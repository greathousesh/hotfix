package com.demo.hotfix.core

import android.content.Context
import com.demo.hotfix.NativeHook
import java.io.File
import java.io.InputStream

/**
 * 补丁持久化：SDK 自行决定保存路径，对外只收数据流。
 *
 * 落在内部沙盒 `filesDir/hotfix/`（app 自己拥有，无文件 owner/SELinux/权限问题，debug/release 通用）：
 *   patch.dex / patch_res.apk / libpatch.so / native_hooks.txt（native hook 规格，供冷启动续用）。
 */
internal class PatchStore(ctx: Context) {

    private val dir = File(ctx.filesDir, "hotfix").apply { mkdirs() }
    private val dex = File(dir, "patch.dex")
    private val res = File(dir, "patch_res.apk")
    private val so = File(dir, "libpatch.so")
    private val nativeSpec = File(dir, "native_hooks.txt")

    fun saveJava(input: InputStream): File = write(input, dex)

    fun saveResource(input: InputStream): File = write(input, res)

    fun saveNative(input: InputStream, hooks: List<NativeHook>): File {
        write(input, so)
        nativeSpec.writeText(hooks.joinToString("\n") { "${it.targetLib}|${it.targetSym}|${it.patchSym}" })
        return so
    }

    fun javaPatch(): File? = dex.takeIf { it.exists() }

    fun resourcePatch(): File? = res.takeIf { it.exists() }

    fun nativePatch(): Pair<File, List<NativeHook>>? {
        if (!so.exists() || !nativeSpec.exists()) return null
        val hooks = nativeSpec.readLines().mapNotNull { line ->
            line.split("|").takeIf { it.size == 3 }?.let { NativeHook(it[0], it[1], it[2]) }
        }
        return if (hooks.isEmpty()) null else so to hooks
    }

    // 先删再写：dex 经 dexopt / setReadOnly 后只读，直接覆盖会 Permission denied。input 由本方法消费并关闭。
    private fun write(input: InputStream, dest: File): File {
        dest.delete()
        input.use { src -> dest.outputStream().use { src.copyTo(it) } }
        return dest
    }
}
