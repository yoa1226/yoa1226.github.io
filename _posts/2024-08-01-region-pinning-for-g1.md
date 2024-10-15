---
layout: post
title:  "解读 JEP 423: Region Pinning for G1"
date:   2024-08-01 11:00:00 +0200
tags: [GC, G1, JEP]
---

JDK 22 在 2024 年 3 月发布，其中 [JEP 423: Region Pinning for G1](https://openjdk.org/jeps/423) 对 G1 进行了增强，大大增加了 G1 的可用性。本文抽丝剥茧带领读者了解此 JEP 的来龙去脉。

> G1 全称叫 Garbage-First Garbage Collector，后面文章都简称 G1。

# JNI

JNI（Java Native Interface）是一种编程框架，允许Java代码调用本地（本机）应用程序或库，通常是用其他编程语言编写的代码，通常是C或C++。JNI提供了一种与Java虚拟机（JVM）交互的机制，使Java程序能够利用现有的C/C++代码库，或者访问与Java标准库中未提供的系统级资源。

## 临界区

JNI 定义了一组用于获取和释放指向 Java 对象指针的函数，这种函数总是成对使用。例如 [GetPrimitiveArrayCritical](https://github.com/openjdk/jdk/blob/master/src/hotspot/share/prims/jni.cpp#L2812) 和 [ReleasePrimitiveArrayCritical](https://github.com/openjdk/jdk/blob/master/src/hotspot/share/prims/jni.cpp#L2830)。

处于这一对函数之间的代码称之为 `临界区（critical region`，在临界区中被使用的 Java 对象称之为`临界对象（critical object`。

>For interoperability with unmanaged programming languages such as C and C++, JNI defines functions to get and then release direct pointers to Java objects. These functions must always be used in pairs: First, get a pointer to an object (e.g., via GetPrimitiveArrayCritical); then, after using the object, release the pointer (e.g., via ReleasePrimitiveArrayCritical). Code within such function pairs is considered to run in a critical region, and the Java object available for use during that time is a critical object.

当线程处于临界区时，GC 不应该移动与之关联的临界对象。如果线程触发 GC，它应该确认没有其他线程处于临界区，在 G1 中会停止此次 GC 。

下面是 JDK 21 中 G1 处理临界区的源码，当线程调用 `GetPrimitiveArrayCritical` 会直接进入临界区。

```cpp
// https://github.com/openjdk/jdk21/blob/master/src/hotspot/share/prims/jni.cpp#L2797
JNI_ENTRY(void*, jni_GetPrimitiveArrayCritical(JNIEnv *env, jarray array, jboolean *isCopy))
 HOTSPOT_JNI_GETPRIMITIVEARRAYCRITICAL_ENTRY(env, array, (uintptr_t *) isCopy);
  // Pin object
  Universe::heap()->pin_object(thread, a());
  return ret;
JNI_EN

// https://github.com/openjdk/jdk21/blob/master/src/hotspot/share/gc/g1/g1CollectedHeap.cpp#L2190
void G1CollectedHeap::pin_object(JavaThread* thread, oop obj) {
  GCLocker::lock_critical(thread);
}

// https://github.com/openjdk/jdk21/blob/master/src/hotspot/share/gc/shared/gcLocker.inline.hpp#L32
void GCLocker::lock_critical(JavaThread* thread) {
  //omit
  thread->enter_critical();
}
```

## 实验

为了简单地认识 JNI，下面做一个实验，[查看源码](/code/critical-gc/)。

首先是 C 文件，包含两个函数。

```c
#include <jni.h>
#include <CriticalGC.h>

static jbyte* sink;

// JNI 持有数组 arr
JNIEXPORT void JNICALL Java_CriticalGC_acquire(JNIEnv* env, jclass klass, jintArray arr) {
   sink = (*env)->GetPrimitiveArrayCritical(env, arr, 0);
}
//释放数组
JNIEXPORT void JNICALL Java_CriticalGC_release(JNIEnv* env, jclass klass, jintArray arr) {
   (*env)->ReleasePrimitiveArrayCritical(env, arr, sink, 0);
}
```

其次是 Java 文件，代码很简单。外层循环表示 获取/释放 数组 `arr` 的测试次数，内层循环表示数组 `arr` 被持有时创建对象的数量。程序的作用是当 JNI 持有 Java 的数组时出发 GC。

计算下`WINDOW` 占用内存的大小，`WINDOW` 数组本身占用内存为 114MB，数组中对象占 457 MB。

> 
> 默认开启指针压缩 114MB = 8+4+4+30_000_000*4，457 MB = (8+4+4)30_000_000。
> 
> 其中 114MB 称之为对象 WINDOW 的 shallow size， 661 MB（114+457）称之为对象 WINDOW 的 retained size。

```java
public class CriticalGC {

  static final int ITERS = Integer.getInteger("iters", 100);
  static final int ARR_SIZE = Integer.getInteger("arrSize", 1_000_000);
  static final int WINDOW = Integer.getInteger("window", 30_000_000);
  //JNI 函数
  static native void acquire(int[] arr);
  static native void release(int[] arr);

  static final Object[] window = new Object[WINDOW];

  public static void main(String... args) throws Throwable {
    System.loadLibrary("CriticalGC"); //加载 JNI 函数

    int[] arr = new int[ARR_SIZE]; //在内层循环的时候，一直被 JNI 持有

    for (int i = 0; i < ITERS; i++) {
      acquire(arr);
      System.out.println("Acquired");
      try {
        for (int c = 0; c < WINDOW; c++)
          window[c] = new Object();
      } catch (Throwable t) {
        // omit
      } finally {
        System.out.println("Releasing");
        release(arr);
      }
    }
  }
}

```

Makefile 文件需要添加 `JAVA_HOME` （如果没有配置环境变量），例如：

```shell
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
```

如果是 macOS 并且需要将 `all` 修改为：

```
javac -h . CriticalGC.java
cc -I. -I${JAVA_HOME}/include -I${JAVA_HOME}/include/darwin -shared -o libCriticalGC.dylib CriticalGC.c
```

代码准备好以后验证一下环境编译代码：

```shell
# 部分输出省略
➜  critical-gc java -version
openjdk version "21" 2023-09-19

➜  critical-gc make all   
javac -h . CriticalGC.java
cc -I. -I/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home/include -I/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home/include/darwin -shared -o libCriticalGC.dylib CriticalGC.c
```

# GCLocker

JNI 定义

## ParallelGC

使用 ParallelGC 运行代码：

```shell
➜  critical-gc make run-parallel
time java -Djava.library.path=. -Xms4g -Xmx4g -verbose:gc -XX:+UseParallelGC CriticalGC
[0.003s][info][gc] Using Parallel
Acquired
Releasing
Acquired
Releasing
[1.326s][info][gc] GC(0) Pause Young (GCLocker Initiated GC) 1096M->692M(3925M) 639.609ms
Acquired
Releasing
Acquired
Releasing
Acquired
Releasing
[4.175s][info][gc] GC(1) Pause Young (GCLocker Initiated GC) 2065M->983M(3925M) 221.085ms
```

从日志中可以看到 GC 没有发生在 `Acquired` 和 `Releasing` 之间，而是发生在 `Releasing` 之后，日志表明此次 GC 是 `GCLocker` 引起的。当 JNI 持有 Java 对象时，JVM 使用 `GCLocker` 阻止 GC 发生，此时如果 JNI 释放 `GCLocker` 就会立即触发 GC。日志 中 Pause Young (GCLocker Initiated GC) 代表的是一次由 GCLocker 状态退出后立即触发的 Young Generation GC。

## G1

使用 G1 运行代码:

```shell
➜  critical-gc make run-g1
time java -Djava.library.path=. -Xms4g -Xmx4g -verbose:gc -XX:+UseG1GC CriticalGC
[0.003s][info][gc] Using G1
Acquired
<HANGS>
```
可以观察到 Java 进程直接被挂起了，没有任何输出。

使用 `jstack` 看看 Java 进程，显示无法打开文件。

```shell
➜  critical-gc jstack 97204
97204: Unable to open socket file /var/folders/7v/6_cxsxmn7gl_kbm9t1vqkfh80000gn/T/.java_pid97204: target process 97204 doesn't respond within 10500ms or HotSpot VM not loaded
```

### 编译 JDK 21

为了搞清楚 JVM 内部发生的事情，我们编译一个开启 `debug` 模式的 JDK 21。

1. 下载

```shell
git clone --depth 1 git@github.com:openjdk/jdk21.git
```

2. 编译

```shell
jdk21 git:(master) bash configure --with-debug-level=slowdebug --with-native-debug-symbols=internal  --with-jvm-variants=server
jdk21 git:(master) make
```

3. 编译结果

```log
➜  jdk21 git:(master) ll build/macosx-aarch64-server-slowdebug/jdk
total 16
# 省略部分.....
drwxr-xr-x  31 yoa  staff   992B Oct 13 01:46 bin
drwxr-xr-x   8 yoa  staff   256B Oct 13 01:43 conf
drwxr-xr-x   8 yoa  staff   256B Oct 13 01:43 include
drwxr-xr-x  54 yoa  staff   1.7K Oct 13 01:47 lib
drwxr-xr-x   3 yoa  staff    96B Oct 13 01:46 man
drwxr-xr-x  71 yoa  staff   2.2K Oct 13 01:45 modules
-rw-r--r--   1 yoa  staff   178B Oct 13 01:43 release
```

### 再次运行

修改 makefile， 并运行

```shell
# your_path 是电脑目录
JAVA_HOME=${your_path}/jdk21/build/macosx-aarch64-server-slowdebug/jdk

#  -Xlog:gc*=trace 设置日志级别
run-g1:
	time ${your_path}/jdk21/build/macosx-aarch64-server-slowdebug/jdk/bin/java ${JVM_OPTS} -XX:+UseG1GC -Xlog:gc*=trace CriticalGC

# 注意是运行 critical-gc
make clean 
make all
make run-g1
```

新的运行日志明确显示有死锁发生，`assert(!JavaThread::current()->in_critical()) failed: Would deadlock`。
还有日志文件 `${your_path}/critical-gc/hs_err_pid64583.log`。

```log
➜  critical-gc make run-g1
time ${your_path}/jdk21/build/macosx-aarch64-server-slowdebug/jdk/bin/java -Djava.library.path=. -Xms4g -Xmx4g -verbose:gc -XX:+UseG1GC CriticalGC
[0.012s][info][gc] Using G1
Acquired
# 省略一些日志
[1.485s][trace][gc,alloc             ] main: Stall until clear
#
# A fatal error has been detected by the Java Runtime Environment:
#
#  Internal Error (${your_path}/jdk21/src/hotspot/share/gc/shared/gcLocker.cpp:107), pid=64583, tid=10243
#  assert(!JavaThread::current()->in_critical()) failed: Would deadlock
#
# An error report file with more information is saved as:
# ${your_path}/critical-gc/hs_err_pid64583.log
```

查看日志文件 `${your_path}/critical-gc/hs_err_pid64583.log` 里面 `main`线程的堆栈信息：

<image src = "/assets/region-pinning-for-g1/critical-gc-main-main-thread-stack.png" width="100%" />

根据`main`线程的堆栈信息和控制台日志可以知道抛出异常的代码在 `GCLocker::stall_until_clear` 方法中。

```cpp
void GCLocker::stall_until_clear() {
  assert(!JavaThread::current()->in_critical(), "Would deadlock");
  //omit
}
```

### 源码分析

结合测试代码、JDK 源码和日志，我们会有一些线索。

首先`mian` 线程进入临界区，调用链是 `Java_CriticalGC_acquire -> GetPrimitiveArrayCritical -> G1CollectedHeap::pin_object -> GCLocker::lock_critical(thread)`

接着 `mian` 线程循环调用 `window[c] = new Object()` 分配内存，正常调用链是 `OptoRuntime::new_instance_C -> MemAllocator::allocate()-> ...omit`，然后正常返回。

最后当内存不足时并且触发 GC 失败，会走到下面的代码，控制台也有输出相应的日志 `main: Stall until clear`。

```java
log_trace(gc, alloc)("%s: Stall until clear", Thread::current()->name());
GCLocker::stall_until_clear();
```

结论是：`main` 自身处于临界区线程，当内存不足时，它要去等待自己退出临界区，然后 G1 才能开始GC，然后就造成了死锁。

# G1 region pinning

使用 JDK 23 看下此问题修复之后的效果。

> JEP 423 在 JDk 22 加入，所以 JDK 大于等于 22 都可以。

在 Makefile 修改 `JAVA_HOME`，重新编译运行。

```shell
# makefile
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-23.jdk/Contents/Home
```

```shell
➜  critical-gc java -version
openjdk version "23" 2024-09-17
OpenJDK Runtime Environment Zulu23.28+85-CA (build 23+37)
OpenJDK 64-Bit Server VM Zulu23.28+85-CA (build 23+37, mixed mode, sharing)
```

```log
➜  critical-gc make run-g1
time java -Djava.library.path=. -Xms4g -Xmx4g -verbose:gc  -XX:+UseG1GC CriticalGC
[0.003s][info][gc] Using G1
Acquired
[0.428s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 325M->327M(4096M) 90.042ms
[0.554s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 505M->509M(4096M) 85.064ms
Releasing
Acquired
[0.686s][info][gc] GC(2) Pause Young (Normal) (G1 Evacuation Pause) 687M->691M(4096M) 88.747ms
[0.810s][info][gc] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 869M->873M(4096M) 82.226ms
[0.937s][info][gc] GC(4) Pause Young (Normal) (G1 Evacuation Pause) 1051M->1054M(4096M) 84.394ms
Releasing
```

从日志可以看到，G1 在 `Acquired` 和 `Releasing` 同样能执行 GC。

## 深入源码

我们直接查看最新的 G1 实现，主要是 `pin_object`。

```cpp
inline void G1CollectedHeap::pin_object(JavaThread* thread, oop obj) {
  uint obj_region_idx = heap_region_containing(obj)->hrm_index();
  //thread local 优化
  G1ThreadLocalData::pin_count_cache(thread).inc_count(obj_region_idx);
}

inline void G1RegionPinCache::inc_count(uint region_idx) {
  if (region_idx == _region_idx) {
    ++_count;
  } else {
    flush_and_set(region_idx, (size_t)1);
  }
}
```

最新的代码是，记录 region 中临界对象的个数。当 region 中有临界对象，GC 不回收此 region。例如下面是选择回收集的部分代码：

```cpp
  for (G1CollectionSetCandidateInfo* ci : *retained_list) {
    G1HeapRegion* r = ci->_r;
    if (r->has_pinned_objects()) {
      num_pinned_regions++;
        log_trace(gc, ergo, cset)("Retained candidate %u can not be reclaimed currently. Skipping.", r->hrm_index());
        //跳过此 region 不加入回收集
      continue;
    }
}
```

G1 内存回收的最小颗粒度是 region， 通过对 region 中临界对象的计数，在 GC 时选择计数为 0 的 region 进行回收，在 G1 层面解决了 `GCLocker` 问题。

## 拷贝对象

在 JEP 423 之前使用拷贝 Java 对象的方式解决 `GCLocker` 问题.

例如 Netty 就是使用的这种方式，详细信息可以看 [Do not use GetPrimitiveArrayCritical(...) due multiple not-fixed bugs](https://github.com/netty/netty/pull/8921/files)。下面是优化后的主要代码：

```cpp
jbyte addressBytes[16];
int len = (*env)->GetArrayLength(env, address);
// We use GetByteArrayRegion(...) and copy into a small stack allocated buffer and NOT GetPrimitiveArrayCritical(...)
(*env)->GetByteArrayRegion(env, address, 0, len, addressBytes);
```

通过将 Java 数组拷贝到线程栈上解决 `GCLocker` 问题，复制数组导致一点性能开销，但是在 JDK 22 之前这点开销是值得的。


# 总结

在 JEP 423 之前，G1 对于临界对象在 GC 中的处理经常会导致线程挂起、不必要的 OOM、还有可能在极端情况下导致 JVM 虚拟关闭。下面是一些事例：

- [GCLocker too often allocating 256 words - Elastic Stack / Logstash - Discuss the Elastic Stack](https://discuss.elastic.co/t/gclocker-too-often-allocating-256-words/323769/2)
- [Do not use GetPrimitiveArrayCritical(...) due multiple not-fixed bugs… by normanmaurer · Pull Request #8921 · netty/netty](https://github.com/netty/netty/pull/8921)
- [JIRA Running out of memory due to GC / Allocation race condition](https://confluence.atlassian.com/jirakb/jira-running-out-of-memory-due-to-gc-allocation-race-condition-957122851.html)

JEP 423 优化了 G1 的短板，提高了 G1 的可用性。如果你使用的是 Java 22 之前的版本遇到类似的问题，则可以参考 Netty 的实现。

本文从实验和源码的角度阐述了 JEP 423 解决的问题，希望对你有所帮助。












