---
layout: post
title:  "G1 Young GC 之 根扫描阶段"
date:   2024-08-25 11:00:00 +0200
tags: [GC, G1]
---

本文开始介绍 GC root scan，G1 开始扫描 Java 堆，并将与 GC root 对象关联的对象加入到任务队列中等待执行，在此之后 G1 采用广度优先的算法对回收集进行遍历。

## merge heap root

`prepare_for_merge_heap_roots()` 准备一些数据结构，`G1MergeHeapRootsTask` 实现具体逻辑。

```cpp
//initial_evacuation = true
void G1RemSet::merge_heap_roots(bool initial_evacuation) {
    _scan_state->prepare_for_merge_heap_roots();
    G1MergeHeapRootsTask cl(_scan_state, num_workers, initial_evacuation);
}

//G1MergeHeapRootsTask
virtual void work(uint worker_id) {
    //处理大对象
    G1FlushHumongousCandidateRemSets cl(_scan_state);
    // 2. collection set
    G1MergeCardSetClosure merge(_scan_state);
    G1ClearBitmapClosure clear(g1h);
    G1CombinedClosure combined(&merge, &clear);

    ////遍历年轻代 region 的记忆集
    G1HeapRegionRemSet::iterate_for_merge(g1h->young_regions_cardset(), merge);
    //遍历老年代 region 的记忆集（注意 由于年轻代被遍历了，这里不会重复遍历，因为迭代器没有重置） 
    g1h->collection_set_iterate_increment_from(&combined, nullptr, worker_id); 
}
```
这里主要逻辑都在 `G1MergeCardSetClosure` 里面，它主要做了三件事情。

1. 遍历记忆集，将记忆集中的 region 的 card 对应的 card table 位置标记为 dirty，即将记忆集存储的转换成 card table 存储。
2. 将记忆集中的 region 统一添加到 ` _scan_state._next_dirty_regions` 中。
   
后续 heap root scan 阶段需要依赖上面两类的信息。

3. 将会收集中的 reigon 统一添加到 ` _scan_state._all_dirty_regions`，由于这部分 region 被回收了，那么其对应的 card table 位置需要被清理。

### G1MergeCardSetClosure

```cpp
class G1MergeCardSetClosure : public G1HeapRegionClosure {

    virtual bool do_heap_region(G1HeapRegion* r) {
        assert(r->in_collection_set(), "must be");
        _scan_state->add_all_dirty_region(r->hrm_index());
        merge_card_set_for_region(r);
        return false;
    }

    //G1FlushHumongousCandidateRemSets 直接调用的这个方法
    //难道 Humongous region 不需要加入到 add_all_dirty_region，清理对应的 card table？
    void merge_card_set_for_region(G1HeapRegion* r) {
      assert(r->in_collection_set() || r->is_starts_humongous(), "must be");
      G1HeapRegionRemSet* rem_set = r->rem_set();
      if (!rem_set->is_empty()) { rem_set->iterate_for_merge(*this); }
    }
}
```

`rem_set->iterate_for_merge(*this)` 对记忆集进行遍历。

```cpp
//->rem_set->iterate_for_merge(*this)
//   cl = G1MergeCardSetClosure
//-> iterate_for_merge(CardOrRangeVisitor& cl)
//->iterate_for_merge(_card_set, cl)
//->iterate_for_merge(G1CardSet* card_set, CardOrRangeVisitor& cl)
//  cl2 = G1HeapRegionRemSetMergeCardClosure
//->card_set->iterate_containers(&cl2, true /* at_safepoint */)

void G1CardSet::iterate_containers(ContainerPtrClosure* cl, bool at_safepoint) {
  auto do_value =
    [&] (G1CardSetHashTableValue* value) {
      cl->do_containerptr(value->_region_idx, value->_num_occupied, value->_container);
      return true;
    };
   _table->iterate_safepoint(do_value);
}

//cl = G1HeapRegionRemSetMergeCardClosure
//-> cl->do_containerptr
//-> do_containerptr
//cl = CardOrRanges, _cl=G1MergeCardSetClosure
//-> _card_set->iterate_cards_or_ranges_in_container(container, cl);
```



## G1ParScanThreadState

## scan root

## scan heap root

## scan collection set regions