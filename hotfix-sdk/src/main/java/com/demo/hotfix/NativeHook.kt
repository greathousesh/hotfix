package com.demo.hotfix

/**
 * 一条 Native 内联 hook 规格：把 `lib<targetLib>.so` 里的导出符号 [targetSym]
 * 重定向到补丁 so 里的 [patchSym]。一个 native 补丁可包含多条。
 */
data class NativeHook(
    val targetLib: String,
    val targetSym: String,
    val patchSym: String,
)
