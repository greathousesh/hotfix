package com.demo.hotfix.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor

/** 决定哪些类要插桩，并为其创建 ClassVisitor。abstract——其余成员由 AGP 注入。 */
abstract class HotfixClassVisitorFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        val api = instrumentationContext.apiVersion.get()
        return HotfixClassVisitor(api, nextClassVisitor)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val name = classData.className
        if (!name.startsWith("com.demo.app")) return false           // demo：只插业务包
        if (name.endsWith(".R") || name.contains(".R$") || name.endsWith(".BuildConfig")) return false
        return true
    }
}
