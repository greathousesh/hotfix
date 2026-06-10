package com.demo.hotfix.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 热修复插桩插件（自动注入 $ipChange + 方法头转发）。
 * Kotlin 版：transformClassesWith 的参数闭包是 (None)->Unit，尾随空 lambda 即可，无 Java 的 Function1 样板。
 */
class HotfixPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        components.onVariants(components.selector().all()) { variant ->
            variant.instrumentation.transformClassesWith(
                HotfixClassVisitorFactory::class.java,
                InstrumentationScope.PROJECT
            ) { /* InstrumentationParameters.None：无参数 */ }
            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
            )
        }
    }
}
