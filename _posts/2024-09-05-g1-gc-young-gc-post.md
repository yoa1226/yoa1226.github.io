---
layout: post
title:  "G1 Young GC 之收尾阶段"
date:   2024-09-05 11:00:00 +0200
tags: [GC, G1]
---

前文 GC 复制阶段已经将所有的对象从 cset 复制到其他 region，本文收尾阶段将对一些遗留问题进行处理。收尾阶段任务很多，仅讲解一些与前文相关性比较大的任务。

## 总览

` allocator()->release_gc_alloc_regions` 释放对象复制时使用的 region。

`post_evacuate_cleanup_1` 任务集合1。

`post_evacuate_cleanup_2` 任务集合2。

`_evac_failure_regions.post_collection` 重置记录失败信息的数据结构。

`rebuild_free_region_list` 重新构造空闲 region 列表。

`prepare_for_mutator_after_young_collection` 为 Java 线程活动准备 region，并将 Survivor 区域加入到回收集。

```cpp
void G1YoungCollector::post_evacuate_collection_set(G1EvacInfo* evacuation_info, G1ParScanThreadStateSet* per_thread_states) {

  //omit process weak reference process

  allocator()->release_gc_alloc_regions(evacuation_info);

  post_evacuate_cleanup_1(per_thread_states);

  post_evacuate_cleanup_2(per_thread_states, evacuation_info);

  //concurrent gc
  if (collector_state()->in_concurrent_start_gc()) {
    enqueue_candidates_as_root_regions();
  }

  _evac_failure_regions.post_collection();
  

  _g1h->rebuild_free_region_list();

  _g1h->prepare_for_mutator_after_young_collection();

  _g1h->expand_heap_after_young_collection();
}
```

## G1ClearCardTableTask

此任务是清空部分 region 的 card table。

`add_all_dirty_region` 在前面文章说过代表的是 cset 所有 region。由于 cset 中的对象都被清空，那么就不存在跨 region 指针，对应的 card table 区域也需要被清空。

`r->clear_cardtable()` 执行清空任务。

```cpp
new G1ClearCardTableTask(G1CollectedHeap::heap(), _all_dirty_regions, this);

void do_work(uint worker_id) override {
    const uint num_regions_per_worker = num_cards_per_worker / (uint)G1HeapRegion::CardsPerRegion;

    while (_cur_dirty_regions < _regions->size()) {
    uint next = Atomic::fetch_then_add(&_cur_dirty_regions, num_regions_per_worker);
    uint max = MIN2(next + num_regions_per_worker, _regions->size());
    for (uint i = next; i < max; i++) {
        G1HeapRegion* r = _g1h->region_at(_regions->at(i));
        r->clear_cardtable();
    }
    }
}
```

## RestoreEvacFailureRegionsTask

此任务处理复制失败的 region，也就说 region 上还有对象没有被转移，需要做特殊处理。

`_chunk_bitmap` 用于记录被遍历的内存块。

`claim_chunk` 用于同步工作线程。

`zap_dead_objects` 填充活对象之间的区域。

```cpp
class G1PostEvacuateCollectionSetCleanupTask1::RestoreEvacFailureRegionsTask{

  CHeapBitMap _chunk_bitmap;
  void do_work(uint worker_id) override {
    for (uint i = 0; i < total_chunks; i++) {
    const uint chunk_idx = (start_chunk_idx + i) % total_chunks;
    if (claim_chunk(chunk_idx)) {
        process_chunk(worker_id, chunk_idx);
        } } } 
}

 void process_chunk(uint worker_id, uint chunk_idx) {
    //omit
    HeapWord* first_marked_addr = bitmap->get_next_marked_addr(chunk_start, hr_top);
    HeapWord* obj_addr = first_marked_addr;

    do {
      oop obj = cast_to_oop(obj_addr);
      const size_t obj_size = obj->size();
      HeapWord* const obj_end_addr = obj_addr + obj_size;

      obj->init_mark();
      hr->update_bot_for_block(obj_addr, obj_end_addr);

      HeapWord* next_marked_obj_addr = bitmap->get_next_marked_addr(obj_end_addr, hr_top);
      garbage_words += zap_dead_objects(hr, obj_end_addr, next_marked_obj_addr);
      obj_addr = next_marked_obj_addr;
    } while (obj_addr < chunk_end);

 }
```

## RestorePreservedMarksTask

对于转移失败的对象，并且 markword 被存储的需要重新恢复。

```cpp

class G1PostEvacuateCollectionSetCleanupTask2::RestorePreservedMarksTask : public G1AbstractSubTask {
  WorkerTask* _task;
public:
  RestorePreservedMarksTask(PreservedMarksSet* preserved_marks) :
    _task(preserved_marks->create_task()) { }

  void do_work(uint worker_id) override { _task->work(worker_id); }
};

WorkerTask* PreservedMarksSet::create_task() {
  return new RestorePreservedMarksTask(this);
}
//_preserved_marks_set->get(task_id)->restore_and_increment(&_total_size);
//restore()
void PreservedMarks::restore() {
  while (!_stack.is_empty()) {
    const PreservedMark elem = _stack.pop();
    elem.set_mark(); //_o->set_mark(_m);
  }
  assert_empty();
}
```

## ProcessEvacuationFailedRegionsTask

对于在 cset 中， 还存在对象的region，看情况在 Young GC 阶段清空 mark bit。

```cpp
bool clear_mark_data = !g1h->collector_state()->in_concurrent_start_gc() ||
                        g1h->policy()->should_retain_evac_failed_region(r);

if (clear_mark_data) {
  g1h->clear_bitmap_for_region(r);
} else {
  // This evacuation failed region is going to be marked through. Update mark data.
  cm->update_top_at_mark_start(r);
  cm->set_live_bytes(r->hrm_index(), r->live_bytes());
  assert(cm->mark_bitmap()->get_next_marked_addr(r->bottom(), cm->top_at_mark_start(r)) != cm->top_at_mark_start(r),
          "Marks must be on bitmap for region %u", r->hrm_index());
}
```

## RedirtyLoggedCardsTask

处理 dirty card 任务队列，处理两类 region：

1. 不在 cset 中。
2. 在 cset 中，但是还有对象未移动。

```cpp
void do_card_ptr(CardValue* card_ptr) override {
G1HeapRegion* hr = region_for_card(card_ptr);
    // Should only dirty cards in regions that won't be freed.
    if (!will_become_free(hr)) {
      *card_ptr = G1CardTable::dirty_card_val();
      _num_dirtied++;
    }
}

bool will_become_free(G1HeapRegion* hr) const {
  // A region will be freed by during the FreeCollectionSet phase if the region is in the
  // collection set and has not had an evacuation failure.
  return _g1h->is_in_cset(hr) && !_evac_failure_regions->contains(hr->hrm_index());
}
```

## FreeCollectionSetTask

释放 cset，对于还有对象没有处理的 region ，如果存活对象小于某个阈值则需要加入到 cset 候选中，反之不处理。

对于清空的 reigon 则加入 `free_list` 中。

```cpp
virtual bool do_heap_region(G1HeapRegion* r) {
    if (_evac_failure_regions->contains(r->hrm_index())) {
      handle_failed_region(r);
    } else {
      handle_evacuated_region(r);
    }
}
```

## 总结

本文简单的介绍了 G1 的收尾阶段，对其中感兴趣的内容读者可自行深入阅读源码。