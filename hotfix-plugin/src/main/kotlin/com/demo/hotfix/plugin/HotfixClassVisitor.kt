package com.demo.hotfix.plugin

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * 注入静态字段 $ipChange（若不存在），并为每个可插桩方法包一层 HotfixMethodVisitor。
 */
internal class HotfixClassVisitor(api: Int, cv: ClassVisitor) : ClassVisitor(api, cv) {

    companion object {
        const val FIELD = "\$ipChange"
        const val IPCHANGE_DESC = "Lcom/demo/hotfix/core/IpChange;"
    }

    private var owner = ""
    private var isInterface = false
    private var hasField = false

    override fun visit(
        version: Int, access: Int, name: String,
        signature: String?, superName: String?, interfaces: Array<String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        owner = name
        isInterface = (access and Opcodes.ACC_INTERFACE) != 0
    }

    override fun visitField(
        access: Int, name: String, descriptor: String, signature: String?, value: Any?
    ): FieldVisitor? {
        if (FIELD == name) hasField = true
        return super.visitField(access, name, descriptor, signature, value)
    }

    override fun visitMethod(
        access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        if (isInterface) return mv
        val skip = Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE
        if (access and skip != 0) return mv
        if (name == "<init>" || name == "<clinit>") return mv
        return HotfixMethodVisitor(api, mv, access, name, descriptor, owner)
    }

    override fun visitEnd() {
        if (!isInterface && !hasField) {
            super.visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_VOLATILE,
                FIELD, IPCHANGE_DESC, null, null
            )?.visitEnd()
        }
        super.visitEnd()
    }
}
