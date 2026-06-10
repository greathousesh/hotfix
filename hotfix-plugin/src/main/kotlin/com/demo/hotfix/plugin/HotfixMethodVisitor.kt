package com.demo.hotfix.plugin

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.Method

/**
 * 在方法体最前面注入：
 * ```
 * val ip = Owner.$ipChange
 * if (ip != null) {
 *     val r = ip.ipcDispatch("Owner.name(desc)", arrayOf(this?, arg0, ...))
 *     return (拆箱/强转) r            // void 则直接 return
 * }
 * // 原方法体...
 * ```
 */
internal class HotfixMethodVisitor(
    api: Int,
    mv: MethodVisitor,
    access: Int,
    private val mName: String,
    private val mDesc: String,
    private val owner: String
) : AdviceAdapter(api, mv, access, mName, mDesc) {

    private val isStaticMethod = (access and Opcodes.ACC_STATIC) != 0

    companion object {
        private val IPCHANGE = Type.getObjectType("com/demo/hotfix/core/IpChange")
        private val OBJECT = Type.getType(Any::class.java)
        // 方法名 ipcDispatch（不带 $，Kotlin 可声明）
        private val IPC_DISPATCH =
            Method("ipcDispatch", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;")
    }

    override fun onMethodEnter() {
        // val ip = Owner.$ipChange
        getStatic(Type.getObjectType(owner), HotfixClassVisitor.FIELD, IPCHANGE)
        val ipLocal = newLocal(IPCHANGE)
        storeLocal(ipLocal)

        // if (ip == null) goto original
        loadLocal(ipLocal)
        val original = newLabel()
        ifNull(original)

        // ip.ipcDispatch(methodId, arrayOf(...))
        loadLocal(ipLocal)
        push(methodId(owner, mName, mDesc))   // 共享格式，须与 PatchOverrideGenerator 一致

        val args = argumentTypes
        val slots = args.size + if (isStaticMethod) 0 else 1
        push(slots)
        newArray(OBJECT)
        var idx = 0
        if (!isStaticMethod) {
            dup(); push(idx); loadThis(); arrayStore(OBJECT); idx++
        }
        for (i in args.indices) {
            dup(); push(idx); loadArg(i); box(args[i]); arrayStore(OBJECT); idx++
        }
        invokeInterface(IPCHANGE, IPC_DISPATCH)

        // 按返回类型处理并 return
        val ret = returnType
        if (ret.sort == Type.VOID) pop() else unbox(ret)
        returnValue()

        mark(original)
    }
}
