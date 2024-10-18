---
layout: post
title:  "G1 Young GC 之准备阶段"
date:   2024-08-15 11:00:00 +0200
tags: [GC, G1]
---

前面两篇文章对 G1 的一些概念和重要组件做了部分介绍，从本文开始介绍 G1 的收集流程，首先分四篇文章介绍 G1 Young GC，而后介绍并发标记的流程。本文开始介绍 G1 Young GC 的准备阶段。

## G1YoungCollector

当内存分配失败时就会导致 Young GC，准确来说是年轻代内存耗尽，本小节不关注分配失败的流程，只是列出调用链，后续会出文章介绍。

> 年轻代内存大小是根据当前 GC 停顿时间是否满足目标停顿时间（-XX:MaxGCPauseMillis=200）动态调整的。年轻代大小的范围通过参数 -XX:G1NewSizePercent=5 和 -XX:G1MaxNewSizePercent=60 设置

```cpp
//调用链
//->mem_allocate->attempt_allocation->attempt_allocation_slow
//->do_collection_pause->VMThread::execute(&op)
//inner_execute-> evaluate_operation-> op->evaluate()
//->do_collection_pause_at_safepoint

void G1CollectedHeap::do_collection_pause_at_safepoint_helper() {

  policy()->decide_on_concurrent_start_pause(); //是否要进行并发标记判断

  G1YoungCollector collector(gc_cause());
  collector.collect(); // Young GC
   
  if (collector_state()->in_concurrent_start_gc()) {//是否执行并发标记
    start_concurrent_cycle(collector.concurrent_operation_is_full_mark());
  }
}
```

`do_collection_pause_at_safepoint_helper` 是 GC 的入口函数，首先计算并发标记的标志，`G1CollectorState` 保存当前 GC 的类型。

GC 的具体工作是调用 `G1YoungCollector` 管理的，`collect` 方法负责开启 GC。

`start_concurrent_cycle` 根据条件允许开启并发标记，所以说 `Young GC` 是并发标记的第一个步骤。

## Young GC

首先用过 GC 日志查看 Young GC 的基本步骤：

<image src="/assets/ygc-pre/ygc-pre-log.png" width = "80%">

- Pre Evacuate Collection Set: 准备工作，本文重点。
- Merge Heap Roots（Root Scan）：gc root 扫描，这里特指 Java 堆，然而 gc 有很多，所以笔者倾向于把这个阶段重新命名为 Root Scan。
- Evacuate Collection Set：debug 日志中会把一些 root scan 放在这里面，笔者将 root scan 移动到上面的阶段，本阶段包含的是复制回收集的活对象到新的 region 中。
- Post Evacuate Collection Set：非强引用处理、数据清理，统计相关的工作。

`Young GC` 的代码结构如下：

```cpp
void G1YoungCollector::collect() {
    set_young_collection_default_active_worker_threads(); 
    wait_for_root_region_scanning();
    {
        pre_evacuate_collection_set(jtm.evacuation_info()); 
        // Actually do the work...
        evacuate_initial_collection_set(&per_thread_states, may_do_optional_evacuation);
        if (may_do_optional_evacuation) {
          evacuate_optional_collection_set(&per_thread_states);
        }
        post_evacuate_collection_set(jtm.evacuation_info(), &per_thread_states); 
    }
}
```

首先设置本次 GC 工作线程的数量，而后需要等待并发标记 root scan 完成，此阶段不能被打断。

`gc root scan ` 和 `Evacuate Collection Set` 逻辑都在 `evacuate_initial_collection_set`方法中。

`pre_evacuate_collection_set` 是 gc 准备阶段的入口函数。

## 准备阶段

准备阶段分成多个步骤，每个步骤如果有多行代码 JVM 会使用大括号包装起来，下面依次介绍每个步骤。

```cpp
void G1YoungCollector::pre_evacuate_collection_set(G1EvacInfo* evacuation_info) {
// Flush various data in thread-local buffers to be able to determine the collection  set
    G1PreEvacuateCollectionSetBatchTask cl;

    calculate_collection_set(evacuation_info, policy()->max_pause_time_ms())
    
    if (collector_state()->in_concurrent_start_gc()) {
    concurrent_mark()->pre_concurrent_start(_gc_cause); }
    
   // how reference processing currently works in G1.
   ref_processor_stw()->start_discovery(false /* always_clear */);
   
   _evac_failure_regions.pre_collection(_g1h->max_reserved_regions());
   
     // Initialize the GC alloc regions.
   allocator()->init_gc_alloc_regions(evacuation_info);
  
   rem_set()->prepare_for_scan_heap_roots();
   _g1h->prepare_group_cardsets_for_scan();
   
   G1PrepareEvacuationTask g1_prep_task(_g1h);
}
```

## 刷新线程缓冲区

`G1PreEvacuateCollectionSetBatchTask` 继承自 `WorkerTask`，`WorkerTask` 工作原理前面的文章已经论述了。

```cpp
G1PreEvacuateCollectionSetBatchTask::G1PreEvacuateCollectionSetBatchTask() :
  G1BatchedTask("Pre Evacuate Prepare", G1CollectedHeap::heap()->phase_times()),
  _old_pending_cards(G1BarrierSet::dirty_card_queue_set().num_cards()),
  _java_retire_task(new JavaThreadRetireTLABAndFlushLogs()),
  _non_java_retire_task(new NonJavaThreadFlushLogs()) {

  // Disable mutator refinement until concurrent refinement decides otherwise.
  G1BarrierSet::dirty_card_queue_set().set_mutator_refinement_threshold(SIZE_MAX);

  add_serial_task(_non_java_retire_task);
  add_parallel_task(_java_retire_task);
}
```

先看构造函数，加入了两个任务，一个是 Java Thread 相关的，并且是并行任务，另外一个反之。任务的主要作用是刷新线程本地缓冲区。

```cpp
class G1PreEvacuateCollectionSetBatchTask : class G1BatchedTask : public WorkerTask {
    void G1BatchedTask::work(uint worker_id) {
      int t = 0;
      while (try_claim_serial_task(t)) {
        G1AbstractSubTask* task = _serial_tasks.at(t);
        task->do_work(worker_id);
      }
      for (G1AbstractSubTask* task : _parallel_tasks) {
        task->do_work(worker_id);
  } } }
```

`work` 方法中使用 `try_claim_serial_task`方法分发串行任务。

### 刷新 Java 线程本地缓冲区

下面以 Java 线程为例介绍缓冲刷新，继续看并行任务怎么分配的：

```cpp
class G1PreEvacuateCollectionSetBatchTask::JavaThreadRetireTLABAndFlushLogs : public G1AbstractSubTask {
    G1JavaThreadsListClaimer _claimer;
  // There is relatively little work to do per thread.
  static const uint ThreadsPerWorker = 250
  
    JavaThreadRetireTLABAndFlushLogs() :
    _claimer(ThreadsPerWorker) { }

  void do_work(uint worker_id) override {
    RetireTLABAndFlushLogsClosure tc;
    _claimer.apply(&tc);
  }
}

inline void G1JavaThreadsListClaimer::apply(ThreadClosure* cl) {
  JavaThread* const* list; uint count;

  while ((list = claim(count)) != nullptr) {//所有的 GC 工作线程会并行执行到这里
    for (uint i = 0; i < count; i++) {
      cl->do_thread(list[i]);
    } } }
```

`_claimer` 初始化的时候会获取线程列表，然后使用 `claim` 方法依据步长 `_claim_step` 切分线程列表分发任务给 GC 工作线程。

```cpp
inline JavaThread* const* G1JavaThreadsListClaimer::claim(uint& count) {
  count = 0;
  uint claim = Atomic::fetch_then_add(&_cur_claim, _claim_step); //保证原子性
  count = MIN2(_list.length() - claim, _claim_step);
  return _list.list()->threads() + claim;
}
```

`G1BatchedTask` 对任务进行封装，串行任务列表中的任务是单个线程执行单个任务，而并行任务列表中的单个任务是同时被多个线程执行的，在任务内部切割任务的进行分发。

```cpp
struct RetireTLABAndFlushLogsClosure : public ThreadClosure {

  void do_thread(Thread* thread) override {
    // Flushes deferred card marks, so must precede concatenating logs.
    BarrierSet::barrier_set()->make_parsable((JavaThread*)thread);
    // Retire TLABs.
    if (UseTLAB) { thread->tlab().retire(&_tlab_stats); }

      // Concatenate logs.
      G1DirtyCardQueueSet& qset = G1BarrierSet::dirty_card_queue_set();
      _refinement_stats += qset.concatenate_log_and_stats(thread);

      // Flush region pin count cache.
      G1ThreadLocalData::pin_count_cache(thread).flush();
  }
}
```

`thread->tlab().retire(&_tlab_stats)` : 
  1. 填充 Tlab 未使用的区域，这么做是为了遍历时地址有效和安全。
  2. 断开当前线程与 TLAB 缓冲区的关联。

`qset.concatenate_log_and_stats(thread)`: 刷新本地 `dirty card queue` 到全局队列 `_completed` 中。

`G1ThreadLocalData::pin_count_cache(thread).flush()`: 刷新 region pin 计数，与 JEP 423 有关（前面的文章有做介绍）。

## 回收集

回收集后面简称 cset ，是本次 Young GC 要回收 region 的集合，包含所有年轻代 region 区域。

如果当前是 Young (Mixed) 则根据情况从 old region 候选回收集中选出，一部分为必须回收的region， 一部分为可选回收的 region。

这里是说的情况是指根据预测模型，当前 cset 回收的耗时是否超过设置的最大停顿时间。

> -XX:MaxGCPauseMillis=200 设置默认最大停顿时间。


```cpp
void G1YoungCollector::calculate_collection_set(G1EvacInfo* evacuation_info, double target_pause_time_ms) {
  allocator()->release_mutator_alloc_regions();

  collection_set()->finalize_initial_collection_set(target_pause_time_ms, survivor_regions());

  concurrent_mark()->verify_no_collection_set_oops(); //本节不涉及

  if (G1HeapRegionPrinter::is_active()) { //打印 cset
    collection_set()->iterate(&cl);
    collection_set()->iterate_optional(&cl);
  }
}
```

`allocator()->release_mutator_alloc_regions()` 将正在分配使用中的 region 添加到 cset。
最终使用 `collection_set()->add_eden_region(alloc_region)` 添加到 cset。

```cpp
void G1Allocator::release_mutator_alloc_regions() {
  for (uint i = 0; i < _num_alloc_regions; i++) { 
    mutator_alloc_region(i)->release(); }
}

//MutatorAllocRegion::release()->G1AllocRegion::release()
//->G1AllocRegion::retire->G1AllocRegion::retire_internal
//->MutatorAllocRegion::retire_region->

void G1CollectedHeap::retire_mutator_alloc_region(G1HeapRegion* alloc_region,
                                                  size_t allocated_bytes) {
  collection_set()->add_eden_region(alloc_region); //加入到 cset
  increase_used(allocated_bytes);
  _eden.add_used_bytes(allocated_bytes);
}
```

`mutator_alloc_region` 指的是正在分配使用中的 region，如果没有开启 `NUMA` 优化，`_num_alloc_regions` 的值为 1。


### Young Region

Young region  分成 Eden region 和 Survivor  region ，分别在不同的时候实际加入到 cset，前者是在内存分配时，当 region 剩余内存不够就会加入到 cset，后者是在 GC 结尾时加入到 cset。

#### Eden Region

先看 Eden region，当分配新的 region 时，会调用 `retire` 方法将当前 region 加入到 cset 中。最终逻辑和前文一致。

```cpp
inline HeapWord* G1Allocator::attempt_allocation_locked(size_t word_size) {
  uint node_index = current_node_index();
  return  mutator_alloc_region(node_index)->attempt_allocation_locked(word_size);
}

//G1AllocRegion::attempt_allocation_locked->attempt_allocation_using_new_region
// -> retire -> retire_internal -> MutatorAllocRegion::retire_region
//-> G1CollectedHeap::retire_mutator_alloc_region
inline HeapWord* G1AllocRegion::attempt_allocation_using_new_region(size_t min_word_size, size_t desired_word_size, size_t* actual_word_size) {
  retire(true /* fill_up */);
  //omit
}
```

> 为什么释放正在使用的 region 时使用 retire(false)，而这里使用的是 retire(true) ?
>或许前者填充成本高，而后者高？

#### Survivor Region

从下面的代码可以看到 Survivor region 是在 GC 收尾阶段加入的。

```cpp
//post_evacuate_collection_set->start_new_collection_set
//->transfer_survivors_to_cset->add_survivor_regions

void G1Policy::transfer_survivors_to_cset(const G1SurvivorRegions* survivors) {
  start_adding_survivor_regions();

  for (GrowableArrayIterator<G1HeapRegion*> it = survivors->regions()->begin(); it != survivors->regions()->end(); ++it) {
    G1HeapRegion* curr = *it;
    _collection_set->add_survivor_regions(curr);
  }
  stop_adding_survivor_regions();
}
```

在对象复制（也就是 evacute ）阶段，`G1CollectedHeap` 会将所有的 survivor region 维护在 `__survivor` 字段中。

```cpp
HeapWord* G1Allocator::par_allocate_during_gc(G1HeapRegionAttr dest, size_t min_word_size, size_t desired_word_size, size_t* actual_word_size, uint node_index) {
  switch (dest.type()) {
    case G1HeapRegionAttr::Young:
      return survivor_attempt_allocation(min_word_size, desired_word_size, actual_word_size, node_index);
    case G1HeapRegionAttr::Old:
      return old_attempt_allocation(min_word_size, desired_word_size, actual_word_size);
      //omit
  }
}
//survivor_attempt_allocation-> attempt_allocation_using_new_region
//-> attempt_allocation_using_new_region -> retire
//->G1AllocRegion::retire_internal->G1GCAllocRegion::retire_region
//->G1CollectedHeap::retire_gc_alloc_region
void G1CollectedHeap::retire_gc_alloc_region(G1HeapRegion* alloc_region, size_t allocated_bytes, G1HeapRegionAttr dest) {
  _bytes_used_during_gc += allocated_bytes;
  if (dest.is_old()) { old_set_add(alloc_region); } 
  else {
    _survivor.add_used_bytes(allocated_bytes); //将 region 加入到 _survivor
  }
  // 并发标记会使用到
  if (during_im && allocated_bytes > 0) { _cm->add_root_region(alloc_region); }
}
```

方法 `release_gc_alloc_regions` 会将最后一个使用的 survivor region 加入到 `__survivor`。

```cpp
//post_evacuate_collection_set-> release_gc_alloc_regions
```



### Old Region

cset 中的 old region 来自两部分，一部分是并发标记，一部分来自上次遗留下来的，主要是上次回收时被 pinned 的 old region。

```cpp
// All old gen collection set candidate regions.
G1CollectionSetCandidates _candidates;

class G1CollectionSetCandidates : public CHeapObj<mtGC> {
  G1CollectionCandidateList _marking_regions;  // Set of regions selected by concurrent marking.
  G1CollectionCandidateList _retained_regions; // Set of regions selected from evacuation failed regions.
}
```

在 `finalize_old_part` 方法中使用 `select_candidates_from_marking` 和 `select_candidates_from_retained` 按照条件分成四部分：

1. initial_old_regions 选定为回收集。
2. _optional_old_regions 作为可选回收集，收集此部分收益小，如果收集完 initial_old_regions 还有剩余时间就会收集这部分。
3. pinned_marking_regions 被 pinned region 等待下次回收。
4. pinned_retained_regions 本次还是 pinned region 则移除回收集。

```cpp
void G1CollectionSet::finalize_old_part(double time_remaining_ms) {
    G1CollectionCandidateRegionList initial_old_regions;
    G1CollectionCandidateRegionList pinned_marking_regions;
    G1CollectionCandidateRegionList pinned_retained_regions;

    if (collector_state()->in_mixed_phase()) {
      time_remaining_ms = select_candidates_from_marking(time_remaining_ms, &initial_old_regions, &pinned_marking_regions); } else { }

    select_candidates_from_retained(time_remaining_ms, &initial_old_regions, &pinned_retained_regions)
```

根据代码可以知道，只有在 Young GC（Mixed） 情况下，_marking_regions 才会被回收，而 _retained_regions 无论何种 Young GC 都会被回收。

下面的代码将 initial_old_regions 加入到回收集。

```cpp
//->pre_evacuate_collection_set -> finalize_initial_collection_set 
//->finalize_old_part-> move_candidates_to_collection_set -> add_old_region

// Move initially selected old regions to collection set directly.
move_candidates_to_collection_set(&initial_old_regions);
```

## 并发标记准备

此部分讲解并发标记时再论述。

```cpp
if (collector_state()->in_concurrent_start_gc()) {
    concurrent_mark()->pre_concurrent_start(_gc_cause);
}
```

## 软引用处理

软引用、若引用、虚引用处理，后续再论述。读者可参考 [以 ZGC 为例，谈一谈 JVM 是如何实现 Reference 语义的](https://mp.weixin.qq.com/s?__biz=Mzg2MzU3Mjc3Ng==&mid=2247489586&idx=1&sn=4306549c480f668458ab4df0d4b2ea47&chksm=ce77de75f9005763016605e0d268e1a4393a83bfe2a281c915bbf55de99d25cda529195c2843&scene=178&cur_album_id=2291913023118213124#rd)

```cpp
ref_processor_stw()->start_discovery(false /* always_clear */);
```

## 其他准备

初始化跟踪  Evacuation 失败时的数据结构。

```cpp
_evac_failure_regions.pre_collection(_g1h->max_reserved_regions());
```

初始化 GC Alloc Region

GC Alloc Region 是在 GC 回收过程中转移对象时使用的，即 survicor region 和用于对象晋升的 old region。

```cpp
// Initialize the GC alloc regions.
allocator()->init_gc_alloc_regions(evacuation_info);
```

1. 为遍历 heap root 准备数据结构
2. 重置 cardset 迭代器，为遍历年轻代记忆做准备。

```cpp
rem_set()->prepare_for_scan_heap_roots();
_g1h->prepare_group_cardsets_for_scan();
```

## G1PrepareEvacuationTask

`G1PrepareEvacuationTask` 迭代所有 region 对象，并设置相关属性，统计大对象数量。

```cpp
void work(uint worker_id) {
  G1PrepareRegionsClosure cl(_g1h, this);
  _g1h->heap_region_par_iterate_from_worker_offset(&cl, &_claimer, worker_id);
}
```

`heap_region_par_iterate_from_worker_offset` 负责将 region 分配给工作线程。

执行具体操作的是 `G1PrepareRegionsClosure::do_heap_region`，不在赘述。

## 总结

本文介绍了 Young GC 准备阶段的代码，重点介绍了线程缓冲区刷新、回收集的构建，以及其他的准备工作，后面的文章开始介绍 gc root 扫描。









