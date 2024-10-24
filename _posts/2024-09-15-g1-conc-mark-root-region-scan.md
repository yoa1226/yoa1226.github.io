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
2. 并且 region 不在 cset中。
3. 并且 region 不在 cset 候选中。

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

root region scan 的最终遍历是在 `G1ConcurrentMark::scan_root_region(...)` 完成的。

`region->start()` 是 TAMS 的位置，`region->end()` 是 top 所在位置。

`root_regions->claim_next()` 负责对线程进行同步。
```cpp
void G1ConcurrentMark::scan_root_regions() {
    G1CMRootRegionScanTask task(this);
    _concurrent_workers->run_task(&task, num_workers);
}

class G1CMRootRegionScanTask : public WorkerTask {
  void work(uint worker_id) {
    G1CMRootMemRegions* root_regions = _cm->root_regions();
    const MemRegion* region = root_regions->claim_next();
    while (region != nullptr) {
      _cm->scan_root_region(region, worker_id);
      region = root_regions->claim_next();
    } }
}

void G1ConcurrentMark::scan_root_region(const MemRegion* region, uint worker_id) {
  G1RootRegionScanClosure cl(_g1h, this, worker_id);
  HeapWord* curr = region->start();
  const HeapWord* end = region->end();
  while (curr < end) {
    Prefetch::read(curr, interval);
    oop obj = cast_to_oop(curr);
    size_t size = obj->oop_iterate_size(&cl);
    curr += size;
  }
}
```

注意上面的代码并没有对 `obj->oop_iterate_size(&cl)` 中 `obj` 对象本身进行标记，如果对 obj 进行标记会带来一定复杂性。

1. obj 可能是 survivor 区对象，也可能是 cset 候选区对象，由于在 root region scan 结束之后，可能进行 young gc，那么这些对象可能会被回收。那么在后续并发标记时会造成对象缺失。这也是 young GC 为什么要等待 root region scan 完成的原因。

2. survivor 区回收是在 young GC，不需要被标记就能被回收，cset 候选区已经被标记过了，无需重复标记。

```cpp
inline bool G1CMBitMap::iterate(G1CMBitMapClosure* cl, MemRegion mr) {
  BitMap::idx_t const end_offset = addr_to_offset(mr.end());
  BitMap::idx_t offset = _bm.find_first_set_bit(addr_to_offset(mr.start()), end_offset);

  while (offset < end_offset) {
    HeapWord* const addr = offset_to_addr(offset);
    size_t const obj_size = cast_to_oop(addr)->size(); // 此时会造成对象缺失
    offset = _bm.find_first_set_bit(offset + (obj_size >> _shifter), end_offset);
  }
  return true;
}
```
下面的图中可以看到，Young GC 和并发标记交替进行。

<image src="/assets/conc-root-region-scan/conc-root-region-sacn-ygc.png" width="80%"/>

`G1RootRegionScanClosure::do_oop_work` 方法标记对象，此对象为上述对象引用类型的属性。

```cpp
inline void G1RootRegionScanClosure::do_oop_work(T* p) {
  T heap_oop = RawAccess<MO_RELAXED>::oop_load(p);
  if (CompressedOops::is_null(heap_oop)) {
    return;
  }
  oop obj = CompressedOops::decode_not_null(heap_oop);
  _cm->mark_in_bitmap(_worker_id, obj);
}
```

### mark_bitmap

在并发标记中使用使用 bitmap 标记存活的对象，使用一个 bit 标记 64 bit 也就是 8 bytes。在 32 位操作系统中，8 bytes 恰好是最小对象所占字节（markword + class pointer）。

<image src= "/assets/conc-root-region-scan/conc-root-region-scan-bitmap-mark.png" width = "80%"/>

### set bit

下面看看 G1 是如果将对象地址映射到 mark_bitmap 上去的，又是如何标记对应 bit 的。

`pointer_delta(addr, _covered.start()) >> _shifter` 计算的是 `addr` 在 mark_bitmap 中的相对位置。

比如图中紫色对象对应的相对位置的索引是 4。

```cpp
inline bool MarkBitMap::par_mark(HeapWord* addr) {
  check_mark(addr);
  return _bm.par_set_bit(addr_to_offset(addr));
}

size_t addr_to_offset(const HeapWord* addr) const {
    return pointer_delta(addr, _covered.start()) >> _shifter;
}

inline bool BitMap::par_set_bit(idx_t bit, atomic_memory_order memory_order) {
  verify_index(bit);
  volatile bm_word_t* const addr = word_addr(bit);
  const bm_word_t mask = bit_mask(bit);
  bm_word_t old_val = load_word_ordered(addr, memory_order);
}
```

`word_addr` 获取 bit 位置在 mark_bitmap 上的所归属的地址，然后使用 `load_word_ordered` 将地址的所在的读出来，并且一次性读 64 bit ，即 8 个字节。 

`bit_in_word` 将地址在 mark_bitmap 上偏移映射到 64 bit 上，例如 bit = 76，那么 bit 在第二个 64 bit 上的偏移为 12（76 / 64）（最小为0）。

`1 << bit_in_word(bit)` 等于 `1000000000000`，标记第 13 个bit 为 1，表示这个位置的对象状态为存活。

```cpp
bm_word_t* word_addr(idx_t bit) {
  return map() + to_words_align_down(bit);
}

static idx_t bit_in_word(idx_t bit) { return bit & (BitsPerWord - 1); }

static bm_word_t bit_mask(idx_t bit) { return (bm_word_t)1 << bit_in_word(bit); }
```

`new_val = old_val | mask` 与旧值相或得到新值，`Atomic::cmpxchg` 设置新值，此地址所在的对象标记完成。

mark_bitmap 占整个堆空间的 1/64（1.56%）。

```cpp
do {
  const bm_word_t new_val = old_val | mask;
  if (new_val == old_val) {
    return false;     // Someone else beat us to it.
  }
  const bm_word_t cur_val = Atomic::cmpxchg(addr, old_val, new_val, memory_order);
  if (cur_val == old_val) {
    return true;      // Success.
  }
  old_val = cur_val;  // The value changed, try again.
} while (true);
```

## 总结

本文介绍了并发标记触发的条件、TAMS的概念、root region 的组成、mark_bitmap 的结构和标记原理。
















