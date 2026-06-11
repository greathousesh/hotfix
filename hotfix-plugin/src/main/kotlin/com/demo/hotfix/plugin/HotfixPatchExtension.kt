package com.demo.hotfix.plugin

/**
 * `com.demo.hotfix.patch` 插件的 Gradle DSL 扩展。
 *
 * 用法（patch-src/build.gradle）：
 * ```groovy
 * hotfixPatch {
 *     // baseVersion 默认自动从根项目 :app 的 versionName 读取，通常不需要手动设置
 *     // baseVersion = '2.0.0'
 *
 *     // loaderClass 默认 'com.demo.patch.PatchesLoaderImpl'，与 JavaPatcher 约定对齐
 *     // loaderClass = 'com.mycompany.patch.PatchesLoaderImpl'
 *
 *     // sdkPackage 默认 'com.demo.hotfix'，换包名发布 SDK 时修改
 *     // sdkPackage = 'com.mycompany.hotfix'
 * }
 * ```
 */
open class HotfixPatchExtension {
    /**
     * 补丁针对的 base 版本，须与宿主 [com.demo.hotfix.Hotfix.init] 传入的值一致。
     * null（默认）= 自动从根项目 :app 的 android.defaultConfig.versionName 读取。
     */
    var baseVersion: String? = null

    /**
     * 补丁加载器的完全限定类名，须与 [com.demo.hotfix.core.JavaPatcher] 约定的值一致。
     * 通常不需要修改；只在接入方需要自定义包名时设置。
     */
    var loaderClass: String = DEFAULT_LOADER_CLASS

    /**
     * hotfix-sdk 的根包名。[PatchOverrideGenerator] 用它推导 IpChange / PatchesLoader 的内部类名。
     * 换包名发布 SDK 时修改此值，无需改插件源码。
     */
    var sdkPackage: String = DEFAULT_SDK_PACKAGE

    companion object {
        const val DEFAULT_LOADER_CLASS = "com.demo.patch.PatchesLoaderImpl"
        const val DEFAULT_SDK_PACKAGE  = "com.demo.hotfix"
    }
}
