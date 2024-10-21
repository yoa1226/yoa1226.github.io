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

<image src="/assets/gc-young-gc-root-scan/g1-gc-ygc-rset-scan.png" width = "80%"/>

这里主要逻辑都在 `G1MergeCardSetClosure` 里面，它主要做了三件事情。

1. 遍历记忆集，将记忆集中的 region 的 card 对应的 card table 位置标记为 dirty，即将记忆集存储的转换成 card table 存储。
2. 将记忆集中的 region 统一添加到 ` _scan_state._next_dirty_regions` 中。
   
后续 heap root scan 阶段需要依赖上面两类的信息。

3. 将回收集中的 reigon 统一添加到 ` _scan_state._all_dirty_regions`，由于这部分 region 被回收了，那么其对应的 card table 位置需要被清理。

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
      if (_ct->mark_clean_as_dirty(value)) {
        _scan_state->set_chunk_dirty(_ct->index_for_cardvalue(value));
       }
    }
}
```

`scan_state->set_chunk_dirty`对 card index 进行压缩，64 个 card组成一个 chunk。相当于对 dirty card 分层，添加了一层索引，后续可以看到对

<image src="/assets/gc-young-gc-root-scan/g1-gc-young-gc-root-scan-chunk.png" width="80%"/>

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

对于 java root 部分本小节主要看看线程栈是如何被遍历的，有兴趣的读者可以看看其他部分的源码。

```cpp
G1EvacuationRootClosures* closures = pss->closures();

//closures->strong_oops() = G1ParCopyClosure<G1BarrierNone, should_mark>
//closures->strong_nmethods() = G1NMethodClosure
//should_mark = false
Threads::possibly_parallel_oops_do(is_par, closures->strong_oops(), closures->strong_nmethods());

void Threads::possibly_parallel_threads_do(bool is_par, ThreadClosure* tc) {
  uintx claim_token = Threads::thread_claim_token();
  ALL_JAVA_THREADS(p) {
    if (p->claim_threads_do(is_par, claim_token)) {
      tc->do_thread(p);
    }
  } //omit nonJavaThread
}

//-> Thread::oops_do(OopClosure* f, NMethodClosure* cf) 

void JavaThread::oops_do_frames(OopClosure* f, NMethodClosure* cf) {
  // Traverse the execution stack
  for (StackFrameStream fst(this, true /* update */, false /* process_frames */); !fst.is_done(); fst.next()) {
    fst.current()->oops_do(f, cf, fst.register_map());
  }
}
```

上面代码可以看到是如何遍历 Java 线程和线程栈帧的。关于 Java 线程栈的内容请参考 [Inside the Java Virtual Machine](https://www.artima.com/insidejvm/ed2/jvm8.html)。下面直接论述遍历到的对象如何处理。

### G1ParCopyClosure

对象如果在记忆集中，则需要拷贝到 Survivor reigon，如果对象已经被移动，则将当前指针指向对象移动后的位置。

```cpp
void G1ParCopyClosure<barrier, should_mark>::do_oop_work(T* p) {
  //p->obj
  oop obj = CompressedOops::decode_not_null(heap_oop);
  const G1HeapRegionAttr state = _g1h->region_attr(obj);
  if (state.is_in_cset()) {
    oop forwardee;
    markWord m = obj->mark();
    if (m.is_forwarded()) {
      forwardee = m.forwardee();
    } else {
      forwardee = _par_scan_state->copy_to_survivor_space(state, obj, m);
    }
    RawAccess<IS_NOT_NULL>::oop_store(p, forwardee);
    //omit
  } else {
    if (state.is_humongous_candidate()) {
      _g1h->set_humongous_is_live(obj);
    }
    //omit
  }
  trim_queue_partially();
}
```

在 GC 过程中， 会保留旧对象在原始的位置，然后让旧对象的 markword 指向新对象。

```cpp
RawAccess<IS_NOT_NULL>::oop_store(p, forwardee);
```

如图，遍历 gc root2 时已经移动了对象，在遍历 gc root1 时只需要改变指针的值。

<image src="/assets/gc-young-gc-root-scan/g1-gc-ygc-gc-root-scan-copy-obj.png" width="60%"/>

在对象 markword 中，最低两位为标志为，值为 `11` 时为 GC 标志。

```cpp
static const uintptr_t locked_value             = 0;
static const uintptr_t unlocked_value           = 1;
static const uintptr_t monitor_value            = 2;
static const uintptr_t marked_value             = 3;

bool is_forwarded()   const {
  return (mask_bits(value(), lock_mask_in_place) == marked_value);
}
```

不移动大对象，取消 `candidate` 标记。

```cpp
if (state.is_humongous_candidate()) {
   _g1h->set_humongous_is_live(obj);
}
```

### 对象复制

对象复制在 G1 中专业术语叫做 evacuate object，将处于 cset 中的对象移动到 survivor region。

对象赋值步骤分三部分：

1. 准备阶段
2. 对象赋值
3. 收尾阶段。

当中贯穿着处理失败的逻辑。

#### 准备阶段

获取对象类型和对象大小，如果对象类型是数组并且所在 reigon 被 pinned，不允许对象被移动。

```cpp
Klass* klass = old->klass();
const size_t word_sz = old->size_given_klass(klass);
// JNI only allows pinning of typeArrays, so we only need to keep those in place.
if (region_attr.is_pinned() && klass->is_typeArray_klass()) 
  return handle_evacuation_failure_par(old, old_mark, word_sz, true /* cause_pinned */);

```

计算对象晋升的区域，如果对象年龄小于阈值，则晋升到 survivor 区，反之则晋升到老年代。`has_displaced_mark_helper` 判断对象是否处于锁的状态。

> 对象年龄阈值一般为15，对象头中使用四个 bit 记录对象年龄，最大值为 15。

```cpp
// G1HeapRegionAttr dest_attr = next_region_attr(region_attr, old_mark, age);
G1HeapRegionAttr G1ParScanThreadState::next_region_attr(G1HeapRegionAttr const region_attr, markWord const m, uint& age) {
  if (region_attr.is_young()) {
    age = !m.has_displaced_mark_helper() ? m.age() : m.displaced_mark_helper().age();
    if (age < _tenuring_threshold) { return region_attr; }
  }
  // young-to-old (promotion) or old-to-old; destination is old in both cases.
  return G1HeapRegionAttr::Old;
}
```

`has_monitor()` 判断是否为重量级锁，`has_locker()` 判断是否为栈锁，即经常说的轻量级锁。

可见当对象进入锁状态时，如果是重量级锁，markword 被存储在 monitor 中， 轻量级锁则被存储在线程栈中。

```cpp
markWord markWord::displaced_mark_helper() const {
  assert(has_displaced_mark_helper(), "check");
  if (has_monitor()) {
    // Has an inflated monitor. Must be checked before has_locker().
    ObjectMonitor* monitor = this->monitor();
    return monitor->header();
  }
  if (has_locker()) {  // has a stack lock
    BasicLock* locker = this->locker();
    return locker->displaced_header();
  }
  // This should never happen:
  fatal("bad header=" INTPTR_FORMAT, value());
  return markWord(value());
}
```

> [JEP 374: Deprecate and Disable Biased Locking](https://openjdk.org/jeps/374) Java 逐步放弃偏向锁。

GC 工作线程优先使用 plab（和 tlab 不一样）复制对象，关于 plab 后面有时间单独论述。

> Promotion-Local Allocation Buffers (PLABs) 是一种对象晋升的优化手段。

```cpp
HeapWord* obj_ptr = _plab_allocator->plab_allocate(dest_attr, word_sz, node_index);
```

如果是申请是常规的内存最终会走到 `par_allocate_during_gc` 函数，前面文章讨论记忆集构建的时候说过这段代码。

```cpp
//allocate_copy_slow -> allocate_direct_or_new_plab
//par_allocate_during_gc->par_allocate_during_gc
HeapWord* G1Allocator::par_allocate_during_gc(G1HeapRegionAttr dest, size_t min_word_size, size_t desired_word_size, size_t* actual_word_size, uint node_index) {
  switch (dest.type()) {
    case G1HeapRegionAttr::Young:
      return survivor_attempt_allocation(min_word_size, desired_word_size, actual_word_size, node_index);
    case G1HeapRegionAttr::Old:
      return old_attempt_allocation(min_word_size, desired_word_size, actual_word_size);
      //omit
  }
}
```

#### 复制对象

对象复制的代码相对简单

```cpp
Copy::aligned_disjoint_words(cast_from_oop<HeapWord*>(old), obj_ptr, word_sz);
```

#### 收尾阶段

修改旧对象 forward 指针指向新对象。

```cpp
const oop forward_ptr = old->forward_to_atomic(obj, old_mark, memory_order_relaxed);
if (forward_ptr == nullptr) {
  //修改成功
}else{
  //修改失败，可能与其他线程有竞争关系
}
```

增加对象年龄，统计各个年龄对象大小总和。

> 当对象的年龄超过某个阈值（默认15）或者相同年龄的对象总内存大小超过一半时，超过这个年龄的对象都会被晋升到老年代。

```cpp
if (dest_attr.is_young()) {
  if (age < markWord::max_age) {
    age++;
    obj->incr_age();
  }
  _age_table.add(age, word_sz);
}
```

`_tenuring_threshold` 会根据 `_age_table` 统计数据被重新计算。

```cpp
_g1h->policy()->record_age_table(&_age_table);

void record_age_table(AgeTable* age_table) {
  _survivors_age_table.merge(age_table);
}

void G1Policy::update_survivors_policy() {
  _tenuring_threshold = _survivors_age_table.compute_tenuring_threshold(survivor_size);
}

_tenuring_threshold(g1h->policy()->tenuring_threshold()),
```

遍历对象数组各个元素加入到处理队列中，不赘述。

```cpp
 if (klass->is_array_klass()) {
      if (klass->is_objArray_klass()) {
        start_partial_objarray(dest_attr, old, obj);
      } 
  }
```

遍历对象中引用类型的字段

```cpp
//G1ScanEvacuatedObjClosure
G1SkipCardEnqueueSetter x(&_scanner, dest_attr.is_young());
obj->oop_iterate_backwards(&_scanner, klass);
```

参考文章 [以 ZGC 为例，谈一谈 JVM 是如何实现 Reference 语义的](https://mp.weixin.qq.com/s?__biz=Mzg2MzU3Mjc3Ng==&mid=2247489586&idx=1&sn=4306549c480f668458ab4df0d4b2ea47&chksm=ce77de75f9005763016605e0d268e1a4393a83bfe2a281c915bbf55de99d25cda529195c2843&scene=178&cur_album_id=2291913023118213124#rd)，在 JVM 中有一种叫做 `OopMapBlock` 的结构记录了当前对象引用字段的分布及数量。通过`OopMapBlock` 结构和对象实例数据遍历对象引用类型的字段。

##### 处理对象引用类型字段


```cpp
// This closure is applied to the fields of the objects that have just been copied during evacuation.
// G1ScanEvacuatedObjClosure
inline void G1ScanEvacuatedObjClosure::do_oop_work(T* p) {
  T heap_oop = RawAccess<>::oop_load(p);
  oop obj = CompressedOops::decode_not_null(heap_oop);
  const G1HeapRegionAttr region_attr = _g1h->region_attr(obj);
  if (region_attr.is_in_cset()) { 
    prefetch_and_push(p, obj);
  } else if (!G1HeapRegion::is_in_same_region(p, obj)) {
    handle_non_cset_obj_common(region_attr, p, obj);
    _par_scan_state->enqueue_card_if_tracked(region_attr, p, obj);
  }
  //其他情况不处理，即不属于 cset ，而且处于同一 reigon
}
```

引用类型字段指向的对象处于 cset，封装成任务加入任务队列，下一个阶段继续处理。

```cpp
if (region_attr.is_in_cset()) { //
  prefetch_and_push(p, obj);
} 
_par_scan_state->push_on_queue(ScannerTask(p));
```

如果原对象和属性对象不处于同一个 region：

1. 如果是 humongous region candidate 则取消 candidate。
2. 如果属性对象处于可选优化区，则加入到 `_oops_into_optional_regions` 容器中等待时机处理。

```cpp
if (!G1HeapRegion::is_in_same_region(p, obj)) {
    handle_non_cset_obj_common(region_attr, p, obj);
    assert(_skip_card_enqueue != Uninitialized, "Scan location has not been initialized.");
    if (_skip_card_enqueue == True) { return; }
    _par_scan_state->enqueue_card_if_tracked(region_attr, p, obj);
  }

inline void G1ScanClosureBase::handle_non_cset_obj_common(G1HeapRegionAttr const region_attr, T* p, oop const obj) {
  if (region_attr.is_humongous_candidate()) {
    _g1h->set_humongous_is_live(obj);
  } else if (region_attr.is_optional()) {
    _par_scan_state->remember_reference_into_optional_region(p);
  } }
```

如果原对象和属性对象不处于同一 reigon，并且原对象不处于年轻代，则需要处理跨 region 引用。

>由于年轻代始终会被收集，故不记录从年轻代出发的跨 region 引用。

```cpp
//dest_attr.is_young()
if (_skip_card_enqueue == True) { return; }
_par_scan_state->enqueue_card_if_tracked(region_attr, p, obj);
```

对于 `G1NMethodClosure` 不做深入介绍。

`process_vm_roots` 是处理与虚拟机相关的 gc root，本文不做深入介绍，后续有空出专门的文章介绍。

`process_code_cache_roots` 读者感兴趣可自行浏览源码。

## scan heap root

将记忆集中的 region 作为 heap root，即前文提到的 `_next_dirty_regions`。

```cpp
void G1RemSet::scan_heap_roots(G1ParScanThreadState* pss,....) {
  G1ScanHRForRegionClosure cl(_scan_state, pss, worker_id, scan_phase, remember_already_scanned_cards);
  _scan_state->iterate_dirty_regions_from(&cl, worker_id)
}

void iterate_dirty_regions_from(G1HeapRegionClosure* cl, uint worker_id) {
  uint num_regions = _next_dirty_regions->size();
  uint const start_pos = num_regions * worker_id / max_workers;
  uint cur = start_pos
  do{
    bool result = cl->do_heap_region(g1h->region_at(_next_dirty_regions->at(cur)));
  }while(cur != start_pos)
}
```

`scan_heap_roots` 支持多个线程遍历同一个 region 的不同区域。

```cpp
class G1ScanHRForRegionClosure {
    bool do_heap_region(G1HeapRegion* r) {
    if (_scan_state->has_cards_to_scan(r->hrm_index)) {
      scan_heap_roots(r);
    }
    return false;
  } 
  
  void scan_heap_roots(G1HeapRegion* r) {
    uint const region_idx = r->hrm_index();
    G1CardTableChunkClaimer claim(_scan_state, region_idx);

    while (claim.has_next()) {
      size_t const region_card_base_idx = ((size_t)region_idx << G1HeapRegion::LogCardsPerRegion) + claim.value();

      CardValue* const start_card = _ct->byte_for_index(region_card_base_idx);
      CardValue* const end_card = start_card + claim.size();

      ChunkScanner chunk_scanner{start_card, end_card};
      chunk_scanner.on_dirty_cards([&] (CardValue* dirty_l, CardValue* dirty_r) {
                                     do_claimed_block(region_idx, dirty_l, dirty_r);
                                   });
    } } }

class G1CardTableChunkClaimer {
   bool has_next() {
    while (true) {
      _cur_claim = _scan_state->claim_cards_to_scan(_region_idx, size());
      if (_cur_claim >= G1HeapRegion::CardsPerRegion) {
        return false; }
      if (_scan_state->chunk_needs_scan(_region_idx, _cur_claim)) {
        return true; }
    }
  }
}
```

<image src="/assets/gc-young-gc-root-scan/g1-gc-young-gc-root-scan-chunk.png" width="80%"/>

`_region_scan_chunks` 在遍历记忆集的时候初始化后， 用于加快查找。

```cpp
void set_chunk_range_dirty(size_t const region_card_idx, size_t const card_length) {
  size_t chunk_idx = region_card_idx >> _scan_chunks_shift;
  size_t const end_chunk = (region_card_idx + card_length - 1) >> _scan_chunks_shift;
  for (; chunk_idx <= end_chunk; chunk_idx++) {
    _region_scan_chunks[chunk_idx] = true;
  }
} 
```

`on_dirty_cards` 方法在指定的范围内找到以 `dirty card`开始，以 `non dirty card` 结束的位置，[dirty_l, dirty_r) 就是需要遍历的区域，这个区域内存在跨 region 指针，指向 cset，最后更新 `cur_card`。

```cpp

ChunkScanner chunk_scanner{start_card, end_card};

class ChunkScanner{
  void on_dirty_cards(Func&& f) {
  for (CardValue* cur_card = _start_card; cur_card < _end_card; /* empty */) {
    CardValue* dirty_l = find_first_dirty_card(cur_card);
    CardValue* dirty_r = find_first_non_dirty_card(dirty_l);

    assert(dirty_l <= dirty_r, "inv");

    if (dirty_l == dirty_r) {
      assert(dirty_r == _end_card, "finished the entire chunk");
      return;
    }
    f(dirty_l, dirty_r);
    cur_card = dirty_r + 1;
  } } }
```

`do_claimed_block` 找到 dirty card 区域所在的地址封装成 `MemRegion`，在方法 `sca_memregion` 中最终使用 `G1ScanCardClosure` 遍历目标内存区域。

```cpp
void do_claimed_block(uint const region_idx, CardValue* const dirty_l, CardValue* const dirty_r) {

  HeapWord* const card_start = _ct->addr_for(dirty_l);
  HeapWord* scan_end = MIN2(card_start + (num_cards << (CardTable::card_shift() - LogHeapWordSize)), top);

  MemRegion mr(MAX2(card_start, _scanned_to), scan_end);
  _scanned_to = scan_memregion(region_idx, mr);
}

HeapWord* scan_memregion(uint region_idx_for_card, MemRegion mr) {
  G1HeapRegion* const card_region = _g1h->region_at(region_idx_for_card);
  G1ScanCardClosure card_cl(_g1h, _pss, _heap_roots_found);

  HeapWord* const scanned_to = card_region->oops_on_memregion_seq_iterate_careful<true>(mr, &card_cl);

  _pss->trim_queue_partially();
  return scanned_to;
}
```

`oops_on_memregion_seq_iterate_careful` 首先对 humongous region 做特殊处理，较为简单。

```cpp
// G1ScanCardClosure
HeapWord* G1HeapRegion::oops_on_memregion_seq_iterate_careful(MemRegion mr,
                                                            Closure* cl) {
  if (is_humongous()) {
    return do_oops_on_memregion_in_humongous<Closure, in_gc_pause>(mr, cl);
  }
  return oops_on_memregion_iterate<Closure, in_gc_pause>(mr, cl);
}

```

`oops_on_memregion_iterate` 将内存块分成不可解析区和解析区，前者需要借助于 `bitmap` 定位活对象，在介绍并发标记的时候再论述，后者可直接遍历。

```cpp
// cl = G1ScanCardClosure
inline HeapWord* G1HeapRegion::oops_on_memregion_iterate(MemRegion mr, Closure* cl) {
  // All objects >= pb are parsable. So we can just take object sizes directly.
  while (true) {
    oop obj = cast_to_oop(cur);
    assert(oopDesc::is_oop(obj, true), "Not an oop at " PTR_FORMAT, p2i(cur));
    bool is_precise = false;
    cur += obj->size();
    if (!obj->is_objArray() || (cast_from_oop<HeapWord*>(obj) >= start && cur <= end)) {
      obj->oop_iterate(cl); //遍历普通对象
    } else {
      obj->oop_iterate(cl, mr); //遍历对象数组
      is_precise = true;
    }
    if (cur >= end) {
      return is_precise ? end : cur;
    } } }
```

`G1ScanCardClosure::do_oop_work` 负责处理原对象中的属性对象，因为原对象是在记忆集中的region，如果属性对象在 cset 中，则认为是跨 region 引用。将属性对象加入到任务对了中等待处理。

注意这里不需要移动原对象，因为原对象不在 cset 中。

这里的处理逻辑前文 `G1ScanEvacuatedObjClosure::do_oop_work` 大多数是一致的，不在赘述。

```cpp
inline void G1ScanCardClosure::do_oop_work(T* p) {
  T o = RawAccess<>::oop_load(p);
  oop obj = CompressedOops::decode_not_null(o);

  const G1HeapRegionAttr region_attr = _g1h->region_attr(obj);
  if (region_attr.is_in_cset()) {
    // Since the source is always from outside the collection set, here we implicitly know
    // that this is a cross-region reference too.
    prefetch_and_push(p, obj);
    _heap_roots_found++;
  } else if (!G1HeapRegion::is_in_same_region(p, obj)) {
    handle_non_cset_obj_common(region_attr, p, obj);
    _par_scan_state->enqueue_card_if_tracked(region_attr, p, obj);
  }
}
```

## scan collection set regions

扫描 `native methods` 指向 cset 的引用，感兴趣读者可自行阅读源码，不赘述。

```cpp
void G1RemSet::scan_collection_set_regions(G1ParScanThreadState* pss, uint worker_id,) {
  G1ScanCollectionSetRegionClosure cl(_scan_state, pss, worker_id, scan_phase, coderoots_phase)
}

class G1ScanCollectionSetRegionClosure : public G1HeapRegionClosure {

  bool do_heap_region(G1HeapRegion* r) {
    // Scan code root remembered sets.
    { EventGCPhaseParallel event;
      G1EvacPhaseWithTrimTimeTracker timer(_pss, _code_root_scan_time, _code_trim_partially_time);
      G1ScanAndCountNMethodClosure cl(_pss->closures()->weak_nmethods());

      // Scan the code root list attached to the current region
      r->code_roots_do(&cl);

      _code_roots_scanned += cl.count();

      event.commit(GCId::current(), _worker_id, G1GCPhaseTimes::phase_name(_code_roots_phase)); }
    return false;
}}
```

## 总结

本文从从记忆集扫描出发，着重介绍了 java roots 、heap roots 遍历。`scan roots` 核心是从各个 gc root 触发扫描一层对象树，如果对象在 cset 中，直接加入到任务队列中等待被处理。
