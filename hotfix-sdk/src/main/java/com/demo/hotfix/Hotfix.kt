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
 * 网络下载/任意来源）交进来，SDK 自己决定落盘路径（内部沙盒 [PatchStore]）、立即应用、并持久化；
 * 下次冷启动 [init] 时自动重新应用已安装的补丁。
 *
 * ## 线程模型
 * - [init] **必须**在主线程调用（Application/Activity.onCreate）。
 * - `install*` 必须在**后台线程**调用：InputStream 读取（可能是网络流）在调用线程完成，
 *   而真正的补丁应用（反射热替换 / AssetManager 重建）则 marshal 到主线程执行。
 *   从主线程调用 `install*` 不会死锁，但 InputStream 读取会阻塞主线程，且主线程上直接
 *   执行补丁应用会让所有 `onMain {}` 退化为同步串行，影响响应性。
 */
object Hotfix {

    private const val TAG = "MiniHotfix"

    @Volatile private var initialized = false
    private lateinit var app: Application
    private lateinit var store: PatchStore
    private lateinit var baseVersion: String
    private lateinit var loaderClass: String

    /**
     * 初始化；并自动应用已持久化的补丁（冷启动续用）。
     *
     * **必须在主线程调用**（Application/Activity onCreate）。重复调用视为 no-op，不会重复初始化。
     *
     * @param loaderClass 补丁加载器类名，须与 hotfixPatch { loaderClass } 配置一致，
     *                    默认 [JavaPatcher.DEFAULT_LOADER_CLASS]。
     */
    fun init(
        app: Application,
        baseVersion: String,
        loaderClass: String = JavaPatcher.DEFAULT_LOADER_CLASS,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Hotfix.init() must be called on the main thread"
        }
        if (initialized) return   // 防止重复 init 覆盖状态
        this.app = app
        this.baseVersion = baseVersion
        this.loaderClass = loaderClass
        this.store = PatchStore(app)
        initialized = true
        // init 在主线程调用，applyPersisted 直接执行（ResourcePatcher 需要主线程）
        applyPersisted()
    }

    /** 安装代码补丁：保存 dex 到 SDK 路径 + 立即热修（InstantPatch 式 $ipChange 重定向）。 */
    fun installJavaPatch(input: InputStream): Boolean {
        ensureInit()
        warnIfMainThread("installJavaPatch")
        val dex = store.savePendingJava(input)
        return try {
            val ok = onMain { JavaPatcher.apply(app, dex.absolutePath, baseVersion, loaderClass) }
            if (ok) store.commitJava() else store.discardPendingJava()
            ok
        } catch (t: Throwable) {
            store.discardPendingJava()
            Log.e(TAG, "install java patch failed", t)
            false
        }
    }

    /** 安装资源补丁：保存 apk 到 SDK 路径 + 立即换资源（MonkeyPatcher 式 AssetManager 替换）。 */
    fun installResourcePatch(input: InputStream): Boolean {
        ensureInit()
        warnIfMainThread("installResourcePatch")
        val apk = store.savePendingResource(input)
        return try {
            val ok = onMain { ResourcePatcher.apply(app, apk.absolutePath) }
            if (ok) store.commitResource() else store.discardPendingResource()
            ok
        } catch (t: Throwable) {
            store.discardPendingResource()
            Log.e(TAG, "install resource patch failed", t)
            false
        }
    }

    /** 安装 Native 补丁：保存 so + 记录 hook 规格 + 立即内联 hook。hook 规格也被持久化供冷启动续用。 */
    fun installNativePatch(input: InputStream, hooks: List<NativeHook>): Boolean {
        ensureInit()
        warnIfMainThread("installNativePatch")
        val so = store.savePendingNative(input, hooks)
        return try {
            val ok = onMain { applyNative(so.absolutePath, hooks) }
            if (ok) store.commitNative() else store.discardPendingNative()
            ok
        } catch (t: Throwable) {
            store.discardPendingNative()
            Log.e(TAG, "install native patch failed", t)
            false
        }
    }

    private fun ensureInit() =
        check(initialized) { "请先调用 Hotfix.init(app, baseVersion) 再安装补丁" }

    private fun warnIfMainThread(name: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "$name() called on main thread — move to a background thread to avoid blocking UI")
        }
    }

    private fun applyNative(soPath: String, hooks: List<NativeHook>): Boolean {
        if (hooks.isEmpty()) return false
        var ok = true
        for (h in hooks) ok = NativeHotfix.apply(h.targetLib, h.targetSym, soPath, h.patchSym) && ok
        return ok
    }

    /**
     * 冷启动续用：把已持久化的补丁重新应用。
     * 调用者（[init]）保证此方法运行在主线程，故 ResourcePatcher 可直接调用，无需 onMain。
     */
    private fun applyPersisted() {
        if (store.hasStaleJavaApply()) {
            Log.e(TAG, "disable persisted java patch after previous apply crash")
            store.disableJava()
        }
        store.javaPatch()?.let {
            Log.i(TAG, "auto-apply persisted java patch")
            store.beginJavaApply()
            val ok = runCatching {
                JavaPatcher.apply(app, it.absolutePath, baseVersion, loaderClass)
            }.getOrDefault(false)
            if (ok) {
                store.finishJavaApply()
            } else {
                Log.e(TAG, "disable persisted java patch after apply failure")
                store.disableJava()
            }
        }
        if (store.hasStaleResourceApply()) {
            Log.e(TAG, "disable persisted resource patch after previous apply crash")
            store.disableResource()
        }
        store.resourcePatch()?.let {
            Log.i(TAG, "auto-apply persisted resource patch")
            store.beginResourceApply()
            val ok = runCatching {
                ResourcePatcher.apply(app, it.absolutePath)
            }.getOrDefault(false)
            if (ok) {
                store.finishResourceApply()
            } else {
                Log.e(TAG, "disable persisted resource patch after apply failure")
                store.disableResource()
            }
        }
        if (store.hasStaleNativeApply()) {
            Log.e(TAG, "disable persisted native patch after previous apply crash")
            store.disableNative()
        }
        store.nativePatch()?.let { (so, hooks) ->
            Log.i(TAG, "auto-apply persisted native patch")
            store.beginNativeApply()
            val ok = runCatching {
                applyNative(so.absolutePath, hooks)
            }.getOrDefault(false)
            if (ok) {
                store.finishNativeApply()
            } else {
                Log.e(TAG, "disable persisted native patch after apply failure")
                store.disableNative()
            }
        }
    }

    /** 在主线程执行 block 并返回结果；已在主线程则直接执行（不 post，避免语义混乱）。 */
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
