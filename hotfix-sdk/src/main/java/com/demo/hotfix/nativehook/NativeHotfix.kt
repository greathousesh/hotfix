package com.demo.hotfix.nativehook

import android.util.Log

/**
 * Native 代码热修复（对应淘宝 tconhook / InlinePatch 的 C++ 内联 hook）。
 *
 * 用 @JvmStatic external，使 JNI 函数名为
 * Java_com_demo_hotfix_nativehook_NativeHotfix_nativeInlineHook(JNIEnv*, jclass, ...)，
 * 与 inline_hook.c 中的签名一致。
 */
object NativeHotfix {

    private const val TAG = "MiniHotfix"

    init {
        System.loadLibrary("minihotfix")
    }

    /**
     * @param targetLib   被修 so 名（如 "native_bug" 对应 libnative_bug.so）
     * @param targetSym   被修函数符号名（extern "C"、未 strip）
     * @param patchSoPath 补丁 so 绝对路径
     * @param patchSym    补丁 so 中替换函数符号名
     */
    fun apply(targetLib: String, targetSym: String, patchSoPath: String, patchSym: String): Boolean {
        val r = nativeInlineHook(targetLib, targetSym, patchSoPath, patchSym)
        if (r != 0) Log.e(TAG, "C++ inlinepatch updating result: fail($r)")
        else Log.i(TAG, "C++ inlinepatch updating ...... ok")
        return r == 0
    }

    @JvmStatic
    private external fun nativeInlineHook(
        targetLib: String, targetSym: String, patchSoPath: String, patchSym: String
    ): Int
}
