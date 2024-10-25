---
layout: post
title:  "G1 并发标记"
date:   2024-09-15 11:00:00 +0200
tags: [GC, G1]
---

前文介绍了并发标记的 root region scan 阶段，此阶段和 Young GC 中的 gc root scan 共同标记与 GC root 直接关联的对象，这些对象在 mark bitmap 对应的位置标记为存活。接下来并发标记阶段就是以这些对象为起点，开始标记与它们关联的对象，在此之前首先介绍此过程使用中到的数据结构。

## G1CMTask

`G1CMTask` 是工作线程的任务封装，并发标记执行的具体逻辑。

`_finger` 是当前工作线程遍历 region 的位置，它范围是 bottom <= _finger < tams。

当前工作的线程会将遍历到的对象加入 `_task_queue` 等待处理。

```cpp
class G1CMTask {
   // Local finger of this task, null if we're not scanning a region
   HeapWord*                   _finger;

  // the task queue of this task
  G1CMTaskQueue*              _task_queue;
}
```


## G1ConcurrentMark

`G1ConcurrentMark` 是并发标记整个流程的封装。

`_finger` 是全局的 `_finger` ，表示所有线程中遍历最靠前的位置，范围是 `heap.start() <= _finger < heap.end()`

当线程本地队列 `_task_queue` 耗尽时会将任务加入到 `_global_mark_stack`。


```cpp
class G1ConcurrentMark {
  // For grey objects
  G1CMMarkStack           _global_mark_stack; // Grey objects behind global finger
  HeapWord* volatile      _finger;            // The global finger, region aligned,
                                              // always pointing to the end of the
                                              // last claimed regio
}
```

## 三色标记算法

在标记阶段 G1 采用三色标记算法([Tri-color marking](https://en.wikipedia.org/wiki/Tracing_garbage_collection#Tri-color_marking))，从 GC root 开始遍历对象。

如图，开始时只有与 gc root 直接关联的对象是灰色的，随着并发标记的进行，所有与 GC root 直接关联或者间接关联的对象都会变成黑色。

<image src="/assets/conc-mark/conc-mark-tri-color.png" width="80%"/>

注意这里说的对象染色并不是标记对象的某个属性为黑色或者灰色，以 G1 为例：

- 并发标记前：在 Young GC 阶段，将与之 gc root 关联的对象标记在 mark_bitmap 中，同理在 root region scan 阶段也会标记与之直接关联的对象。当对象已经在 mark_bitmap 标记，由于对象的引用还未被处理此时称对象被标记为灰色。此时除此之外的对象全部为白色对象，就是还没有遍历到的对象。

- 并发标记中：G1 从 heap 开始的位置起开始遍历，当遍历到的对象已经被标记，则会遍历它的属性引用并加入到 `_task_queue` 中，称在队列中的对象标记为黑色。
   当处理任务队列时，对象会被拿出来在 mark_bitmap 上标记，此时对象被标记灰色，此后遍历对象引用属性加入到队列中，此时对象为黑色。

- 并发标记后活者的对象都已经在 `mark_bitmap` 标记，并且对象的所有应用都已经被遍历，为黑色对象，死对象未被标记，为白色对象。

由此可知，遍历开始时只有灰色对象和白色对象，遍历结束后只有黑色和白色对象。


## 并发标记

并发标记在代码的名称叫做 `mark from roots`，主要逻辑框架封装在 G1CMTask::do_marking_step` 方法中。

```cpp
G1ConcPhaseTimer p(_cm, "Concurrent Mark From Roots");

//-> subphase_mark_from_roots ->G1ConcurrentMark::mark_from_roots
class G1CMConcurrentMarkingTask : public WorkerTask {
  G1ConcurrentMark*     _cm;
  void work(uint worker_id) {
    {
      G1CMTask* task = _cm->task(worker_id);
      if (!_cm->has_aborted()) {
        do {
          task->do_marking_step(G1ConcMarkStepDurationMillis, true , false);
        } while (!_cm->has_aborted() && task->has_aborted());
      } } }
}
```

核心模块是一个 `do-while` 循环。

```cpp
do{
  //1. process region between botton and tams
  //2. process task queue

  while (!has_aborted() && _curr_region == nullptr && !_cm->out_of_regions()){
    G1HeapRegion* claimed_region = _cm->claim_region(_worker_id);//get region to scan
  }
}while(_curr_region != nullptr && !has_aborted())
```

并发标记的代码逻辑很多，下面分功能模块介绍。

### drain satb buffers

#### SATB

G1 使用 SATB 来保证 GC 线程与用户线程并发执行的正确性，而 SATB 是写前屏障（write-pre barrier）来实现。

SATB 机制将在并发标记过程中 “消失的对象” 全部标记为黑色，然后以此为根，开始扫描。它能确保对象不会被误删除（最终标记为黑色），但也会导致真正应该消失的对象在本轮标记中存活，需要等待下一轮并发标记才能被回收。参考《深入理解 Java 虚拟机》

```cpp
inline void ModRefBarrierSet::AccessBarrier<decorators, BarrierSetT>::
oop_store_in_heap(T* addr, oop value) {
  BarrierSetT *bs = barrier_set_cast<BarrierSetT>(barrier_set());
  bs->template write_ref_field_pre<decorators>(addr); //写前屏障
  Raw::oop_store(addr, value);
  bs->template write_ref_field_post<decorators>(addr);
}
```

写前屏障会首先判断 SATB 是否开启，开启状态下会将对象指针加入到 `SATBMarkQueue` 队列中。

```cpp
//write_ref_field_pre->enqueue
inline void G1BarrierSet::enqueue(T* dst) {
  G1SATBMarkQueueSet& queue_set = G1BarrierSet::satb_mark_queue_set();
  if (!queue_set.is_active()) return;

  T heap_oop = RawAccess<MO_RELAXED>::oop_load(dst);
  if (!CompressedOops::is_null(heap_oop)) {
    SATBMarkQueue& queue = G1ThreadLocalData::satb_mark_queue(Thread::current());
    queue_set.enqueue_known_active(queue, CompressedOops::decode_not_null(heap_oop));
  }
}
```

在并发标记开始的时候，会设置 `SATBMarkQueueSet` 的状态，并为每个线程设置 `queue` 的容量。

```cpp
//start_concurrent_cycle->set_active_all_threads
void SATBMarkQueueSet::set_active_all_threads(bool active, bool expected_active) {
  _all_active = active;

  Threads::threads_do(&closure);
}

class SetThreadActiveClosure : public ThreadClosure {
  SATBMarkQueueSet* _qset; bool _active;
public:
  SetThreadActiveClosure(SATBMarkQueueSet* qset, bool active) :
    _qset(qset), _active(active) {}
  virtual void do_thread(Thread* t) {
    SATBMarkQueue& queue = _qset->satb_queue_for_thread(t);
    if (_active) {
      assert(queue.is_empty(), "queues should be empty when activated");
    } else {
      queue.set_index(queue.current_capacity());
    }
    queue.set_active(_active);
  }
}
```

#### drain satb

`_task->make_reference_grey` 在 mark_map 中标记当前对象，标记成功则将对象加入到队列中，后续会遍历对象所有引用。

标记失败的情况：

1. 对象地址大于 tams ，即对象不在本次需要遍历的范围内，不在此范围的对象是并发过程中用户线程产生的新对象，这些对象都是活对象。
2. 对象已经被标记，不需要再次标记，保证所有任务都能够完成。

```cpp
//->drain_satb_buffers -> SATBMarkQueueSet::apply_closure_to_completed_buffer
class G1CMSATBBufferClosure : public SATBBufferClosure {
    virtual void do_buffer(void** buffer, size_t size) {
    for (size_t i = 0; i < size; ++i) {
      do_entry(buffer[i]);
    } }

  void do_entry(void* entry) const {
    _task->increment_refs_reached();
    oop const obj = cast_to_oop(entry);
    _task->make_reference_grey(obj);
  }
}
//make_reference_grey -> push -> _task_queue->push(task_entry)
inline bool G1CMTask::make_reference_grey(oop obj) {
  if (!_cm->mark_in_bitmap(_worker_id, obj)) {
    return false;
  }
  if (is_below_finger(obj, global_finger)) {
    push(entry);
  }
}
```

如果队列已满，则移动部分本地队列任务到全局栈中，然后再将当前任务加入到本地队列中。

```cpp
inline void G1CMTask::push(G1TaskQueueEntry task_entry) {
  if (!_task_queue->push(task_entry)) {
    move_entries_to_global_stack();
    bool success = _task_queue->push(task_entry);
  }
}
```

### mark humongous region

对于数组对象和普通对象有不同的处理，本节只看普通对象。

```cpp
if(_curr_region->is_humongous() && mr.start() == _curr_region->bottom()) {
      if (_mark_bitmap->is_marked(mr.start())) {
        // The object is marked - apply the closure
        bitmap_closure.do_addr(mr.start());
      }
}

bool G1CMBitMapClosure::do_addr(HeapWord* const addr) {
  // We move that task's local finger along.
  _task->move_finger_to(addr);
  _task->scan_task_entry(G1TaskQueueEntry::from_oop(cast_to_oop(addr)));
  return !_task->has_aborted();
}

//scan_task_entry -> process_grey_task_entry
_words_scanned += obj->oop_iterate_size(_cm_oop_closure);; //源码中就多了一个分号
```

`_cm_oop_closure` 在 `do_marking_step` 开始执行的时候被设置，`make_reference_grey` 处理方式上文已经提到过。


```cpp
G1CMOopClosure cm_oop_closure(_g1h, this);
set_cm_oop_closure(&cm_oop_closure);

inline void G1CMOopClosure::do_oop_work(T* p) {
  _task->deal_with_reference(p);
}
// G1CMTask::deal_with_reference -> make_reference_grey
```

如果全局栈耗尽，则会重新启动流程，在 `do_marking_step` 方法中首先执行清空队列和栈的逻辑。

```cpp
for (uint iter = 1; true; ++iter) {
  // Check if we need to restart the marking loop.
  if (!mark_loop_needs_restart()) break;
  log_info(gc, marking)("Concurrent Mark Restart for Mark Stack Overflow (iteration #%u)", iter);
}

// ...then partially drain the local queue and the global stack
drain_local_queue(true);
drain_global_stack(true);
```

> If it overflows, then the marking phase should restart and iterate over the bitmap to identify grey objects.


### mark normal region

对于普通 region，则遍历 region 区域，找到其中被标记的对象，然后逐个遍历对象的所有引用，其次标记这些引用，最后将这些引用加入到队列中。`make_reference_grey` 处理方式上文已经提到过。

```cpp
if (_mark_bitmap->iterate(&bitmap_closure, mr))

inline bool G1CMBitMap::iterate(G1CMBitMapClosure* cl, MemRegion mr) {
  BitMap::idx_t const end_offset = addr_to_offset(mr.end());
  BitMap::idx_t offset = _bm.find_first_set_bit(addr_to_offset(mr.start()), end_offset);

  while (offset < end_offset) {
    HeapWord* const addr = offset_to_addr(offset);
    if (!cl->do_addr(addr)) { return false; }
    size_t const obj_size = cast_to_oop(addr)->size();
    offset = _bm.find_first_set_bit(offset + (obj_size >> _shifter), end_offset);
  }
  return true;
}
```

### drain local queue

依次从本地队列中取出任务处理，后续流程前文已经介绍过，不在赘述。

参数 `partially` 表示处理部分任务还是全部任务，留下部分是为了让其他工作线程来窃取。

```cpp
void G1CMTask::drain_local_queue(bool partially) {
    uint target_size;
    if (partially) { target_size = GCDrainStackTargetSize; } 
    else { target_size = 0; } 

    G1TaskQueueEntry entry;
    bool ret = _task_queue->pop_local(entry);
    while (ret) {
      scan_task_entry(entry);
      if (_task_queue->size() <= target_size || has_aborted()) {
        ret = false;
      } else {
        ret = _task_queue->pop_local(entry);
     } }
}

//scan_task_entry->process_grey_task_entry
```

### drain global stack

`get_entries_from_global_stack` 方法批量从全局栈中获取任务，然后将任务加入到本地队列中，然后 `drain_local_queue` 清空本地队列的任务。

```cpp
void G1CMTask::drain_global_stack(bool partially) {
  while (!has_aborted() && _cm->mark_stack_size() > target_size) {
      if (get_entries_from_global_stack()) {
        drain_local_queue(partially);
      } }
}

bool G1CMTask::get_entries_from_global_stack() {
  G1TaskQueueEntry buffer[G1CMMarkStack::EntriesPerChunk];
  if (!_cm->mark_stack_pop(buffer)) { return false; }
  // We did actually pop at least one entry.
  for (size_t i = 0; i < G1CMMarkStack::EntriesPerChunk; ++i) {
    G1TaskQueueEntry task_entry = buffer[i];
    if (task_entry.is_null()) { break; }
    bool success = _task_queue->push(task_entry);
  }
  return true;
}
```

### claim region

`_cm->claim_region` 为当前线程分配 region，然后 `setup_for_region` 初始化相关属性。

```cpp
while (!has_aborted() && _curr_region == nullptr && !_cm->out_of_regions()) {
  G1HeapRegion* claimed_region = _cm->claim_region(_worker_id);
  if (claimed_region != nullptr) {
    setup_for_region(claimed_region);
  }
}
```

### 窃取任务

窃取任务的逻辑和收集阶段类似，不在赘述。

```cpp
while (!has_aborted()) {
  G1TaskQueueEntry entry;
  if (_cm->try_stealing(_worker_id, entry)) {
    scan_task_entry(entry);
    drain_local_queue(false);
    drain_global_stack(false);
  } else {
    break;
  }
}
```

## 总结

本文从三色标记算法开始，讲解了 G1 遍历所有 region（bottom 到 tams） ，并且在 mark map 中标记对象的全过程。这块代码看似很多，不过主要逻辑还是很清楚，希望本文对读者有用。
























