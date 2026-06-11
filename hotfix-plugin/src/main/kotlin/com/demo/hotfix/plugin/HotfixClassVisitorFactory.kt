package com.demo.hotfix.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import org.objectweb.asm.ClassVisitor

/** 决定哪些类要插桩，并为其创建 ClassVisitor。abstract——其余成员由 AGP 注入。 */
abstract class HotfixClassVisitorFactory :
    AsmClassVisitorFactory<HotfixInstrumentParams> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        val api = instrumentationContext.apiVersion.get()
        return HotfixClassVisitor(api, nextClassVisitor)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val pkgs = parameters.get().instrumentPackages.get()
        if (pkgs.isEmpty()) return false
        val name = classData.className
        if (!pkgs.any { name == it || name.startsWith("$it.") }) return false
        if (name.endsWith(".R") || name.contains(".R$") || name.endsWith(".BuildConfig")) return false
        return true
    }
}
