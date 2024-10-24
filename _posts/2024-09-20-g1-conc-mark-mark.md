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

在标记阶段 G1 采用三色标记算法([Tri-color marking](https://en.wikipedia.org/wiki/Tracing_garbage_collection#Tri-color_marking))，从 GC root 开始遍历对象，如图开始时只有与 gc root 直接关联的对象是黑色的，随着并发标记的进行，所有与 GC root 直接关联或者间接关联的对象都会变成黑色。

<image src="/assets/conc-mark/conc-mark-tri-color.png" width="80%"/>

注意这里说的对象染色并不是标记对象的某个属性为黑色或者灰色，以 G1 为例：

- 并发标记前：在 Young GC 阶段，将与之 gc root 关联的对象标记在 mark_bitmap 中，同理在 root region scan 阶段也会标记与之直接关联的对象。当对象已经在 mark_bitmap 标记，称对象被标记为黑色。此时除此之外的对象全部为白色对象，就是还没有遍历到的对象。

- 并发标记中：G1 从 heap 开始的位置起开始遍历，当遍历到的对象已经被标记，就会将它的属性引用加入到 `_task_queue` 中，称在队列中的对象标记为灰色。当处理任务队列时，对象会被拿出来在 mark_bitmap 上标记，此时对象被标记黑色，并且遍历对象引用属性加入到队列中。

- 并发标记后活者的对象都已经在 `mark_bitmap` 标记，为黑色对象，死对象未被标记，为白色对象。

由此可知，只有在标记过程中有灰色对象，标记之前和以后都只有黑色和白色对象，即标记之前和之后任务队列都为空。


## 并发标记

### todo

```cpp
//-> subphase_mark_from_roots
//->G1ConcurrentMark::mark_from_roots
class G1CMConcurrentMarkingTask : public WorkerTask {
  G1ConcurrentMark*     _cm;

public:
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
