package com.demo.hotfix.res

import android.app.Application
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import java.lang.ref.Reference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 资源热修复（对应淘宝 MonkeyPatcher.monkeyPatchExistingResources）。
 *
 * 关键点（Android 7+ / 含 Android 16 实测）：
 *  1. 新建 AssetManager 时必须**先加宿主原始 apk 路径、再加补丁包**——补丁后加，
 *     同一资源 id 由补丁覆盖；同时保留全部宿主资源（否则布局 inflate 会崩）。
 *     旧实现只 addAssetPath(补丁) -> 新 AssetManager 只有补丁那几个资源，换上去要么崩、
 *     要么根本没换到 Activity 实际用的 Resources（本 demo 在 Android 16 上即后者）。
 *  2. 把新 AssetManager 注入进程内**所有 ResourcesImpl**（N+ 的真正持有者），
 *     而不仅是 Resources 包装层。ResourcesManager 里同时遍历
 *     mResourceImpls(ResourcesImpl 缓存) 和 mResourceReferences(Resources 列表)。
 *  3. 补丁包的资源 id 必须与宿主一致（打包流水线 linkPatchRes 用 --stable-ids 锁定）。
 */
object ResourcePatcher {

    private const val TAG = "MiniHotfix"

    // apply() 始终在主线程执行（由 Hotfix.onMain() 保证），无并发写入，lazy 安全。
    private val addAssetPath: Method by lazy {
        AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }
    }
    private val ensureStringBlocks: Method? by lazy {
        try { AssetManager::class.java.getDeclaredMethod("ensureStringBlocks").apply { isAccessible = true } }
        catch (ignore: Throwable) { null }
    }
    private val cachedResourcesManager: Any? by lazy { resourcesManager() }
    private val mResourcesImplField: Field? by lazy {
        try { Resources::class.java.getDeclaredField("mResourcesImpl").apply { isAccessible = true } }
        catch (ignore: Throwable) { null }
    }
    // 通用字段缓存：Class+名 -> Field；替代每次 getDeclaredField 的重复反射。
    private val fieldCache = HashMap<Pair<Class<*>, String>, Field?>()
    private fun cachedField(cls: Class<*>, name: String): Field? =
        fieldCache.getOrPut(cls to name) {
            try { cls.getDeclaredField(name).apply { isAccessible = true } } catch (ignore: Throwable) { null }
        }

    fun apply(app: Application, patchResPath: String): Boolean {
        return try {

            // 1. 新 AssetManager：先补丁、后宿主原始路径。
            //    ★ 顺序关键（Android 7+ / AssetManager2）：补丁包与宿主同 package(com.demo.app, id 0x7f)，
            //    会被合并进同一个 PackageGroup。AssetManager2::FindEntryInternal 在 config 相等时用
            //    isBetterThan() 严格比较 —— 只有「严格更优」或 RRO overlay 才覆盖 best，相等不覆盖，
            //    于是**最先加载**的包胜出（这与 N 以前的旧 ResTable「后加覆盖」相反，是常见踩坑点）。
            //    所以补丁必须先于宿主加入：补丁定义的同名 id(如 patch_text) 胜出；
            //    补丁未定义的 id(layout/其它 string) 在补丁包里查不到，自然回退到宿主包，照常解析。
            val newAssets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val patchCookie = addAssetPath.invoke(newAssets, patchResPath) as Int
            if (patchCookie == 0) {
                Log.e(TAG, "addAssetPath patch failed: $patchResPath")
                return false
            }
            Log.i(TAG, "addAssetPath patch: $patchResPath -> cookie=$patchCookie")
            val originalPaths = collectOriginalApkPaths(app)
            for (p in originalPaths) {
                val c = addAssetPath.invoke(newAssets, p) as Int
                Log.i(TAG, "addAssetPath host: $p -> cookie=$c")
            }
            runCatching { ensureStringBlocks?.invoke(newAssets) }

            // 2. 注入进程内所有 ResourcesImpl + Resources
            var implCount = 0
            for (impl in collectResourcesImpls()) {
                if (setImplAssets(impl, newAssets)) implCount++
            }
            var resCount = 0
            for (resRef in collectResourceReferences()) {
                val res = resRef() ?: continue
                if (replaceAssetManager(res, newAssets)) resCount++
                runCatching { res.updateConfiguration(res.configuration, res.displayMetrics) }
            }
            // 兜底：Application 自己的 Resources
            runCatching {
                replaceAssetManager(app.resources, newAssets)
                app.resources.updateConfiguration(
                    app.resources.configuration, app.resources.displayMetrics
                )
            }
            Log.i(TAG, "resource patch applied: impls=$implCount res=$resCount path=$patchResPath")
            implCount > 0 || resCount > 0
        } catch (t: Throwable) {
            Log.e(TAG, "apply resource patch failed", t)
            false
        }
    }

    /** 宿主已加载的 apk 路径：base.apk + splits + 共享库。 */
    private fun collectOriginalApkPaths(app: Application): List<String> {
        val ai = app.applicationInfo
        val paths = LinkedHashSet<String>()
        ai.sourceDir?.let { paths.add(it) }
        ai.splitSourceDirs?.forEach { paths.add(it) }
        ai.sharedLibraryFiles?.forEach { paths.add(it) }
        return paths.toList()
    }

    /** 把 ResourcesImpl.mAssets 设为新 AssetManager。 */
    private fun setImplAssets(impl: Any, newAssets: AssetManager): Boolean {
        val f = cachedField(impl.javaClass, "mAssets") ?: return false
        return try { f.set(impl, newAssets); true } catch (t: Throwable) { false }
    }

    /** N+：Resources -> mResourcesImpl -> mAssets；老版本：Resources.mAssets。 */
    private fun replaceAssetManager(res: Resources, newAssets: AssetManager): Boolean {
        val impl = mResourcesImplField?.get(res)
        if (impl != null) return setImplAssets(impl, newAssets)
        return try {
            cachedField(Resources::class.java, "mAssets")?.set(res, newAssets)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 从 ResourcesManager.mResourceImpls 拿到所有 ResourcesImpl（N+ 真正持有 AssetManager 的对象）。 */
    @Suppress("UNCHECKED_CAST")
    private fun collectResourcesImpls(): List<Any> {
        val out = ArrayList<Any>()
        try {
            val rm = cachedResourcesManager ?: return out
            val f = cachedField(rm.javaClass, "mResourceImpls") ?: return out
            when (val impls = f.get(rm)) {
                is Map<*, *> -> impls.values.forEach { o ->
                    (o as? Reference<*>)?.get()?.let { out.add(it) }
                }
            }
        } catch (ignore: Throwable) {
        }
        return out
    }

    /** 从 ResourcesManager 拿到所有 Resources 的取值器列表。 */
    @Suppress("UNCHECKED_CAST")
    private fun collectResourceReferences(): List<() -> Resources?> {
        val out = ArrayList<() -> Resources?>()
        val rm = cachedResourcesManager ?: return out
        val refField = cachedField(rm.javaClass, "mResourceReferences")
            ?: cachedField(rm.javaClass, "mActiveResources")
            ?: return out

        when (val refs = refField.get(rm)) {
            is Collection<*> -> refs.forEach { o ->
                val wr = o as Reference<Resources>
                out.add { wr.get() }
            }
            is Map<*, *> -> refs.values.forEach { o ->
                val wr = o as Reference<Resources>
                out.add { wr.get() }
            }
        }
        return out
    }

    private fun resourcesManager(): Any? = try {
        val rmClz = Class.forName("android.app.ResourcesManager")
        rmClz.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
    } catch (t: Throwable) {
        null
    }
}
