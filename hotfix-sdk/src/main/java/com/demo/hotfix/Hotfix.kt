package com.demo.hotfix

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.demo.hotfix.core.JavaPatcher
import com.demo.hotfix.core.PatchStore
import com.demo.hotfix.nativehook.NativeHotfix
import com.demo.hotfix.res.ResourcePatcher
import java.io.InputStream
import java.util.concurrent.CountDownLatch

/**
 * 热修复 SDK 门面：Java/Kotlin 代码、资源、Native 三种能力收口。
 *
 * 对外只暴露**数据流**接口（`installXxxPatch(InputStream)`）：调用方只管把补丁字节流（本地文件/
 * 网络下载/任意来源）交进来，SDK 自己决定落盘路径(内部沙盒 [PatchStore])、立即应用、并持久化；
 * 下次冷启动 [init] 时自动重新应用已安装的补丁。
 *
 * 线程模型：保存(可能是网络流的读取)在调用线程完成 —— 所以 install* 请在**后台线程**调用；
 * 真正的应用(反射热替换 / 资源 AssetManager 重建)统一 marshal 到**主线程**执行。
 */
object Hotfix {

    private const val TAG = "MiniHotfix"

    private lateinit var app: Application
    private var baseVersion: String = "unknown"
    private lateinit var store: PatchStore

    /** 初始化；并自动应用已持久化的补丁（冷启动续用）。需在主线程调用（如 Activity/Application onCreate）。 */
    fun init(app: Application, baseVersion: String) {
        this.app = app
        this.baseVersion = baseVersion
        this.store = PatchStore(app)
        applyPersisted()
    }

    /** 安装代码补丁：保存 dex 到 SDK 路径 + 立即热修（InstantPatch 式 $ipChange 重定向）。 */
    fun installJavaPatch(input: InputStream): Boolean {
        ensureInit()
        val dex = store.saveJava(input)
        return onMain { JavaPatcher.apply(app, dex.absolutePath, baseVersion) }
    }

    /** 安装资源补丁：保存 apk 到 SDK 路径 + 立即换资源（MonkeyPatcher 式 AssetManager 替换）。 */
    fun installResourcePatch(input: InputStream): Boolean {
        ensureInit()
        val apk = store.saveResource(input)
        return onMain { ResourcePatcher.apply(app, apk.absolutePath) }
    }

    /** 安装 Native 补丁：保存 so + 记录 hook 规格 + 立即内联 hook。hook 规格也被持久化供冷启动续用。 */
    fun installNativePatch(input: InputStream, hooks: List<NativeHook>): Boolean {
        ensureInit()
        val so = store.saveNative(input, hooks)
        return onMain { applyNative(so.absolutePath, hooks) }
    }

    private fun ensureInit() =
        check(this::store.isInitialized) { "请先调用 Hotfix.init(app, baseVersion) 再安装补丁" }

    private fun applyNative(soPath: String, hooks: List<NativeHook>): Boolean {
        if (hooks.isEmpty()) return false
        var ok = true
        for (h in hooks) ok = NativeHotfix.apply(h.targetLib, h.targetSym, soPath, h.patchSym) && ok
        return ok
    }

    /** 冷启动续用：把已持久化的补丁重新应用。运行在主线程、仅读本地文件（无网络）。 */
    private fun applyPersisted() {
        store.javaPatch()?.let {
            Log.i(TAG, "auto-apply persisted java patch")
            JavaPatcher.apply(app, it.absolutePath, baseVersion)
        }
        store.resourcePatch()?.let {
            Log.i(TAG, "auto-apply persisted resource patch")
            ResourcePatcher.apply(app, it.absolutePath)
        }
        store.nativePatch()?.let { (so, hooks) ->
            Log.i(TAG, "auto-apply persisted native patch")
            applyNative(so.absolutePath, hooks)
        }
    }

    /** 在主线程执行 block 并返回结果；已在主线程则直接执行。 */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        Handler(Looper.getMainLooper()).post {
            result = runCatching(block)
            latch.countDown()
        }
        latch.await()
        return result!!.getOrThrow()
    }
}
