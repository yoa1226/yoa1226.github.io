---
layout: post
title:  "G1 并发标记 root region scan"
date:   2024-09-15 11:00:00 +0200
tags: [GC, G1]
---

前几篇文章介绍了 G1 Young GC ，从本文开始介绍 G1 并发标记。并发标记是一个非常重要的阶段，它为 Young GC（Mixed）提供数据支持。并发标记过程中大部分时间并不会暂停用户线程，只有小部分时间会暂停用户线程。


## 触发并发标记

并发标记是根据一些数据条件，以及当时 GC 的类型来确定是否触发。

### IOHP

IOHP 全称 InitiatingHeapOccupancyPercent， 使用 `-XX:InitiatingHeapOccupancyPercent=45` 指定。

它的意思是，当所有老年代（包括 humongous region）region 总和超过全堆 reigon 总和的 45 % 时需要触发并发标记。

如果指定了 `-XX:+G1UseAdaptiveIHOP` , G1 会通过一定的算法自适应计算 IHOP 的值。

```cpp
G1IHOPControl* G1Policy::create_ihop_control(const G1OldGenAllocationTracker* old_gen_alloc_tracker, const G1Predictions* predictor) {
  if (G1UseAdaptiveIHOP) {
    return new G1AdaptiveIHOPControl(InitiatingHeapOccupancyPercent, old_gen_alloc_tracker, predictor, G1ReservePercent, G1HeapWastePercent);
  } else {
    return new G1StaticIHOPControl(InitiatingHeapOccupancyPercent, old_gen_alloc_tracker);
  }
}
```

### Young GC 结尾

在 Young GC 结尾，`record_young_collection_end` 方法会根据当前 GC 类型决定是否调用 `maybe_start_marking` 方法触发并发标记。

```cpp
G1YoungCollector::collect(){
  policy()->record_young_collection_end(_concurrent_operation_is_full_mark, evacuation_alloc_failed());
}

void G1Policy::record_young_collection_end(bool concurrent_operation_is_full_mark, bool allocation_failure) {
  if (G1GCPauseTypeHelper::is_concurrent_start_pause(this_pause)) {
    record_concurrent_mark_init_end();
  } else { maybe_start_marking(); }

  if (G1GCPauseTypeHelper::is_mixed_pause(this_pause)) {
    // This is a mixed GC. Here we decide whether to continue doing more
    // mixed GCs or not.
    if (!next_gc_should_be_mixed()) { maybe_start_marking(); }
  }
}
```
> 第一个判断当前 GC 如果不是并发标记的开始阶段，就调用 `maybe_start_marking` 方法。第二个判断是否多余？


当 `marking_request_bytes > marking_initiating_used_threshold` 成立，并且当前 GC 是 Young Ony GC，即只回收年轻代，调用 `collector_state()->set_initiate_conc_mark_if_possible(true)` 方法设置可能需要并发标记的标志。

```cpp

void G1Policy::maybe_start_marking() {
  if (need_to_start_conc_mark("end of GC")) {
    collector_state()->set_initiate_conc_mark_if_possible(true);
  } }

bool G1Policy::need_to_start_conc_mark(const char* source, size_t alloc_word_size) {
  if (about_to_start_mixed_phase()) { return false; }

  size_t marking_initiating_used_threshold = _ihop_control->get_conc_mark_start_threshold();

  size_t cur_used_bytes = _g1h->non_young_capacity_bytes();
  size_t alloc_byte_size = alloc_word_size * HeapWordSize;
  size_t marking_request_bytes = cur_used_bytes + alloc_byte_size;

  bool result = false;
  if (marking_request_bytes > marking_initiating_used_threshold) {
    result = collector_state()->in_young_only_phase();
  }
  return result; }
```

### 大对象分配

大对象分配的实际，按照条件设置可能需要并发标记的标志。

```cpp
HeapWord* G1CollectedHeap::attempt_allocation_humongous(size_t word_size) {
  if (policy()->need_to_start_conc_mark("concurrent humongous allocation",
                                        word_size)) {
    collect(GCCause::_g1_humongous_allocation);
  }
}
```

另外 Full GC 结尾时也会判断

```cpp
void G1Policy::record_full_collection_end() {
  collector_state()->set_initiate_conc_mark_if_possible(need_to_start_conc_mark("end of Full GC"));
}
```

### 确定触发并发标记

在 Young GC 开始时会准确判断是否需要进行并发标记。

```cpp
void G1CollectedHeap::do_collection_pause_at_safepoint_helper() {
  policy()->decide_on_concurrent_start_pause();
  collector.collect();

   if (should_start_concurrent_mark_operation) {
    verifier()->verify_bitmap_clear(true /* above_tams_only */);
    start_concurrent_cycle(collector.concurrent_operation_is_full_mark());
    ConcurrentGCBreakpoints::notify_idle_to_active();
  }
}
```

如果当前时 Young Only GC 则可以进行并发标记。

```cpp
void G1Policy::decide_on_concurrent_start_pause() {
  if (collector_state()->initiate_conc_mark_if_possible()) {
    if (!about_to_start_mixed_phase() && collector_state()->in_young_only_phase()) {
        initiate_conc_mark();
      }else if(
        //其他情况
        initiate_conc_mark();
      )
  }
}
```
如果本轮已经是并发标记的开始阶段，在结束时还需要判断。

```cpp
_concurrent_operation_is_full_mark = policy()->concurrent_operation_is_full_mark("Revise IHOP");

bool G1Policy::concurrent_operation_is_full_mark(const char* msg) {
  return collector_state()->in_concurrent_start_gc() &&
    ((_g1h->gc_cause() != GCCause::_g1_humongous_allocation) || need_to_start_conc_mark(msg));
}
```

最后在 `start_concurrent_cycle` 根据 `concurrent_operation_is_full_mark` 是否进行并发标记。

```cpp
void G1CollectedHeap::start_concurrent_cycle(bool concurrent_operation_is_full_mark) {
  if (concurrent_operation_is_full_mark) {
    _cm->post_concurrent_mark_start();
    _cm_thread->start_full_mark();
  } else {
    _cm->post_concurrent_undo_start();
    _cm_thread->start_undo_mark();
  }
  CGC_lock->notify();
}
```

小结：

- `_initiate_conc_mark_if_possible` 是在上一轮 Young GC 结束时判断是否需要设置。
- `_in_concurrent_start_gc` 是在当前 Young GC 开始时根据上面字段和其他条件设置。
- `concurrent_operation_is_full_mark` 是在当前 Young GC 结束时根据上面的字段和其他条件设置。

## Young GC 

Young GC 作为并发标记的第一个阶段，其中一些代码是为后续并发标记做准备的。

`G1PreConcurrentStartTask` 是准备并发标记需要的数据结构，后面使用时再详细说明。

```cpp
void G1YoungCollector::pre_evacuate_collection_set(G1EvacInfo* evacuation_info) {
    if (collector_state()->in_concurrent_start_gc()) {
    concurrent_mark()->pre_concurrent_start(_gc_cause);
  }
}

void G1ConcurrentMark::pre_concurrent_start(GCCause::Cause cause) {
  G1PreConcurrentStartTask cl(cause, this);
}

G1PreConcurrentStartTask::G1PreConcurrentStartTask(GCCause::Cause cause, G1ConcurrentMark* cm) :
  add_serial_task(new ResetMarkingStateTask(cm));
  add_parallel_task(new NoteStartOfMarkTask());{}
```

在 GC root 遍历时行标记对象。

```cpp
if (g1h->collector_state()->in_concurrent_start_gc()) {
  res = new G1ConcurrentStartMarkClosures<true>(g1h, pss);
}

template <bool should_mark_weak>
class G1ConcurrentStartMarkClosures : public G1EvacuationRootClosures {
  G1SharedClosures<true>             _strong;
}

void G1ParCopyClosure<barrier, should_mark>::do_oop_work(T* p) {
  if (should_mark) {
    mark_object(obj);
  }
}
```

对象复制阶段，当 survivor 或者 old 空间耗尽时，会加入到 `root_region` 中。

```cpp
void G1CollectedHeap::retire_gc_alloc_region(G1HeapRegion* alloc_region,
                                             size_t allocated_bytes,
                                             G1HeapRegionAttr dest) {
  bool const during_im = collector_state()->in_concurrent_start_gc();
  if (during_im && allocated_bytes > 0) {
    _cm->add_root_region(alloc_region);
  }
}
```

Young GC 结尾阶段将 cset 候选 region 加入到 `root_region`。

```cpp
void G1YoungCollector::post_evacuate_collection_set(.....) {
   if (collector_state()->in_concurrent_start_gc()) {
    enqueue_candidates_as_root_regions();
   }
}
```

## 并发标记启动

在 `post_concurrent_mark_start`  方法做一些准备，然后调用 ` CGC_lock->notify()` 唤醒 `G1ConcurrentMarkThread` 线程。

```cpp
//start_concurrent_cycle(collector.concurrent_operation_is_full_mark());

void G1CollectedHeap::start_concurrent_cycle(bool concurrent_operation_is_full_mark) {
  assert(!_cm_thread->in_progress(), "Can not start concurrent operation while in progress");

  MutexLocker x(CGC_lock, Mutex::_no_safepoint_check_flag);
  if (concurrent_operation_is_full_mark) {
    _cm->post_concurrent_mark_start();
    _cm_thread->start_full_mark();
  } else { /* YGC 完成之后，不需要并发提前退出 */ }
  CGC_lock->notify();
}

void G1ConcurrentMark::post_concurrent_mark_start() {

  SATBMarkQueueSet& satb_mq_set = G1BarrierSet::satb_mark_queue_set();
  satb_mq_set.set_active_all_threads(true, /* new active value */ false /* expected_active */);
  _root_regions.prepare_for_scan();
}

void G1ConcurrentMarkThread::run_service() {
  while (wait_for_next_cycle()) {
    concurrent_cycle_start();
    if (_state == FullMark) {
      concurrent_mark_cycle_do();
    } else { concurrent_undo_cycle_do(); }
    concurrent_cycle_end(_state == FullMark && !_cm->has_aborted());
  }
  _cm->root_regions()->cancel_scan();
}

bool G1ConcurrentMarkThread::wait_for_next_cycle() {
  MonitorLocker ml(CGC_lock, Mutex::_no_safepoint_check_flag);
  while (!in_progress() && !should_terminate()) {
    ml.wait();
  }
  return !should_terminate();
}
```

如上所示， `G1ConcurrentMarkThread` 阻塞在 `CGC_lock` 上。

下面代码是并发标记的整体逻辑，其中 `phase_scan_root_regions` 是本文接下来的内容，并且此阶段结束之前不能被打断。

```cpp
void G1ConcurrentMarkThread::concurrent_mark_cycle_do() {
  HandleMark hm(Thread::current());
  ResourceMark rm;

  // Phase 1: Scan root regions.
  if (phase_scan_root_regions()) return;

  // Phase 2: Actual mark loop.
  if (phase_mark_loop()) return;

  // Phase 3: Rebuild remembered sets and scrub dead objects.
  if (phase_rebuild_and_scrub()) return;

  // Phase 4: Wait for Cleanup.
  if (phase_delay_to_keep_mmu_before_cleanup()) return;

  // Phase 5: Cleanup pause
  if (phase_cleanup()) return;

  // Phase 6: Clear CLD claimed marks.
  if (phase_clear_cld_claimed_marks()) return;

  // Phase 7: Clear bitmap for next mark.
  phase_clear_bitmap_for_next_mark();
}
```

> G1 makes sure that this concurrent phase has been completed before the next GC, at worst delaying start of the next garbage collection until completed. If G1 did not do that, there would be problems with objects not surviving that next GC wrt to SATB.

Young GC 等待 root region scan 完成。

```cpp
void G1YoungCollector::collect() {
    wait_for_root_region_scanning();
}
```

## TAMS

在 region 中有三个字段：
- _bottom 指向 region 起始位置。
- _end 指向 region 结束为止。
- _top 指向目前待分配的位置。

```cpp
class G1HeapRegion : public CHeapObj<mtGC> {
  HeapWord* const _bottom;
  HeapWord* const _end;
  HeapWord* volatile _top;
}
```

`TAMS` 全称 `top at mark start`， 记录了 top 在并发标记前的位置。 TAMS 被记录并发满足三个条件：

1. 是 old region 或者 humongous region。
2. region 不在 cset中。
3. 2. region 不在 cset 候选中。

对于在这里没有显示设置 TAMS 位置的 reigon，其 TAMS 值等于其 bottom。

```cpp
//pre_evacuate_collection_set->G1ConcurrentMark::pre_concurrent_start
//G1PreConcurrentStartTask cl(cause, this);
void G1PreConcurrentStartTask::NoteStartOfMarkTask::do_work(uint worker_id) {
  NoteStartOfMarkHRClosure start_cl;
  G1CollectedHeap::heap()->heap_region_par_iterate_from_worker_offset(&start_cl, &_claimer, worker_id);
}

class NoteStartOfMarkHRClosure : public G1HeapRegionClosure {
  G1ConcurrentMark* _cm;

  bool do_heap_region(G1HeapRegion* r) override {
    if (r->is_old_or_humongous() && !r->is_collection_set_candidate() && !r->in_collection_set()) {
      _cm->update_top_at_mark_start(r);
    }
    return false; } 
}

inline void G1ConcurrentMark::update_top_at_mark_start(G1HeapRegion* r) {
  uint const region = r->hrm_index();
  _top_at_mark_starts[region] = r->top();
}

```

如下图，有三个 region，属性在 young GC 收集前和收集后的变化。

<image src="/assets/conc-root-region-scan/conc-root-region-scan-tams.png"/>

tams 与 top 之间的区域是本次 young gc 存活下来的对象，这些对象明确为活对象，这些区域会作为 root region。

```cpp
void G1ConcurrentMark::add_root_region(G1HeapRegion* r) {
  root_regions()->add(top_at_mark_start(r), r->top());
}
void G1CMRootMemRegions::add(HeapWord* start, HeapWord* end) {
  size_t idx = Atomic::fetch_then_add(&_num_root_regions, 1u);
  _root_regions[idx].set_start(start);
  _root_regions[idx].set_end(end);
}
```

## root region scan




















