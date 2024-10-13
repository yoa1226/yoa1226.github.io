#include <jni.h>
#include <CriticalGC.h>

static jbyte* sink;

JNIEXPORT void JNICALL Java_CriticalGC_acquire(JNIEnv* env, jclass klass, jintArray arr) {
   sink = (*env)->GetPrimitiveArrayCritical(env, arr, 0);
}

JNIEXPORT void JNICALL Java_CriticalGC_release(JNIEnv* env, jclass klass, jintArray arr) {
   (*env)->ReleasePrimitiveArrayCritical(env, arr, sink, 0);
}
