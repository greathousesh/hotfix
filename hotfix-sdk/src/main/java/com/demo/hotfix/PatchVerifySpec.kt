package com.demo.hotfix

/**
 * 补丁安装前的可选校验信息。
 *
 * 生产下发应至少提供 [targetBaseVersion] 和 [sha256]。SDK 会先校验目标 base 版本，
 * 再校验已落盘 pending 补丁的 SHA-256，全部通过后才进入 apply/commit 流程。
 */
data class PatchVerifySpec(
    val targetBaseVersion: String? = null,
    val sha256: String? = null,
)
