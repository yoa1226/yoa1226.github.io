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

记忆集底层是一个 hashtable，`G1CardSetHashTableValue` 作为 hashtable 的一个元素，存储了 region 和它的 dirty card 集合。

```cpp
//->rem_set->iterate_for_merge(*this)
//   cl = G1MergeCardSetClosure
//-> iterate_for_merge(CardOrRangeVisitor& cl)
//->iterate_for_merge(_card_set, cl)
//->iterate_for_merge(G1CardSet* card_set, CardOrRangeVisitor& cl)

G1HeapRegionRemSetMergeCardClosure<CardOrRangeVisitor, G1ContainerCardsOrRanges> cl2
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
//cl = G1ContainerCardsOrRanges, _cl=G1MergeCardSetClosure
void do_containerptr(uint card_region_idx, size_t num_occupied, G1CardSet::ContainerPtr container) override {
    CardOrRanges<Closure> cl(_cl,
                             card_region_idx >> _log_card_regions_per_region,
                             (card_region_idx & _card_regions_per_region_mask) << _log_card_region_size);
    _card_set->iterate_cards_or_ranges_in_container(container, cl);
  }

//-> _card_set->iterate_cards_or_ranges_in_container(container, cl);
```

对比下面的代码，就知道`CardOrRanges<Closure>` 是 `G1ContainerCardsOrRanges`类型，`Closure` 是 `CardOrRangeVisitor` 类型。
```cpp
G1HeapRegionRemSetMergeCardClosure<CardOrRangeVisitor, G1ContainerCardsOrRanges> cl2

template <typename Closure, template <typename> class CardOrRanges
class G1HeapRegionRemSetMergeCardClosure : public G1CardSet::ContainerPtrClosure
```

下面继续遍历 dirty card 集合

```cpp
//cl = G1ContainerCardsOrRanges
nline void G1CardSet::iterate_cards_or_ranges_in_container(ContainerPtr const container, CardOrRangeVisitor& cl) {
  switch (container_type(container)) {
    case ContainerInlinePtr: {
      if (cl.start_iterate(G1GCPhaseTimes::MergeRSMergedInline)) {
        G1CardSetInlinePtr ptr(container);
        ptr.iterate(cl, _config->inline_ptr_bits_per_card());
      } return; } }
      /* omit */ }
```

`cl.start_iterate` 判断是否进行遍历。

```cpp
class G1ContainerCardsOrRanges {
    Closure& _cl; //G1MergeCardSetClosure
    bool start_iterate(uint tag) {
    return _cl.start_iterate(tag, _region_idx);
  }
}

class G1MergeCardSetClosure : public G1HeapRegionClosure {
   bool start_iterate(uint const tag, uint const region_idx) {
      if (remember_if_interesting(region_idx)) {
        _region_base_idx = (size_t)region_idx << G1HeapRegion::LogCardsPerRegion;
        return true;
      }
      return false; } 

  bool remember_if_interesting(uint const region_idx) {
    // (hr != nullptr && !hr->in_collection_set() && hr->is_old_or_humongous())
    if (!_scan_state->contains_cards_to_process(region_idx)) {
      return false;
    }
    _scan_state->add_dirty_region(region_idx);
    return true; }}
```

`remember_if_interesting` 只处理不在回收集中，并且是 old region 或者 是 humongous region。

1. 记忆集记录跨 region 引用指的是， old region 或者 humongous region 指向其他 region 的引用，这部分引用称之为 heap root。
2. 如果 old region 在回收集中，后续全堆遍历时一定会遍历，不用加入到 ` _scan_state._next_dirty_regions`中。

对每个 dirty card 进行处理，`_merge_card_set_cache` 用于优化性能，`mark_clean_as_dirty` 将存在跨 region 引用的 card 标记为 dirty card。

```cpp
//->ptr.iterate(cl, _config->inline_ptr_bits_per_card());
//->found(value & card_mask);
class G1ContainerCardsOrRanges {
  void operator()(uint card_idx) {
    _cl.do_card(card_idx + _offset);
  } }
  
class G1MergeCardSetClosure : public G1HeapRegionClosure {
    void do_card(uint const card_idx) {
    G1CardTable::CardValue* to_prefetch = _ct->byte_for_index(_region_base_idx + card_idx);
    G1CardTable::CardValue* to_process = _merge_card_set_cache.push(to_prefetch);
    mark_card(to_process);
  }
  
    void mark_card(G1CardTable::CardValue* value) {
      if (_ct->mark_clean_as_dirty(value)) { }
    }
}
```

## scan root

scan root 的代码如下，核心逻辑封装在 `scan_roots` 中：

1. `evacuate_roots` 扫描 Java GC root 对象和 VM GC root 对象。如果对象在 rset 中，需要复制到 Survisor region。
2. `scan_heap_roots` 根据前文的 ` _scan_state._next_dirty_regions` 扫描region，并根据全局卡表的记录只扫描 dirty card提高性能。
3. `scan_collection_set_regions` 扫描 cset。

在深入了解 scan root 之前，我们先了解 `G1ParScanThreadState`。

```cpp
evacuate_initial_collection_set(){
  G1RootProcessor root_processor(_g1h, num_workers);
  G1EvacuateRegionsTask g1_par_task(....);
}

class G1EvacuateRegionsTask : public G1EvacuateRegionsBaseTask {
  void work(uint worker_id) {
    start_work(worker_id);
    G1ParScanThreadState* pss = _per_thread_states->state_for_worker(worker_id);
    pss->set_ref_discoverer(_g1h->ref_processor_stw());
    scan_roots(pss, worker_id); //scan root
    evacuate_live_objects(pss, worker_id);
    end_work(worker_id);
  } 
}

void scan_roots(G1ParScanThreadState* pss, uint worker_id) {
  _root_processor->evacuate_roots(pss, worker_id);
  _g1h->rem_set()->scan_heap_roots(pss, worker_id, G1GCPhaseTimes::ScanHR, G1GCPhaseTimes::ObjCopy, _has_optional_evacuation_work);
  _g1h->rem_set()->scan_collection_set_regions(pss, worker_id, G1GCPhaseTimes::ScanHR, G1GCPhaseTimes::CodeRoots, G1GCPhaseTimes::ObjCopy);
}

```

### G1ParScanThreadState

下面是关于 `G1ParScanThreadState` 初始化的精简代码，目前只关注以下字段，这里代码稍微有点多，读者需要耐心点，大部分都是关于 `_closures` 怎么初始化的。

```cpp
G1ParScanThreadState::G1ParScanThreadState(...):
  _task_queue(g1h->task_queue(worker_id)),
  _scanner(g1h, this),
{ _closures = G1EvacuationRootClosures::create_root_closures(_g1h, this, ....); }
 
G1EvacuationRootClosures::create_root_closures(...){
  return new G1EvacuationClosures(g1h, pss, process_only_dirty_klasses);
}

class G1EvacuationClosures : public G1EvacuationRootClosures {
  G1SharedClosures<false> _closures;
  OopClosure* strong_oops() { return &_closures._oops; }
  CLDClosure* weak_clds()             { return &_closures._clds; }
  CLDClosure* strong_clds()           { return &_closures._clds; }
  NMethodClosure* strong_nmethods()   { return &_closures._nmethods; }
  NMethodClosure* weak_nmethods()     { return &_closures._nmethods; }
}

template <bool should_mark> class G1SharedClosures {
  G1ParCopyClosure<G1BarrierNone, should_mark> _oops;
  G1ParCopyClosure<G1BarrierCLD,  should_mark> _oops_in_cld;
  G1ParCopyClosure<G1BarrierNoOptRoots, should_mark> _oops_in_nmethod;
  G1CLDScanClosure                _clds;
  G1NMethodClosure                _nmethods;
}
```

-  `_task_queue` 是每个线程的工作队列，root scan 时会将任务提交到这里。
-  `_scanner` 封装了复制对象（evacuate object）的逻辑，下一篇详细说明。
-  `_closures` 封装了 GC root 的处理逻辑，具体来说是针对不同 gc root 对象由不同的 `Closure` 处理。

知道 `G1ParScanThreadState` 基本知识以后，正式进入 gc root scan 阶段。

## java root

## vm root

## scan heap root

## scan collection set regions