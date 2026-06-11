package com.demo.hotfix.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 热修复插桩插件（自动注入 $ipChange + 方法头转发）。
 *
 * 接入方须在 build.gradle 声明要插桩的包名，否则不会对任何类插桩：
 * ```groovy
 * hotfix {
 *     instrumentPackages = ['com.mycompany.app']
 * }
 * ```
 */
class HotfixPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("hotfix", HotfixExtension::class.java)
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        components.onVariants(components.selector().all()) { variant ->
            variant.instrumentation.transformClassesWith(
                HotfixClassVisitorFactory::class.java,
                InstrumentationScope.PROJECT
            ) { params ->
                // afterEvaluate 之后才能读到 ext.instrumentPackages，用 provider 延迟求值
                params.instrumentPackages.set(project.provider { ext.instrumentPackages })
            }
            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
            )
        }
    }
}
