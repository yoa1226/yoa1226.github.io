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

