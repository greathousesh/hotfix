package com.demo.hotfix

/**
 * 一条 Native 内联 hook 规格：把 `lib<targetLib>.so` 里的导出符号 [targetSym]
 * 重定向到补丁 so 里的 [patchSym]。一个 native 补丁可包含多条规格。
 *
 * @param targetLib 被修 so 的**库名**，不含 `lib` 前缀和 `.so` 后缀。
 *   例：so 文件名为 `libnative_bug.so`，则填 `"native_bug"`。
 *   SDK 内部会拼成 `lib$targetLib.so` 再调 `dlopen`。
 * @param targetSym 被修函数的导出符号名（须 `extern "C"` 且未被 strip）。
 *   例：`"compute_add"`
 * @param patchSym  补丁 so 中替换函数的导出符号名。
 *   例：`"patched_add"`
 */
data class NativeHook(
    val targetLib: String,
    val targetSym: String,
    val patchSym: String,
)
