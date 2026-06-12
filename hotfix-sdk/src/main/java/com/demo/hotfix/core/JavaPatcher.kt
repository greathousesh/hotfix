package com.demo.hotfix.core

import android.content.Context
import android.os.Build
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Java/Kotlin 代码热修复（对应淘宝 InstantPatcher.handleHotSwapPatch）。
 *
 *  1. 独立 DexClassLoader 加载补丁 dex，parent = 宿主 ClassLoader（补丁可引用宿主类 / Kotlin stdlib）。
 *  2. 加载补丁里的 PatchesLoaderImpl，调 load() 拿"被修类(运行时名) -> override"映射。
 *  3. 校验 baseVersion 一致（保证混淆名对齐）。
 *  4. 反射把每个被修类的静态字段 $ipChange 设为 override 实例 —— 即时生效。
 */
object JavaPatcher {

    private const val TAG = "MiniHotfix"
    const val DEFAULT_LOADER_CLASS = "com.demo.patch.PatchesLoaderImpl"
    const val INJECT_FIELD = "\$ipChange"   // "\$" -> 字面量 $ipChange，与 ASM 注入字段同名

    fun apply(
        ctx: Context,
        patchDexPath: String,
        hostBaseVersion: String,
        loaderClass: String = DEFAULT_LOADER_CLASS,
    ): Boolean {
        val dex = File(patchDexPath)
        if (!dex.exists()) {
            Log.e(TAG, "patch dex not found: $patchDexPath")
            return false
        }
        // Android W^X：可写的 dex 会被 openDexFileNative 拒绝
        // (SecurityException: Writable dex file ... is not allowed)。加载前去掉写权限。
        if (dex.canWrite() && !dex.setReadOnly()) {
            Log.e(TAG, "cannot make patch dex read-only: $patchDexPath")
            return false
        }
        return try {
            // dexopt 输出目录：API<26 用 optimizedDirectory 做 odex；API>=26 该参数被忽略，用 code_cache。
            val optDir = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                File(ctx.filesDir, "hotfix_opt") else ctx.codeCacheDir
            if (!optDir.exists() && !optDir.mkdirs()) {
                Log.e(TAG, "mkdir opt failed: $optDir")
                return false
            }

            val hostCl = JavaPatcher::class.java.classLoader
            val patchCl = DexClassLoader(dex.absolutePath, optDir.absolutePath, null, hostCl)

            val loader = patchCl.loadClass(loaderClass)
                .getDeclaredConstructor().newInstance() as PatchesLoader

            if (hostBaseVersion != loader.baseVersion()) {
                Log.e(TAG, "patch miss match: host=$hostBaseVersion patch.base=${loader.baseVersion()}")
                return false
            }

            val patches = loader.load()
            Log.i(TAG, "patched classes = ${patches.keys}")

            for ((cls, impl) in patches) {
                try {
                    val target = Class.forName(cls, false, hostCl)   // 用宿主 CL 解析"运行时类名"
                    val f = target.getDeclaredField(INJECT_FIELD)
                    f.isAccessible = true
                    f.set(null, impl)
                    Log.i(TAG, "hot-swap installed -> $cls")
                } catch (e: ClassNotFoundException) {
                    // 补丁里新增的类（如被删除的 companion）在宿主中不存在，跳过即可；
                    // 该类的方法只会从 patch 内部的 static trampoline 调用，不需要 $ipChange 注入。
                    Log.w(TAG, "skip hot-swap for $cls: not in host (new class only in patch)")
                } catch (t: Throwable) {
                    Log.e(TAG, "hot-swap failed for $cls", t)
                    return false
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "apply java patch failed", t)
            false
        }
    }
}
