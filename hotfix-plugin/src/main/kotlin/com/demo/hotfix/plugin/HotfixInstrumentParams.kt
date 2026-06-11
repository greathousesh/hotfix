package com.demo.hotfix.plugin

import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input

/**
 * 传递给 [HotfixClassVisitorFactory] 的 Gradle Worker 参数。
 * 通过 AGP Instrumentation API 跨进程序列化，必须是接口 + @Input 注解。
 */
interface HotfixInstrumentParams : InstrumentationParameters {
    /** 需要被插桩的包名前缀列表，如 ["com.mycompany.app"]。空列表 = 不插桩任何类。 */
    @get:Input
    val instrumentPackages: ListProperty<String>
}
