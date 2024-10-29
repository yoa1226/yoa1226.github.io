---
layout: post
title:  "G1 并发标记之重标记"
date:   2024-10-15 11:00:00 +0200
tags: [GC, G1]
---

在并发标记中，heap 中所有 region 活着的对象都已经被标记（bottom 到 TAMS 之间的区域），并且每个 region 存活对象占用的大小也会被记录，重标记利用上述信息对 region 进行处理。重标记指的是对 SATB 队列中的对象重新标记，但是在 G1 的代码中， 它有更多的处理任务。

## region 存活对象

在并发标记的过程中，不仅会标记存活的对象，而且会统计每个 region 存活对象总的大小。

```cpp

inline bool G1ConcurrentMark::mark_in_bitmap(uint const worker_id, oop const obj) {
  bool success = _mark_bitmap.par_mark(obj);
  if (success) {
    add_to_liveness(worker_id, obj, obj->size());
  }
  return success;
}

//add_to_liveness -> update_liveness -> add_live_words
void add_live_words(uint region_idx, size_t live_words) {
    G1RegionMarkStatsCacheEntry* const cur = find_for_add(region_idx);
    cur->_stats._live_words += live_words;
}
```

## 重标记

重标记的代码很简单，首先是刷新 SATB 队列，然后在 `do_marking_step` 调用 `drain_satb_buffers` 处理队列中的对象，后面的处理逻辑与并发标记类似，但是不需要遍历 region，本文不在赘述。

```cpp
//remark() -> finalize_marking()
class G1CMRemarkTask : public WorkerTask {
  void work(uint worker_id) {
    {
      G1RemarkThreadsClosure threads_f(G1CollectedHeap::heap(), task);
      Threads::possibly_parallel_threads_do(true /* is_par */, &threads_f);
    }
    do {
      task->do_marking_step(1000000000.0 /* something very large */,
                            true         /* do_termination       */,
                            false        /* is_serial            */);
    } while (task->has_aborted() && !_cm->has_overflown());
  }
}

//drain_satb_buffers();
```

重标记完成以后需要设置 SATB 为关闭。

```cpp
SATBMarkQueueSet& satb_mq_set = G1BarrierSet::satb_mark_queue_set();
satb_mq_set.set_active_all_threads(false, /* new active value */ true /* expected_active */);
```

确认在 region 的 tams 到 top 之间在 mark bit map 上不应该有标记。

```cpp
 _g1h->verifier()->verify_bitmap_clear(true /* above_tams_only */)
```

## 更新 region 数据

```cpp
// G1UpdateRegionLivenessAndSelectForRebuildTask cl(_g1h, this, _g1h->workers()->active_workers());

struct G1OnRegionClosure : public G1HeapRegionClosure {
    bool do_heap_region(G1HeapRegion* hr) override {}
}
```

### humongous region 处理

如果 tams == bottom 或者在并发标记时标记为存活的对象，那么此 region 暂且不能不被回收。

> 如果 tams == bottom，证明该 humongous region 是并发开始后才分配的 region，默认为存活的。

```cpp
const bool is_live = _cm->top_at_mark_start(hr) == hr->bottom()
                             || _cm->contains_live_object(hr->hrm_index());
if (is_live) {
    const bool selected_for_rebuild = tracker->update_humongous_before_rebuild(hr);
    auto on_humongous_region = [&] (G1HeapRegion* hr) {
    if (selected_for_rebuild) {
        _num_selected_for_rebuild++;
    }
    _cm->update_top_at_rebuild_start(hr);
    };

    _g1h->humongous_obj_regions_iterate(hr, on_humongous_region);
} else {
    reclaim_empty_humongous_region(hr);
}
```

`reclaim_empty_humongous_region` 释放没有活对象的 region。

`update_top_at_rebuild_start` 记录重建时 top 的位置，即 TARS，重建主要是重建记忆集。

由于大对象可能占多个 region，`humongous_obj_regions_iterate` 处理连接起来的 humongous region。

```cpp
do {
    G1HeapRegion* next = _hrm.next_region_in_humongous(start);
    f(start);
    start = next;
} while (start != nullptr);

inline G1HeapRegion* G1HeapRegionManager::next_region_in_humongous(G1HeapRegion* hr) const {
  uint index = hr->hrm_index();
  index++;
  if (index < reserved_length() && is_available(index) && at(index)->is_continues_humongous()) {
    return at(index);
  } else { return nullptr; }
}
```

### old region 处理

首先更新 region 死对象所占空间大小，即 bottom 与 tams 之间的区域减去已经被标记的区域。

其次是记录 region 可解析的位置，可解析指的是可以根据对象大小对 reigon 进行遍历，由于并发标记的存在，可能存在死对象空洞，这些对象由于对应的类型被卸载而无法被解析。因为 bottom 和 tams 之间是被标记的区域，可解析区设置为 `top_at_mark_start`。

其他代码和 humongoug region 处理类似。

```cpp
inline void G1HeapRegion::note_end_of_marking(HeapWord* top_at_mark_start, size_t marked_bytes) {
  assert_at_safepoint();

  if (top_at_mark_start != bottom()) {
    _garbage_bytes = byte_size(bottom(), top_at_mark_start) - marked_bytes;
  }

  if (needs_scrubbing()) {
    _parsable_bottom = top_at_mark_start;
  }
}
```

注意：young Region 不需要被处理，因为他们不会被标记，也不需要被标记。

## 重建记忆集

此阶段包含重建记忆集和擦除死对象。

```cpp
//->phase_rebuild_and_scrub -> rebuild_and_scrub -> 
//-> G1ConcurrentRebuildAndScrub::rebuild_and_scrub
class G1RebuildRSAndScrubTask {
    void work(uint worker_id) {
        G1CollectedHeap* g1h = G1CollectedHeap::heap();
        G1RebuildRSAndScrubRegionClosure cl(_cm, _should_rebuild_remset, worker_id);
        g1h->heap_region_par_iterate_from_worker_offset(&cl, &_hr_claimer, worker_id);
    } 
}
```

### old region

先看 old region 处理，对于不可解析区 [start,pb) ，使用 mark bit map 进行遍历，对于可解析区 [pb, TARS) 直接遍历。

> 注意，此处 pb = TAMS 

```cpp
// Scan and scrub the given region to tars.
void scan_and_scrub_region(G1HeapRegion* hr, HeapWord* const pb) {

  {
    // Step 1: Scan the given region from bottom to parsable_bottom.
    HeapWord* start = hr->bottom();
    HeapWord* limit = pb; //_parsable_bottom
    while (start < limit) {
      start = scan_or_scrub(hr, start, limit);

      if (yield_if_necessary(hr)) {
        return;
  } } }

  // Scrubbing completed for this region - notify that we are done with it, resetting
  // pb to bottom.
  hr->note_end_of_scrubbing();
  {
    // Step 2: Rebuild from TAMS (= parsable_bottom) to TARS.
    HeapWord* start = pb;
    HeapWord* limit = _cm->top_at_rebuild_start(hr);
    while (start < limit) {
      start += scan_object(hr, start);

      if (yield_if_necessary(hr)) {
        return;
  } } } }
```

`scan_object(hr, addr)` 遍历活着的对象，`get_next_marked_addr ` 找到下一个活对象的位置，`fill_range_with_dead_objects` 填充死对象所占的区域。

```cpp
// Scan or scrub depending on if addr is marked.
HeapWord* scan_or_scrub(G1HeapRegion* hr, HeapWord* addr, HeapWord* limit) {
  if (_bitmap->is_marked(addr)) {
    //  Live object, need to scan to rebuild remembered sets for this object.
    return addr + scan_object(hr, addr);
  } else {
    // Found dead object (which klass has potentially been unloaded). Scrub to next marked object.
    HeapWord* scrub_end = _bitmap->get_next_marked_addr(addr, limit);
    hr->fill_range_with_dead_objects(addr, scrub_end);
    // Return the next object to handle.
    return scrub_end;
  }
}
```

`G1RebuildRemSetClosure::do_oop_work` 遍历引用类型的属性，判断是否是跨 region 引用，加入到记忆集。

```cpp
template <class T> void G1RebuildRemSetClosure::do_oop_work(T* p) {
  oop const obj = RawAccess<MO_RELAXED>::oop_load(p);
  if (G1HeapRegion::is_in_same_region(p, obj)) { return; }

  G1HeapRegion* to = _g1h->heap_region_containing(obj);
  G1HeapRegionRemSet* rem_set = to->rem_set();
  if (rem_set->is_tracked()) {
    rem_set->add_reference(p, _worker_id);
  }
}
```

对于非解析区较为简单不在赘述。

### humongous region

大对象可能横跨几个 region，`MIN2(hr->top(), humongous_end)` 这里只处理当前 region 。

```cpp
void scan_humongous_region(G1HeapRegion* hr, HeapWord* const pb) {
  HeapWord* humongous_end = hr->humongous_start_region()->bottom() + humongous->size();
  MemRegion mr(hr->bottom(), MIN2(hr->top(), humongous_end));
  scan_large_object(hr, humongous, mr);
}
```

## 回收集

`do_heap_region` 根据条件是否满足将 old region 加入到目标集合中。G1MixedGCLiveThresholdPercent 默认值为 85 %，对象存活总大小低于此值的 region 将被加入到目标集合。

```cpp
void G1CollectionSetChooser::build(WorkerThreads* workers, uint max_num_regions, G1CollectionSetCandidates* candidates) {

  G1BuildCandidateRegionsTask cl(max_num_regions, chunk_size, num_workers);
  workers->run_task(&cl, num_workers);

  cl.sort_and_prune_into(candidates);
  candidates->verify();
}

class G1BuildCandidateRegionsClosure : public G1HeapRegionClosure {

    bool do_heap_region(G1HeapRegion* r) {
    if (!r->is_old() || r->is_collection_set_candidate()) { return false; }

    if (!r->rem_set()->is_tracked()) { return false; }

    bool should_add = !G1CollectedHeap::heap()->is_old_gc_alloc_region(r) &&
                    G1CollectionSetChooser::region_occupancy_low_enough_for_evac(r->live_bytes());

    if (should_add) { add_region(r); } 
    else { r->rem_set()->clear(true /* only_cardset */); }
    return false;
}
}

static size_t mixed_gc_live_threshold_bytes() {
    return G1HeapRegion::GrainBytes * (size_t)G1MixedGCLiveThresholdPercent / 100;
}

static bool region_occupancy_low_enough_for_evac(size_t live_bytes) {
    return live_bytes < mixed_gc_live_threshold_bytes();
}
```

`sort_and_prune_into` 对目标集合进行排序，然后根据条件排除掉回收效益低的 region，不在目标集合中的 region 需要清除记忆集，因为记忆集对于这些 region 没有作用反而会占用内存。

最后将目标集合加入到回收候选集合中。

```cpp
void sort_and_prune_into(G1CollectionSetCandidates* candidates) {
    _result.sort_by_reclaimable_bytes();
    prune(_result.array());
    candidates->set_candidates_from_marking(_result.array(),
                                        _num_regions_added);
}
```

## 总结

本文首先介绍了 region 被标记时活对象的统计、其次介绍了重标记、其次介绍了 region 数据的更新和记忆集的重新构建，最后介绍了 old region 是如何被加入到回收集中的。其他细节读者可自行查阅源码。



