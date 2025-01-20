---
layout: post
title:  "Java 是如何开始运行的"
date:   2024-12-01 11:00:00 +0200
tags: [Java JVM]
---

当使用 `java` 命令执行程序时，JVM 中需要执行许多准备工作才能让 `java` 程序运行起来。本文将带领读者窥见 JVM 底层源码，深入理解 `java` 程序启动的流程。

在前面的文章 [JVM 创建](https://yoa1226.github.io/2024/08/10/g1-gc-init.html#jvm-%E5%88%9B%E5%BB%BA) 中介绍了 `JVM main` 函数的入口。

## BootClassLoader

BootClassLoader 用于加载标准库，它的初始化却很简单。从代码中可以看到由 `Handle()` 封装的 `classLoader` 其实是 `null`，所有的数据都在 `ClassLoaderData` 中。

```cpp
// Threads::create_vm -> init_globals -> universe_init
ClassLoaderData * ClassLoaderData::_the_null_class_loader_data = nullptr;

void ClassLoaderData::init_null_class_loader_data() {
    //Handle()  { _handle = nullptr; }
  _the_null_class_loader_data = new ClassLoaderData(Handle(), false);
}

ClassLoaderData::ClassLoaderData(Handle h_class_loader, ...){
    //... omit 
    //return new Dictionary(this, size);
    _dictionary = create_dictionary(); 
}
```

### FindClassFromBootLoader

使用 `BootClassLoader` 加载 `class` 时，先要使用 `GetProcAddress` 获取函数 `JVM_FindClassFromBootLoader`。

`resolve_or_null` 负责解析目标 `class`。

```cpp
static FindClassFromBootLoader_t *findBootClass = NULL
// Returns a class loaded by the bootstrap class loader; or null if not found.
jclass FindBootStrapClass(JNIEnv *env, const char *classname){
   findBootClass = (FindClassFromBootLoader_t *)GetProcAddress(hJvm,
            "JVM_FindClassFromBootLoader");
}

JVM_ENTRY(jclass, JVM_FindClassFromBootLoader(JNIEnv* env,
                                              const char* name))
  TempNewSymbol h_name = SymbolTable::new_symbol(name);
  Klass* k = SystemDictionary::resolve_or_null(h_name, CHECK_NULL);
JVM_END
```

由于 `class_loader = Handle()` ，所以 `class_loader() == nullptr` ，最终返回的 `ClassLoaderData` 就是对应于 `BootClassLoader` 的 `ClassLoaderData::the_null_class_loader_data()` 。

JVM 使用 `class_loader = Handle()` 来决定加载的类是由 `BootClassLoader` 加载还是由其他 `ClassLoader` 加载。

```cpp
//-> resolve_or_null
Klass* SystemDictionary::resolve_or_null(Symbol* class_name, Handle class_loader, TRAPS) {
    //class_loader = Handle()
  return resolve_instance_class_or_null(class_name, class_loader, THREAD);
}

InstanceKlass* SystemDictionary::resolve_instance_class_or_null(Symbol* name, Handle class_loader){
  ClassLoaderData* loader_data = register_loader(class_loader);
}

ClassLoaderData* SystemDictionary::register_loader(Handle class_loader, bool create_mirror_cld) {
  return (class_loader() == nullptr) ? ClassLoaderData::the_null_class_loader_data() :
                ClassLoaderDataGraph::find_or_create(class_loader);
}
```

## 标准库类加载

### 类加载

此时加载的 java 类包括：

- `java.lang` 包： Object、String 、Class 等。
- `java_io` 包：Serializable、ByteArrayInputStream 等。
- `jdk.internal.vm` 包。
- `java.net` 包。

具体类加载清单可见 `src/hotspot/share/classfile/vmClassMacros.hpp` ，搜索 `#define VM_CLASSES_DO(do_klass) ` 。

```cpp
// create_vm -> init_globals2 -> universe2_init ->  Universe::genesis ->  SystemDictionary::initialize 
void SystemDictionary::initialize(TRAPS) {
  vmClasses::resolve_all(CHECK);   // Resolve basic classes
}

// vmClasses::resolve_all ->  resolve_through -> resolve_until
bool vmClasses::resolve(vmClassID id, TRAPS) {
  InstanceKlass** klassp = &_klasses[as_int(id)];
  Symbol* symbol = vmSymbols::symbol_at(vmSymbols::as_SID(sid));
  Klass* k = SystemDictionary::resolve_or_fail(symbol, true, CHECK_false);
}

java_lang_Object::register_natives(CHECK){
  InstanceKlass* obj = vmClasses::Object_klass();
  Method::register_native(obj, vmSymbols::hashCode_name(),
                          vmSymbols::void_int_signature(), (address) &JVM_IHashCode, CHECK);
  // wait / notify/ notifyAll /  clone
 }
```

`java_lang_Object::register_natives` 绑定 native 方法到 `Object` 。

### 类初始化

`Klass` 只有初始化以后，才能被使用创建对象实例。

```cpp
//-> create_vm -> initialize_java_lang_classes
void Threads::initialize_java_lang_classes(JavaThread* main_thread, TRAPS) {
    initialize_class(vmSymbols::java_lang_String(), CHECK);
    java_lang_String::set_compact_strings(CompactStrings);
    //其他类
}
static void initialize_class(Symbol* class_name, TRAPS) {
  Klass* klass = SystemDictionary::resolve_or_fail(class_name, true, CHECK);
  InstanceKlass::cast(klass)->initialize(CHECK);
}
```

## Thread

创建 `JavaThread` 对象，并且绑定当前 os 线程。

```cpp
//create_vm 
// Attach the main thread to this os thread
JavaThread* main_thread = new JavaThread();
Thread::set_as_starting_thread(main_thread);
```

JVM 层面 `JavaThread` 和 Java 层面 `Thread` 相互绑定。

`ik->allocate_instance_handle(CHECK)` 只是为对象分配的内存，初始化对象头，但是没有调用构造方法。

`call_special` 调用对象构造方法 `Thread(ThreadGroup group, String name)` 初始化 Java 主线程。主线程的名称是 `java_lang_String::create_from_str("main", CHECK);` 也就是字符串 `"main"`。

最后设置线程状态。

```cpp
//create_vm -> initialize_java_lang_classes
static void create_initial_thread(Handle thread_group, JavaThread* thread,
                                 TRAPS) {
  InstanceKlass* ik = vmClasses::Thread_klass();
  instanceHandle thread_oop = ik->allocate_instance_handle(CHECK);

  java_lang_Thread::set_thread(thread_oop(), thread);
  thread->set_threadOopHandles(thread_oop())

  Handle string = java_lang_String::create_from_str("main", CHECK);

  JavaValue result(T_VOID);
  JavaCalls::call_special(&result, thread_oop,
                          ik,
                          vmSymbols::object_initializer_name(),
                          vmSymbols::threadgroup_string_void_signature(),
                          thread_group,
                          string,
                          CHECK);
//public Thread(ThreadGroup group, String name) {}

  java_lang_Thread::set_thread_status(thread_oop(),
                                      JavaThreadStatus::RUNNABLE);
}

```

## System

`java.lang.System` 在程序启动过程中扮演了重要的角色，`initPhase1-3` 三个方法分别完成不同资源的初始化。

在 JVM 中使用 `JavaCalls::call_static` 调用 java 类的静态方法，根据 Klass 、函数名称、方法签名定位。

```cpp
//create_vm->initialize_java_lang_classes
void Threads::initialize_java_lang_classes(JavaThread* main_thread, TRAPS) {
// Phase 1 of the system initialization in the library, java.lang.System class initialization
  call_initPhase1(CHECK)
}
//private static void initPhase1(){}
static void call_initPhase1(TRAPS) {
  Klass* klass = vmClasses::System_klass();
  JavaValue result(T_VOID);
  JavaCalls::call_static(&result, klass, vmSymbols::initPhase1_name(),
                                         vmSymbols::void_method_signature(), CHECK);
}
```

`initPhase1` 初始化系统配置、标准输入输出、注册信号处理等。

```java
private static void initPhase1() {
  Map<String, String> tempProps = SystemProps.initProperties();
  ineSeparator = props.getProperty("line.separator");
  FileInputStream fdIn = new In(FileDescriptor.in);
  FileOutputStream fdOut = new Out(FileDescriptor.out);
  FileOutputStream fdErr = new Out(FileDescriptor.err);
  Terminator.setup();
  VM.initLevel(1);
}
```

`initPhase2` 初始化模块化系统。

```cpp
// This will initialize the module system.  Only java.base classes can be
// loaded until phase 2 completes
call_initPhase2(CHECK_JNI_ERR);
```

`initPhase3` 初始化 `SecurityManager` 、设置 `SystemClassLoader` 、设置线程的 `ContextClassLoader`。

```java
/*
* Invoked by VM.  Phase 3 is the final system initialization:
* 1. eagerly initialize bootstrap method factories that might interact
*    negatively with custom security managers and custom class loaders
* 2. set security manager
* 3. set system class loader
* 4. set TCCL
*/
private static void initPhase3() {
  // custom security manager may be in non-exported package
  ctor.setAccessible(true);
  SecurityManager sm = (SecurityManager) ctor.newInstance();
  // initializing the system class loader
  VM.initLevel(3);
  // system class loader initialized
  ClassLoader scl = ClassLoader.initSystemClassLoader();

  // set TCCL
  Thread.currentThread().setContextClassLoader(scl);

  // system is fully initialized
  VM.initLevel(4);
}
```

缓存 classLoaer 

```cpp
// cache the system and platform class loaders
SystemDictionary::compute_java_loaders(CHECK_JNI_ERR)
```


## JavaMain

下面的代码解析主类，获取应用参数，执行主类 main 方法。

```cpp
JavaMain(void* _args) {
    mainClass = LoadMainClass(env, mode, what);
    /* Build platform specific argument array */
    mainArgs = CreateApplicationArgs(env, argv, argc);
    invokeStaticMainWithArgs(env, mainClass, mainArgs);
}
```

首先需要加载使用 `GetLauncherHelperClass` 加载 `LauncherHelper`。

获取 `LauncherHelper`  的 `checkAndLoadMain` 方法。

`CallStaticObjectMethod` 执行 `checkAndLoadMain` 方法。

```cpp

static jclass LoadMainClass(JNIEnv *env, int mode, char *name) {
    jclass cls =GetLauncherHelperClass(env);
    NULL_CHECK0(mid = (*env)->GetStaticMethodID(env, cls,
                "checkAndLoadMain", "(ZILjava/lang/String;)Ljava/lang/Class;"));
    NULL_CHECK0(str = NewPlatformString(env, name));
    NULL_CHECK0(result = (*env)->CallStaticObjectMethod(env, cls, mid,
                                                        USE_STDERR, mode, str));
    return (jclass)result;
}

static jclass helperClass = NULL;
jclass GetLauncherHelperClass(JNIEnv *env) {
    if (helperClass == NULL) {
        NULL_CHECK0(helperClass = FindBootStrapClass(env,
                "sun/launcher/LauncherHelper"));
    }
    return helperClass;
}
```

### checkAndLoadMain

`loadMainClass` 负责使用 `LM_MODULE ` 中找到主类。

```java
public static Class<?> checkAndLoadMain(boolean printToStderr, int mode, String what) {
    Class<?> mainClass = loadMainClass(mode, what);
    validateMainMethod(mainClass);
    return mainClass;
}

private static final int LM_CLASS   = 1;
private static final int LM_JAR     = 2;
private static final int LM_MODULE  = 3;
```

如果是 `LM_CLASS` 模式，例如 `java com.test.HelloWorld` 启动，`what` 就是类名称。

```java
private static Class<?> loadMainClass(int mode, String what) {
    String cn = null; JarFile jarFile = null;
    switch (mode) {
        case LM_CLASS:
            cn = what; break;
        case LM_JAR:
             jarFile = new JarFile(what);
             cn = getMainClassFromJar(jarFile);
            break;
        default: throw new InternalError("" + mode + ": Unknown launch mode");
    }
  cn = cn.replace('/', '.'); Class<?> mainClass = null;
  ClassLoader scl = ClassLoader.getSystemClassLoader();
  return Class.forName(cn, false, scl);
}
```

如果是 `LM_JAR` 模式，例如 `java -jar ` 启动方式，则需要 `getMainClassFromJar` 解析主类。

### jarFile

`String mainValue = mainAttrs.getValue(MAIN_CLASS)` 就是根据 key 找到值。

当 java 代码被打包成 jar 文件之后，会存在 `/META-INF/MANIFEST.MF` 文件。下面是一个 Spring boot 打包之后的内容：

```properties
Manifest-Version: 1.0
Created-By: Maven JAR Plugin 3.3.0
Build-Jdk-Spec: 21
Implementation-Title: bee-admin
Implementation-Version: 0.0.1
Main-Class: org.springframework.boot.loader.JarLauncher
Start-Class: com.test.WebServer
Spring-Boot-Version: 3.1.4
Spring-Boot-Classes: BOOT-INF/classes/
Spring-Boot-Lib: BOOT-INF/lib/
Spring-Boot-Classpath-Index: BOOT-INF/classpath.idx
Spring-Boot-Layers-Index: BOOT-INF/layers.idx
```

`mainValue` 的值就是 `org.springframework.boot.loader.JarLauncher`。`Start-Class: com.test.WebServer` 就是实际上用户写的主类。

`mainAttrs.getValue(LAUNCHER_AGENT_CLASS);` 加载 agent 技术。

```java
private static String getMainClassFromJar(JarFile jarFile){
  // Main-Class
  String mainValue = mainAttrs.getValue(MAIN_CLASS);
  // Launcher-Agent-Class (only check for this when Main-Class present)
  String agentClass = mainAttrs.getValue(LAUNCHER_AGENT_CLASS);
  return mainValue;
}
```

`invokeStaticMainWithArgs` 执行主类 `main` 方法。

```cpp
/*
 * Invokes static main(String[]) method if found.
 * Returns 0 with a pending exception if not found. Returns 1 if invoked, maybe
 * a pending exception if the method threw.
 */
int invokeStaticMainWithArgs(JNIEnv *env, jclass mainClass, jobjectArray mainArgs) {
    jmethodID mainID = (*env)->GetStaticMethodID(env, mainClass, "main",
                                  "([Ljava/lang/String;)V");
    (*env)->CallStaticVoidMethod(env, mainClass, mainID, mainArgs);
    return 1; // method was invoked
}
```

回到 jar 中 java 代码，查看用户主类是如何被执行的。

首先执行 `JarLauncher` 中的 `main` 方法，执行 `Launcher` 的 `launch` 方法。

`getMainClass` 方法获取用户定义的主类，本例为 `com.test.WebServer`。

`MainMethodRunner.run` 方法执行用户定义主类的 `main` 。执行主类则开始执行用户的程序逻辑。

```java
public class JarLauncher extends ExecutableArchiveLauncher  { //also extends Launcher
  public static void main(String[] args) throws Exception {
        (new JarLauncher()).launch(args);
    }
}

public abstract class ExecutableArchiveLauncher extends Launcher {
   protected String getMainClass(){
     Manifest manifest = this.archive.getManifest();
     //com.test.WebServer
     return manifest.getMainAttributes().getValue("Start-Class"); 
   }

}

public abstract class Launcher {
     protected void launch(String[] args) throws Exception {
        ClassLoader classLoader = ;
        String jarMode = System.getProperty("jarmode");
        String launchClass =  this.getMainClass();
        this.launch(args, launchClass, classLoader);
    }

     protected void launch(String[] args, String launchClass, ClassLoader classLoader) {
        Thread.currentThread().setContextClassLoader(classLoader);
        this.createMainMethodRunner(launchClass, args, classLoader).run();
    }

     protected MainMethodRunner createMainMethodRunner(String mainClass, String[] args, ClassLoader classLoader) {
        return new MainMethodRunner(mainClass, args);
    }
}

public class MainMethodRunner {
    private final String mainClassName;
    private final String[] args;

    public void run() throws Exception {
        Class<?> mainClass = Class.forName(this.mainClassName, false, Thread.currentThread().getContextClassLoader());
        Method mainMethod = mainClass.getDeclaredMethod("main", String[].class);
        mainMethod.setAccessible(true);
        mainMethod.invoke((Object)null, this.args); //invoke main method
    }
}
```
## 总结

本文分析了 JVM 启动到执行 main 方法的全过程，主要包括：

核心类加载：BootClassLoader 加载核心类库，初始化标准库类（如 String、Thread）。
主线程创建：绑定主线程，初始化并设置为运行状态。
系统初始化：完成 System 类初始化和环境配置。
主类执行：加载主类并调用其 main 方法，进入应用逻辑。
Spring Boot 启动：通过 Launcher 进入主类，启动应用。
JVM 启动过程展示了高效的初始化和强大的跨平台能力。
