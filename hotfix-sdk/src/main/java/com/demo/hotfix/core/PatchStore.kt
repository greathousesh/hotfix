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
        // 先把 spec 写入临时文件，再原子 rename —— 与 so 的写法保持对称。
        // 顺序：spec tmp 写完 → spec rename → so rename。
        // 若崩在 spec rename 之前：so 未变，spec 不存在 -> nativePatch() 返回 null，安全。
        // 若崩在 so rename 之前：spec 是新的，so 是旧的 -> nativePatch() 返回旧 so + 新 spec，
        //   hook 规格相同时不影响；规格变了则下次重新安装覆盖。
        val specContent = hooks.joinToString("\n") { "${it.targetLib}|${it.targetSym}|${it.patchSym}" }
        val specTmp = File(dir, "${nativeSpec.name}.tmp")
        specTmp.delete()
        specTmp.writeText(specContent)
        nativeSpec.delete()
        check(specTmp.renameTo(nativeSpec)) { "atomic rename failed: $specTmp -> $nativeSpec" }
        write(input, so)
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

    // 写临时文件再 rename：dex 经 dexopt/setReadOnly 后只读，直接覆盖会 Permission denied；
    // 先写 .tmp 再 renameTo，进程崩溃不会丢失已有补丁（rename 是原子操作）。
    // input 由本方法消费并关闭。
    private fun write(input: InputStream, dest: File): File {
        val tmp = File(dest.parent, "${dest.name}.tmp")
        tmp.delete()
        try {
            input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        dest.delete()
        check(tmp.renameTo(dest)) { "atomic rename failed: $tmp -> $dest" }
        return dest
    }
}
