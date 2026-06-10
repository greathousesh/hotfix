package com.demo.app

/** 被 native 热修复的类：底层 compute_add 有 bug（返回 a-b）。 */
class NativeBug {
    init { System.loadLibrary("native_bug") }

    /** 实例 external fun -> JNI 名 Java_com_demo_app_NativeBug_nativeAdd(JNIEnv*, jobject, ...)，与 native_bug.c 一致。 */
    external fun nativeAdd(a: Int, b: Int): Int
}
