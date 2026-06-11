package com.demo.hotfix.core

import android.content.Context
import com.demo.hotfix.NativeHook
import java.io.File
import java.io.InputStream

/**
 * 补丁持久化：SDK 自行决定保存路径，对外只收数据流。
 *
 * 新补丁先写到 `hotfix/<kind>/pending/`，只有运行时 apply 成功后才提升为 `active/`。
 * 这样坏补丁不会覆盖上一份可用补丁，也不会在冷启动时反复应用失败。
 */
internal class PatchStore(ctx: Context) {

    private val dir = File(ctx.filesDir, "hotfix").apply { mkdirs() }
    private val legacyDex = File(dir, "patch.dex")
    private val legacyRes = File(dir, "patch_res.apk")
    private val legacySo = File(dir, "libpatch.so")
    private val legacyNativeSpec = File(dir, "native_hooks.txt")

    private val javaStore = TypedStore("java")
    private val resourceStore = TypedStore("resource")
    private val nativeStore = TypedStore("native")

    fun savePendingJava(input: InputStream): File =
        write(input, javaStore.pendingFile("patch.dex"))

    fun commitJava() = javaStore.promotePending()

    fun discardPendingJava() = javaStore.discardPending()

    fun disableJava() {
        javaStore.disableActive()
        legacyDex.delete()
    }

    fun savePendingResource(input: InputStream): File =
        write(input, resourceStore.pendingFile("patch_res.apk"))

    fun commitResource() = resourceStore.promotePending()

    fun discardPendingResource() = resourceStore.discardPending()

    fun disableResource() {
        resourceStore.disableActive()
        legacyRes.delete()
    }

    fun savePendingNative(input: InputStream, hooks: List<NativeHook>): File {
        nativeStore.discardPending()
        val so = nativeStore.pendingFile("libpatch.so")
        val nativeSpec = nativeStore.pendingFile("native_hooks.txt")
        write(input, so)
        val specContent = hooks.joinToString("\n") { "${it.targetLib}|${it.targetSym}|${it.patchSym}" }
        writeText(specContent, nativeSpec)
        return so
    }

    fun commitNative() = nativeStore.promotePending()

    fun discardPendingNative() = nativeStore.discardPending()

    fun disableNative() {
        nativeStore.disableActive()
        legacySo.delete()
        legacyNativeSpec.delete()
    }

    fun javaPatch(): File? =
        javaStore.activeFile("patch.dex").takeIf { it.exists() } ?: legacyDex.takeIf { it.exists() }

    fun resourcePatch(): File? =
        resourceStore.activeFile("patch_res.apk").takeIf { it.exists() } ?: legacyRes.takeIf { it.exists() }

    fun nativePatch(): Pair<File, List<NativeHook>>? {
        val so = nativeStore.activeFile("libpatch.so").takeIf { it.exists() } ?: legacySo.takeIf { it.exists() }
        val spec = nativeStore.activeFile("native_hooks.txt").takeIf { it.exists() }
            ?: legacyNativeSpec.takeIf { it.exists() }
        if (so == null || spec == null) return null
        val hooks = readHooks(spec)
        return if (hooks.isEmpty()) null else so to hooks
    }

    private fun readHooks(spec: File): List<NativeHook> =
        spec.readLines().mapNotNull { line ->
            line.split("|").takeIf { it.size == 3 }?.let { NativeHook(it[0], it[1], it[2]) }
        }

    private fun write(input: InputStream, dest: File): File {
        dest.parentFile?.mkdirs()
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

    private fun writeText(text: String, dest: File): File {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parent, "${dest.name}.tmp")
        tmp.delete()
        tmp.writeText(text)
        dest.delete()
        check(tmp.renameTo(dest)) { "atomic rename failed: $tmp -> $dest" }
        return dest
    }

    private inner class TypedStore(name: String) {
        private val root = File(dir, name)
        private val pending = File(root, "pending")
        private val active = File(root, "active")
        private val backup = File(root, "backup")
        private val disabled = File(root, "disabled")

        fun pendingFile(name: String): File = File(pending, name)

        fun activeFile(name: String): File = File(active, name)

        fun discardPending() {
            pending.deleteRecursively()
        }

        fun promotePending() {
            check(pending.isDirectory) { "pending patch not found: $pending" }
            backup.deleteRecursively()
            if (active.exists()) {
                check(active.renameTo(backup)) { "backup active patch failed: $active -> $backup" }
            }
            if (!pending.renameTo(active)) {
                active.deleteRecursively()
                if (backup.exists()) backup.renameTo(active)
                error("promote pending patch failed: $pending -> $active")
            }
            backup.deleteRecursively()
        }

        fun disableActive() {
            disabled.deleteRecursively()
            if (active.exists()) {
                check(active.renameTo(disabled)) { "disable active patch failed: $active -> $disabled" }
            }
            pending.deleteRecursively()
            backup.deleteRecursively()
        }
    }
}
