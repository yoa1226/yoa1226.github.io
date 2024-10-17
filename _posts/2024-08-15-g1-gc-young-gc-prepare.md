---
layout: post
title:  "G1 Young GC 之准备阶段"
date:   2024-08-15 11:00:00 +0200
tags: [GC, G1]
---

前面两篇文章对 G1 的一些概念和重要组件做了部分介绍，从本文开始介绍 G1 的收集流程，首先分四篇文章介绍 G1 Young GC，而后介绍并发标记的流程。本文开始介绍 G1 Young GC 的准备阶段。

## G1YoungCollector

当内存分配失败时就会导致 Young GC，本小节不关注分配失败的流程，只是列出调用链，后续会出文章介绍。

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


##

## 回收集












