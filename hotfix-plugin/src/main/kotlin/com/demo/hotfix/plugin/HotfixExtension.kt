package com.demo.hotfix.plugin

/**
 * `com.demo.hotfix.instrument` 插件的 Gradle DSL 扩展。
 *
 * 用法（app/build.gradle）：
 * ```groovy
 * hotfix {
 *     instrumentPackages = ['com.mycompany.app']
 * }
 * ```
 */
open class HotfixExtension {
    /** 需要插桩的包名前缀列表。只有 className.startsWith(pkg) 的类才会被注入热修复分支。 */
    var instrumentPackages: List<String> = emptyList()
}
