注意到方法 `retire_gc_alloc_region` 是将 old region 和 survivor region 加入到 `G1CollectedHeap` 对应的 set 中，而不是 cset。

为了迅速找到入口函数在方法 `retire_gc_alloc_region`加点日志。

```cpp
void G1CollectedHeap::retire_gc_alloc_region(G1HeapRegion* alloc_region, size_t allocated_bytes, G1HeapRegionAttr dest) {
  assert(dest.is_young(), "retire_gc_alloc_region (%d)", dest.type())

  _bytes_used_during_gc += allocated_bytes;
  if (dest.is_old()) { old_set_add(alloc_region);
  } else { _survivor.add_used_bytes(allocated_bytes); }

  bool const during_im = collector_state()->in_concurrent_start_gc();
  if (during_im && allocated_bytes > 0) {
    _cm->add_root_region(alloc_region); //注意后面并发标记时会说到
  }
}
```

从日志文件中可以看到报错的调用栈，确实是在 GC 收尾时将 region 加入到 cset 中。

<image src="/assets/ygc-pre/g1-ygc-pre-cset-retire-gc-alloc-region.png" width="80%">



最近在看 G1 垃圾收集器的相关代码，将理解内容整理成文章输出。

最开始在掘金更新，后来发现不好管理、图片有水印、发布需要被审核等原因，逐步迁移到 github。

文章地址为：https://yoa1226.github.io

觉得有用的朋友点个🌟🌟😘

https://github.com/yoa1226/yoa1226.github.io


```cpp
bool G1Policy::need_to_start_conc_mark(const char* source, size_t alloc_word_size) {
  if (about_to_start_mixed_phase()) {
    return false;
  }

  size_t marking_initiating_used_threshold = _ihop_control->get_conc_mark_start_threshold();

  size_t cur_used_bytes = _g1h->non_young_capacity_bytes();
  size_t alloc_byte_size = alloc_word_size * HeapWordSize;
  size_t marking_request_bytes = cur_used_bytes + alloc_byte_size;

  bool result = false;
  if (marking_request_bytes > marking_initiating_used_threshold) {
    result = collector_state()->in_young_only_phase();
    log_debug(gc, ergo, ihop)("%s occupancy: " SIZE_FORMAT "B allocation request: " SIZE_FORMAT "B threshold: " SIZE_FORMAT "B (%1.2f) source: %s",
                              result ? "Request concurrent cycle initiation (occupancy higher than threshold)" : "Do not request concurrent cycle initiation (still doing mixed collections)",
                              cur_used_bytes, alloc_byte_size, marking_initiating_used_threshold, (double) marking_initiating_used_threshold / _g1h->capacity() * 100, source);
  }
  return result;
}

void G1Policy::maybe_start_marking() {
  if (need_to_start_conc_mark("end of GC")) {
    // Note: this might have already been set, if during the last
    // pause we decided to start a cycle but at the beginning of
    // this pause we decided to postpone it. That's OK.
    collector_state()->set_initiate_conc_mark_if_possible(true);
  }
}


bool const during_im = collector_state()->in_concurrent_start_gc();
  if (during_im && allocated_bytes > 0) {
    _cm->add_root_region(alloc_region);
  }

  G1EvacuationRootClosures* res = nullptr;
if (g1h->collector_state()->in_concurrent_start_gc()) {
  if (ClassUnloadingWithConcurrentMark) {
    res = new G1ConcurrentStartMarkClosures<false>(g1h, pss);
  } else {
    res = new G1ConcurrentStartMarkClosures<true>(g1h, pss);
  }
} else {
  res = new G1EvacuationClosures(g1h, pss, process_only_dirty_klasses);
}


  if (collector_state()->in_concurrent_start_gc()) {
    concurrent_mark()->pre_concurrent_start(_gc_cause);
  }


  void G1YoungCollector::enqueue_candidates_as_root_regions() {
  assert(collector_state()->in_concurrent_start_gc(), "must be");

  G1CollectionSetCandidates* candidates = collection_set()->candidates();
  for (G1HeapRegion* r : *candidates) {
    _g1h->concurrent_mark()->add_root_region(r);
  }
}

 EagerlyReclaimHumongousObjectsTask() 
 //处理大对象


```

