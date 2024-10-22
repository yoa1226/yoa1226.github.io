---
layout: post
title:  "G1 Young GC 之复制阶段"
date:   2024-08-30 11:00:00 +0200
tags: [GC, G1]
---

上文介绍了 G1 遍历 GC root 的源码，已经知道 G1 将与 GC root 对象直接关联的对象加入到任务队列中，复制对象也叫 evacuate object 主要是任务就是处理任务队列，直至队列为空为止。


## 任务队列

任务队列在垃圾收集过程中扮演着非常重要的角色，首先看看任务队列如何被初始化和内部结构。

从下面的代码可以看出队列集合的大小和 GC 线程大小一致。

```cpp
G1CollectedHeap::G1CollectedHeap() :{
  uint n_queues = ParallelGCThreads;
  _task_queues = new G1ScannerTasksQueueSet(n_queues);
  for (uint i = 0; i < n_queues; i++) {
    G1ScannerTasksQueue* q = new G1ScannerTasksQueue();
    _task_queues->register_queue(i, q);
  }
}
```

`OverflowTaskQueue` 分成两部分：

`taskqueue_t` 是一个队列，队列大小由 `TASKQUEUE_SIZE` 指定，底层 `_elems` 是一个数组。
它是一个双端出队队列，`pop_local` 是队列持有者线程获取任务，`pop_global` 是其他线程获取任务，前者是从入队的位置出队。

```cpp
template<class E, MemTag MT, unsigned int N = TASKQUEUE_SIZE>
class OverflowTaskQueue: public GenericTaskQueue<E, MT, N>
{
  typedef Stack<E, MT>               overflow_t;
  typedef GenericTaskQueue<E, MT, N> taskqueue_t;
  // Element array.
  E* _elems;
  inline bool pop_local(E& t, uint threshold = 0);
  PopResult pop_global(E& t);
}
```

<image src="/assets/gc-ygc-evacuate/gc-ygc-evacuate-overflow_t.png "/>

`overflow_t` 是一个栈，其内部是一个链表连接的数组，大致结构如上图。

在 `G1ParScanThreadState` 初始化的实际被绑定到线程上。

```cpp
G1ParScanThreadState::G1ParScanThreadState(...):
  _task_queue(g1h->task_queue(worker_id)),
{ }
```

## 本地任务处理

`pss->trim_queue()` 负责处理线程本地任务队列。

```cpp
class G1EvacuateRegionsBaseTask : public WorkerTask {
    evacuate_live_objects(.....){
    G1ParEvacuateFollowersClosure cl(........);
    cl.do_void();
    } }

class G1ParEvacuateFollowersClosure : public VoidClosure {
    void do_void() {
    G1ParScanThreadState* const pss = par_scan_state();
    pss->trim_queue();
    do {
      pss->steal_and_trim_queue(queues());
    } while (!offer_termination());
  }
}
```

`trim_queue_to_threshold` 首先将溢出栈里面的任务加入到队列中，避免溢出栈过长，增加开销，而且队列中的任务是可以并行处理的。加入失败时直接处理任务。

`pop_local` 方法从队列中取出任务直到队列为空。

```cpp
//->pss->trim_queue() -> trim_queue_to_threshold(0)
void G1ParScanThreadState::trim_queue_to_threshold(uint threshold) {
  ScannerTask task;
  do {
    while (_task_queue->pop_overflow(task)) {
      if (!_task_queue->try_push_to_taskqueue(task)) {
        dispatch_task(task);
      }
    }
    while (_task_queue->pop_local(task, threshold)) {
      dispatch_task(task);
    }
  } while (!_task_queue->overflow_empty());
}
```

`G1ParScanThreadState::do_oop_evac` 处理逻辑与 [G1ParCopyClosure](https://yoa1226.github.io/2024/08/25/g1-gc-young-gc-root-scan.html#g1parcopyclosure) 类似不在赘述。

```cpp
//-> G1ParScanThreadState::dispatch_task 
void G1ParScanThreadState::do_oop_evac(T* p) {
  oop obj = RawAccess<IS_NOT_NULL>::oop_load(p);

  const G1HeapRegionAttr region_attr = _g1h->region_attr(obj);
  if (!region_attr.is_in_cset()) { return; }

  markWord m = obj->mark();
  if (m.is_forwarded()) {
    obj = m.forwardee();
  } else {
    obj = do_copy_to_survivor_space(region_attr, obj, m);
  }
  RawAccess<IS_NOT_NULL>::oop_store(p, obj);

  write_ref_field_post(p, obj);
}
```

## 全局任务处理

`steal_and_trim_queue` 负责从其他队列窃取任务并且执行，当本地任务不为空时，先清空本地任务。

`_n` 是队列的数量，`num_retries` 表示尝试次数是队列数量的两倍。

```cpp
void G1ParScanThreadState::steal_and_trim_queue(G1ScannerTasksQueueSet* task_queues) {
  ScannerTask stolen_task;
  while (task_queues->steal(_worker_id, stolen_task)) {
    dispatch_task(stolen_task);
    // Processing stolen task may have added tasks to our queue.
    trim_queue(); //处理其他任务队列的过程中，会将后续任务加入到本地队列中。
  }
}

bool GenericTaskQueueSet<T, MT>::steal(uint queue_num, E& t) {
  uint const num_retries = 2 * _n;
  for (uint i = 0; i < num_retries; i++) {
    PopResult sr = steal_best_of_2(queue_num, t);
    if (sr == PopResult::Success) {
      return true;
    } else if (sr == PopResult::Contended) {
    } else {
    }
  }
  return false;
}
```

`steal_best_of_2` 复杂随机选取两个队列，从 `size` 较大队列获取获取任务。

其中 `k1`代表的队列要么随机获取，要么是获取的队列，`k2` 代表的队列随机获取。

```cpp
steal_best_of_2(uint queue_num, E& t){
  uint k1 = queue_num; 

  if (local_queue->is_last_stolen_queue_id_valid()) {
    k1 = local_queue->last_stolen_queue_id();
    assert(k1 != queue_num, "Should not be the same");
  } else {
    while (k1 == queue_num) {
    k1 = local_queue->next_random_queue_id() % _n;
   }
  }

  uint k2 = queue_num;
  while (k2 == queue_num || k2 == k1) {
    k2 = local_queue->next_random_queue_id() % _n;
  }

  // Sample both and try the larger.
  uint sz1 = queue(k1)->size();
  uint sz2 = queue(k2)->size();

  uint sel_k = 0;
  PopResult suc = PopResult::Empty;
  if (sz2 > sz1) {
    sel_k = k2;
    suc = queue(k2)->pop_global(t);
  } else if (sz1 > 0) {
    sel_k = k1;
    suc = queue(k1)->pop_global(t);
  }
  if (suc == PopResult::Success) { local_queue->set_last_stolen_queue_id(sel_k);
  } else { local_queue->invalidate_last_stolen_queue_id(); }
  return suc;
}
```

直到所有队列中的任务都处理完成，那么对象的遍历也就完成了。

## 复制失败处理

`forward_to_atomic` 让对象自转发，当前线程获取拥有权。

`_evac_failure_regions->record` 记录失败的 region， 并统计失败的次数。

`_g1h->mark_evac_failure_object` 在 bitmap 中标记，便于后面处理。

` _preserved_marks->push_if_necessary` 表示如果当前对象处于锁状态或者 hash 值已经计算则需要将此时的 `markword` 记录下来。后面需要回复。

`old->oop_iterate_backwards` 遍历对象属性。

```cpp
oop G1ParScanThreadState::handle_evacuation_failure_par(oop old, markWord m, size_t word_sz, bool cause_pinned) {

  oop forward_ptr = old->forward_to_atomic(old, m, memory_order_relaxed);
  if (forward_ptr == nullptr) {
    // Forward-to-self succeeded. We are the "owner" of the object.
    G1HeapRegion* r = _g1h->heap_region_containing(old);

    if (_evac_failure_regions->record(_worker_id, r->hrm_index(), cause_pinned)) {
      G1HeapRegionPrinter::evac_failure(r);
    }

    // Mark the failing object in the marking bitmap and later use the bitmap to handle
    // evacuation failure recovery.
    _g1h->mark_evac_failure_object(_worker_id, old, word_sz);

    _preserved_marks->push_if_necessary(old, m);

    ContinuationGCSupport::transform_stack_chunk(old);

    _evacuation_failed_info.register_copy_failure(word_sz);

    G1SkipCardEnqueueSetter x(&_scanner, false /* skip_card_enqueue */);
    old->oop_iterate_backwards(&_scanner);

    return old;
  }else {} //omit
}
```

## 总结

本文介绍了对象复制的逻辑，着重说明了线程本地队列和全局队列任务的处理，最后简单描述了对于对象复制失败的处理。




















