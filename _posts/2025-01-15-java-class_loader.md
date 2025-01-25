---
layout: post
title:  "源码理解 Java ClassLoader"
date:   2025-01-15 11:00:00 +0200
tags: [Java JVM ClassLoader]
---

ClassLoader 是 JVM 运行的重要组件，负责将字节码文件加载成 Class 对象。JVM 采用多层次的 ClassLoader 结构，不同层次的 ClassLoader 负责不同的类加载。在 JVM 中，Java 代码和 c++ 代码共同保证 ClassLoader 的完整性。本文将从 jvm 源码层面阐述 ClassLoader 的功能，展现出完整的 ClassLoader 架构图。

## 内置 ClassLoader

`BuiltinClassLoader` 翻译成中文叫做内置 ClassLoader。它有三个子类，分别是 BootClassLoader、PlatformClassLoader、AppClassLoader三个子类，分别负责不同路径下类的加载。

`BuiltinClassLoader` 三个子类形成逻辑上的父子关系。

```java
public class BuiltinClassLoader{
    // parent ClassLoader
    private final BuiltinClassLoader parent;
    // the URL class path, or null if there is no class path
    private @Stable URLClassPath ucp;
}
// BootClassLoader
//     ↑ parent 
// PlatformClassLoader
//     ↑ parent 
// AppClassLoader
```

- BootClassLoader: 加载 Java 核心类例如 `java.lang.Object` 和标准库中的类。
- Platform Class Loader: 替代了 Extension Class Loader，Platform Class Loader 主要负责加载 Java 平台模块（如 java.sql, java.xml 等）中定义的类。
- AppClassLoader: 主要负责加载 CLASSPATH 或通过 -classpath 或 -cp 参数指定的路径中的类和资源，它是大多数应用程序开发者直接或间接使用的类加载器。

## ClassLoader 初始化

`BootClassLoader` 不仅在 Java 层面有对应实例，而且 JVM 初始化时在 C++ 层面也有对应的实例。

C++ 层面初始化请看前文 [BootClassLoader 初始化](https://yoa1226.github.io/2025/01/10/java-run-main.html#bootclassloader)。

```cpp
ClassLoaderData * ClassLoaderData::_the_null_class_loader_data = nullptr;

void ClassLoaderData::init_null_class_loader_data() {
    //Handle()  { _handle = nullptr; }
  _the_null_class_loader_data = new ClassLoaderData(Handle(), false);
}
```

Java 层面的初始化由静态代码块定义，即当 `ClassLoaders` 被加载的时候会初始化。

```java
public class ClassLoaders {
  static{
    BOOT_LOADER = new BootClassLoader(bootUcp);
    PLATFORM_LOADER = new PlatformClassLoader(BOOT_LOADER);
    APP_LOADER = new AppClassLoader(PLATFORM_LOADER, ucp);
  }
}
```

JVM 初始化的时候会加载一批类，见前文 [类加载](https://yoa1226.github.io/2025/01/10/java-run-main.html#%E7%B1%BB%E5%8A%A0%E8%BD%BD)。

部分定义代码

```cpp
  do_klass(jdk_internal_loader_ClassLoaders_klass,      jdk_internal_loader_ClassLoaders                      ) \

  do_klass(jdk_internal_loader_ClassLoaders_AppClassLoader_klass,      jdk_internal_loader_ClassLoaders_AppClassLoader) \

  do_klass(jdk_internal_loader_ClassLoaders_PlatformClassLoader_klass, jdk_internal_loader_ClassLoaders_PlatformClassLoader) \
```

> 为什么没有加载 BootClassLoader ？

上述 ClassLoader 都是被 `_the_null_class_loader_data` 这个 C++ `ClassLoader` 加载的。

上述代码执行完成以后，BootClassLoader、PlatformClassLoader、AppClassLoader 这几个对象也就被创建出来了。

```java
// BootClassLoader
//     ↑ parent 
// PlatformClassLoader
//     ↑ parent 
// AppClassLoader
```

## BootClassLoader / _the_null_class_loader_data 加载类

`_the_null_class_loader_data` 是对最顶层加载器的封装，此时的加载器就是 nullptr。

```cpp
_the_null_class_loader_data = new ClassLoaderData(Handle(), false);

Handle(){ _handle = nullptr; }
```

`BootClassLoader` 加载类时最终也是调用`_the_null_class_loader_data` 的逻辑。

下面是 `BootClassLoader` 的部分逻辑。

```cpp
private static class BootClassLoader extends BuiltinClassLoader {
    protected Class<?> loadClassOrNull(String cn, boolean resolve) {
        return JLA.findBootstrapClassOrNull(cn);
    }
};
//-> findBootstrapClassOrNull 
//-> ClassLoader.findBootstrapClassOrNull
private static native Class<?> findBootstrapClass(String name);

JNIEXPORT jclass JNICALL
Java_java_lang_ClassLoader_findBootstrapClass(JNIEnv *env, jclass dummy,
                                              jstring classname){
  cls = JVM_FindClassFromBootLoader(env, clname);
}
```

`loadClassOrNull`方法最终调用 `JVM_FindClassFromBootLoader` 函数。

```cpp
JVM_ENTRY(jclass, JVM_FindClassFromBootLoader(JNIEnv* env,
                                              const char* name))
Klass* k = SystemDictionary::resolve_or_null(h_name, CHECK_NULL);
return (jclass) JNIHandles::make_local(THREAD, k->java_mirror());
JVM_END

//-> resolve_or_null -> resolve_or_null
resolve_or_null(class_name, Handle(), THREAD); //Handle(){ _handle = nullptr; }

//->resolve_instance_class_or_null
InstanceKlass* SystemDictionary::
  resolve_instance_class_or_null(Symbol* name, Handle class_loader, TRAPS) {
  ClassLoaderData* loader_data = register_loader(class_loader);

  // Do actual loading
  loaded_class = load_instance_class(name, class_loader, THREAD);
}

//->register_loader
return (class_loader() == nullptr) ? ClassLoaderData::the_null_class_loader_data() :
                                      ClassLoaderDataGraph::find_or_create(class_loader);
```

函数 `register_loader` 根据判断是否使用 `the_null_class_loader_data`。由于使用 `Handle()` 所以这里是 `ClassLoaderData::the_null_class_loader_data()`。

```cpp
//load_instance_class -> load_instance_class_impl
InstanceKlass* SystemDictionary::load_instance_class_impl(Symbol* class_name, Handle class_loader, TRAPS) {
  if (class_loader.is_null()) { // BootClassLoader
    if (k == nullptr) { // Use VM class loader
      k = ClassLoader::load_class(class_name, pkg_entry, search_only_bootloader_append, CHECK_NULL);
    }

    // find_or_define_instance_class may return a different InstanceKlass
    if (k != nullptr) {
      // If a class loader supports parallel classloading handle parallel define requests.
      // find_or_define_instance_class may return a different InstanceKlas
      k = find_or_define_instance_class(class_name, class_loader, k, CHECK_NULL);
    }
    return k
  }else{
    // other ClassLoader
  }
}

InstanceKlass* ClassLoader::load_class(Symbol* name, PackageEntry* pkg_entry, bool search_append_only, TRAPS) {
  ClassLoaderData* loader_data = ClassLoaderData::the_null_class_loader_data();
  InstanceKlass* result = KlassFactory::create_from_stream(stream, name,
                                                           loader_data,
                                                           cl_info, CHECK_NULL)
}
```

最终在函数 `load_class` 中调用 `KlassFactory::create_from_stream` 实现类加载。见前文 [类解析](https://yoa1226.github.io/2025/01/03/java-object-create.html#%E7%B1%BB%E8%A7%A3%E6%9E%90)。

## 其他类加载器

在前文 [加载主类](https://yoa1226.github.io/2025/01/10/java-run-main.html#checkandloadmain) 中有代码 `Class.forName(cn, false, scl)` 加载主类，最终调用 native 方法。

```java
//loader = ClassLoader.getSystemClassLoader();
private static native Class<?> forName0(String name, boolean initialize,
                                            ClassLoader loader, Class<?> caller);
//-> Java_java_lang_Class_forName0
//-> JVM_FindClassFromCaller
//-> find_class_from_class_loader
// Find a class with this name in this loader, using the caller's protection domain.
JVM_ENTRY(jclass, JVM_FindClassFromCaller(JNIEnv* env, const char* name,
                                          jboolean init, jobject loader,
                                          jclass caller))
return find_class_from_class_loader(env, h_name, init, h_loader, false, THREAD)
JVM_END
```

函数 `find_class_from_class_loader` 最终会调用 `load_instance_class_impl` 。

```cpp
InstanceKlass* SystemDictionary::load_instance_class_impl(Symbol* class_name, Handle class_loader, TRAPS) {
  // Use user specified class loader to load class. Call loadClass operation on class_loader.
   if (class_loader.is_null()) { /* BootClassLoader */}
   else{// 其他类加载器
    JavaCalls::call_virtual(&result,
                        class_loader,
                        spec_klass,
                        vmSymbols::loadClass_name(),
                        vmSymbols::string_class_signature(),
                        string,
                        CHECK_NULL);
    return InstanceKlass::cast(java_lang_Class::as_Klass(obj));
   }
}
```

上面代码 `JavaCalls::call_virtual` 调用的是 `vmSymbols::loadClass_name()` 方法。

```cpp
template(loadClass_name, "loadClass");
```

对应于 Java 方法 `ClassLoader#loadClass(String name)`，此代码就是通常说的双亲委派模型的实现。

其中 `parent.loadClass` 继续调用 `loadClass` 方法。

`findBootstrapClassOrNull` 前文已经论述过。

```java
public Class<?> loadClass(String name) throws ClassNotFoundException {
  return loadClass(name, false);
}
protected Class<?> loadClass(String name, boolean resolve) {
    synchronized (getClassLoadingLock(name)) {
        // First, check if the class has already been loaded
        Class<?> c = findLoadedClass(name); // 查询是否已经在被加载过
        if (c == null) {
            if (parent != null) {
                c = parent.loadClass(name, false); //使用父类加载器加载
            } else {
                c = findBootstrapClassOrNull(name); //在顶层加载器中搜索
            }
            if (c == null) {
                // If still not found, then invoke findClass in order
                // to find the class.
                c = findClass(name); //当前加载器加载
            }
        }
        return c;
    }
}
```

但是对于内置的 ClassLoader 都是继承自 `BuiltinClassLoader`，重写了 `loadClass` 方法。而`BootClassLoader` 对 `loadClassOrNull` 进行了重写。

所以本文这里`JavaCalls::call_virtual`  调用的是 `BuiltinClassLoader#loadClass`，增了模块化的支持。其他逻辑与 `ClassLoader#loadClass` 类似。

```java
//BuiltinClassLoader
@Override
protected Class<?> loadClass(String cn, boolean resolve) {
    return loadClassOrNull(cn, resolve);
}

private static class BootClassLoader extends BuiltinClassLoader {
    @Override
    protected Class<?> loadClassOrNull(String cn, boolean resolve) {
        return JLA.findBootstrapClassOrNull(cn);
    }
};
```

### findLoadedClass

回到 `ClassLoader#loadClass` ，看看 `findLoadedClass` 方法是如何实现的。

```java
private final native Class<?> findLoadedClass0(String name);

//-> Java_java_lang_ClassLoader_findLoadedClass0
//-> JVM_FindLoadedClass
JVM_ENTRY(jclass, JVM_FindLoadedClass(JNIEnv *env, jobject loader, jstring name))
  SystemDictionary::find_instance_or_array_klass(THREAD, klass_name, h_loader);
JVM_EN

//find_instance_or_array_klass -> find_instance_klass
InstanceKlass* SystemDictionary::find_instance_klass(Thread* current,
                                                     Symbol* class_name,
                                                     Handle class_loader) {
Dictionary* dictionary = loader_data->dictionary();
return dictionary->find_class(current, class_name)
}
```

### BuiltinClassLoader#findClassOnClassPathOrNull

`findClassOnClassPathOrNull` 最终会调用 native 方法 `defineClass1`。

```java
private Class<?> findClassOnClassPathOrNull(String cn) {
   return defineClass(cn, res);
}

static native Class<?> defineClass1(ClassLoader loader, String name, byte[] b, int off, int len,ProtectionDomain pd, String source);

JNIEXPORT jclass JNICALL
Java_java_lang_ClassLoader_defineClass1(JNIEnv *env,
                                        jclass cls,
                                        jobject loader,
                                        jstring name,
                                        jbyteArray data,
                                        jint offset,
                                        jint length,
                                        jobject pd,
                                        jstring source){
  body = (jbyte *)malloc(length);
  (*env)->GetByteArrayRegion(env, data, offset, length, body);
  return JVM_DefineClassWithSource(env, utfName, loader, body, length, pd, utfSource);
}
```

`GetByteArrayRegion` 方法会将字节码内容拷贝到新分配的数组中。 `GetByteArrayRegion` 也用于解决 G1 中 region pin 的问题。见 [region pinning for g1](https://yoa1226.github.io/2024/08/01/region-pinning-for-g1.html)。


```cpp
JVM_ENTRY(jclass, JVM_DefineClassWithSource(JNIEnv *env, const char *name, jobject loader, const jbyte *buf, jsize len, jobject pd, const char *source))
  return jvm_define_class_common(name, loader, buf, len, pd, source, THREAD);
JVM_END

//->jvm_define_class_common
//->SystemDictionary::resolve_from_stream
```

最终调用 `resolve_from_stream` 解析类。

## 总结

本文从 Java 和 C++ 源码介绍了内置三个 ClassLoader 的创建，以及他们是如何加载 Java 类的。希望本文对于想深入理解 ClassLoader 机制的读者有所作用。

