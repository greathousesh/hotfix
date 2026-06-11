package com.demo.hotfix.plugin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.MethodRemapper
import org.objectweb.asm.commons.Method as AsmMethod
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode
import java.io.File

/**
 * 从「开发者写好的修复版原始类」字节码，一次性生成补丁 dex 里需要的**全部**类（InstantPatch `$override` 风格）：
 *   1) 每个被修类 -> 一个分发类 `com.demo.patch.<Simple>Patch`（实现 IpChange）
 *   2) 一个 `com.demo.patch.PatchesLoaderImpl`（实现 PatchesLoader），把「运行时类名 -> new <Simple>Patch()」装进 Map
 *
 * 于是开发者唯一要写的就是 `patched/` 下修好的原始类，其余补丁代码全部由字节码生成、零手写样板。
 *
 * 为什么必须生成异名分发类、而不能直接发布修好的原始类：宿主进程已加载 `com.demo.app.Calculator`，
 * 补丁 DexClassLoader(parent=宿主) 是 parent-first，补丁 dex 里同名类会被宿主版本屏蔽、永远加载不到。
 * 所以把每个原方法转成 static 跳板（实例作首参），由宿主插桩的方法头 `ipcDispatch(...)` 转进来执行修好的逻辑。
 *
 * methodId 与 HotfixMethodVisitor 注入格式严格对齐：`owner以点分隔 + "." + 方法名 + 描述符`（如
 * `com.demo.app.Calculator.add(II)I`），约定 args[0]=this（实例方法），其后为实参（基本类型已装箱）。
 *
 * 由 HotfixPatchPlugin 的 generatePatchClasses{V} 任务在 Gradle daemon 进程内直接调用 [generate]。
 * mappingFile 用于把被修类解析成运行时(R8 混淆)类名；debug/无混淆传 null 则用原始名。
 */
object PatchOverrideGenerator {

    private val OBJECT_T  = Type.getType(Any::class.java)
    private val MAP_T     = Type.getType("Ljava/util/Map;")
    private val HASHMAP_T = Type.getType("Ljava/util/HashMap;")

    // 生成配置：收口所有外部参数，避免逐层透传
    data class Config(
        val baseVersion: String,
        val mappingFile: File?,
        val loaderClass: String = HotfixPatchExtension.DEFAULT_LOADER_CLASS,
        val sdkPackage:  String = HotfixPatchExtension.DEFAULT_SDK_PACKAGE,
    ) {
        val loaderInternal: String = loaderClass.replace('.', '/')
        val patchPkg:       String = loaderInternal.substringBeforeLast('/')
        val ipChange:       String = "${sdkPackage.replace('.', '/')}/core/IpChange"
        val patchesLoader:  String = "${sdkPackage.replace('.', '/')}/core/PatchesLoader"
    }

    fun generate(inDir: File, outDir: File, config: Config) {
        val classNodes = inDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { cf ->
                ClassNode().also { ClassReader(cf.readBytes()).accept(it, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) }
            }
            .toList()
        require(classNodes.isNotEmpty()) { "no .class found under $inDir" }

        // mapping.txt 一次性解析类名 + 成员名重映射；无 mapping(debug) 则均为空表。
        val (classMapping, memberMapping) = parseMappings(config.mappingFile)
        // 可热修类判定：非 synthetic、非接口，且末段 $-segment 含非数字字符。
        //   ACC_SYNTHETIC  → 过滤 Java 匿名类 / Kotlin lambda / WhenMappings 等编译器合成类
        //   末段全数字      → 过滤 Kotlin object 表达式（如 Calculator$add$fixed$1）——
        //                    这类类不带 ACC_SYNTHETIC，但末段固定是纯数字序号。
        // 保留：顶层类（无 $）、$Companion、$DefaultImpls、命名内部类。
        val topClasses = classNodes.filter { cn ->
            cn.access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ANNOTATION) == 0 &&
                cn.name.substringAfterLast('$').any { !it.isDigit() }
        }
        require(topClasses.isNotEmpty()) { "no patchable class found under $inDir" }

        val remapNames = buildClassRemap(classNodes, topClasses, classMapping, config)
        val remapper = object : Remapper() {
            override fun map(internalName: String) =
                remapNames[internalName] ?: internalName
            // R8 会重命名字段（如 Companion -> a）和方法，必须一并重写，否则 GETSTATIC/INVOKEVIRTUAL 引用已不存在的名字。
            override fun mapFieldName(owner: String, name: String, descriptor: String) =
                memberMapping["$owner\n$name\n$descriptor"] ?: name
            override fun mapMethodName(owner: String, name: String, descriptor: String) =
                memberMapping["$owner\n$name\n$descriptor"] ?: name
        }

        // 预先建立跨补丁调用重定向表：补丁方法体中若调用了「同批次也被修」的方法（R8 可能已将其内联
        // 导致宿主里不再存在该虚方法），跳板必须直接调用对应分发类的 static 跳板，而不能走宿主虚方法。
        val crossCallMap = buildCrossCallMap(topClasses, classMapping, config)

        // 运行时类名 -> 分发类内部名（供 PatchesLoaderImpl 装 Map）
        val loaderEntries = LinkedHashMap<String, String>()
        for (cn in topClasses) {
            val origDot    = cn.name.replace('/', '.')
            val runtimeName = classMapping[origDot] ?: origDot
            val (dispName, bytes) = genDispatcher(cn, runtimeName, config, remapper, crossCallMap)
            write(outDir, dispName, bytes)
            loaderEntries[runtimeName] = dispName
            println("[PatchOverrideGenerator] ${cn.name} -> $dispName  (运行时 key=$runtimeName, ${bytes.size}B)")
        }
        for (cn in classNodes - topClasses.toSet()) {
            val (helperName, helperBytes) = genHelper(cn, remapper)
            write(outDir, helperName, helperBytes)
            println("[PatchOverrideGenerator] helper ${cn.name} -> $helperName  (${helperBytes.size}B)")
        }

        val loaderBytes = genLoader(loaderEntries, config)
        write(outDir, config.loaderInternal, loaderBytes)
        println("[PatchOverrideGenerator] ${config.loaderInternal}  (baseVersion=${config.baseVersion}, ${loaderEntries.size} 条映射)")
    }

    // 保留旧签名作为便捷入口，内部构造 Config 后转发
    fun generate(
        inDir: File,
        outDir: File,
        baseVersion: String,
        mappingFile: File?,
        loaderClass: String = HotfixPatchExtension.DEFAULT_LOADER_CLASS,
        sdkPackage:  String = HotfixPatchExtension.DEFAULT_SDK_PACKAGE,
    ) = generate(inDir, outDir, Config(baseVersion, mappingFile, loaderClass, sdkPackage))

    private data class Mappings(
        val classes: Map<String, String>,   // orig dot → runtime dot
        val members: Map<String, String>,   // "ownerInternal\nname\ndescriptor" → renamed name
    )

    /**
     * 解析 R8/ProGuard mapping.txt：
     *  - 顶格类行 `com.Foo -> a.b:` → [Mappings.classes]
     *  - 缩进成员行（字段和方法）→ [Mappings.members]，key = "ownerInternal\nname\ndesc"
     *
     * 字段示例：`    com.Foo$Companion Companion -> a`
     * 方法示例：`    3:5:int add(int,int):6:8 -> b`（行号前缀/后缀会被剥除）
     * 内联方法行（name 含 `.`，如 `com.Other.foo`）跳过——它们是宿主行号信息，非当前类成员。
     */
    private fun parseMappings(mappingFile: File?): Mappings {
        if (mappingFile == null || !mappingFile.isFile) return Mappings(emptyMap(), emptyMap())
        val classes = HashMap<String, String>()
        val members = HashMap<String, String>()
        var curOwner = ""   // 当前正在解析的类（internal 格式）
        for (rawLine in mappingFile.readLines()) {
            val line = rawLine.trimEnd()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (!line[0].isWhitespace()) {
                // 类行：com.demo.app.Foo -> a.b:
                if (!line.endsWith(":")) continue
                val arrow = line.indexOf(" -> ")
                if (arrow < 0) continue
                val orig    = line.substring(0, arrow)
                val renamed = line.substring(arrow + 4, line.length - 1)
                classes[orig] = renamed
                curOwner = orig.replace('.', '/')
            } else {
                // 成员行
                if (curOwner.isEmpty()) continue
                val trimmed = line.trimStart()
                if (trimmed.startsWith("#")) continue
                val arrowIdx = trimmed.lastIndexOf(" -> ")
                if (arrowIdx < 0) continue
                val renamedMember = trimmed.substring(arrowIdx + 4)

                // 剥除行号前缀 "N:N:"
                var sig = trimmed.substring(0, arrowIdx)
                val firstColon = sig.indexOf(':')
                if (firstColon >= 0 && sig.substring(0, firstColon).all { it.isDigit() }) {
                    val secondColon = sig.indexOf(':', firstColon + 1)
                    if (secondColon >= 0) sig = sig.substring(secondColon + 1)
                }
                sig = sig.trim()
                // 剥除行号后缀 ":N:N"
                sig = sig.replace(Regex(":\\d+:\\d+$"), "").trimEnd()

                val parenIdx = sig.indexOf('(')
                if (parenIdx < 0) {
                    // 字段行：<type> <name>
                    val sp = sig.lastIndexOf(' ')
                    if (sp < 0) continue
                    val name = sig.substring(sp + 1).trim()
                    if (name.contains('.')) continue        // 跳过内联引用
                    val desc = typeToDescriptor(sig.substring(0, sp).trim())
                    members["$curOwner\n$name\n$desc"] = renamedMember
                } else {
                    // 方法行：<returnType> <name>(<params>)
                    val closeParen = sig.lastIndexOf(')')
                    if (closeParen < 0) continue
                    val beforeParen = sig.substring(0, parenIdx)
                    val sp = beforeParen.lastIndexOf(' ')
                    if (sp < 0) continue
                    val name = beforeParen.substring(sp + 1).trim()
                    if (name.contains('.')) continue        // 跳过内联方法
                    val retDesc = typeToDescriptor(beforeParen.substring(0, sp).trim())
                    val paramStr = sig.substring(parenIdx + 1, closeParen)
                    val paramDescs = if (paramStr.isBlank()) ""
                        else paramStr.split(',').joinToString("") { typeToDescriptor(it.trim()) }
                    members["$curOwner\n$name\n($paramDescs)$retDesc"] = renamedMember
                }
            }
        }
        return Mappings(classes, members)
    }

    /** ProGuard/R8 dot-notation type → ASM descriptor（递归处理数组）。 */
    private fun typeToDescriptor(t: String): String = when {
        t.endsWith("[]") -> "[" + typeToDescriptor(t.dropLast(2))
        else -> when (t) {
            "void"    -> "V";  "boolean" -> "Z";  "byte"  -> "B";  "char"   -> "C"
            "short"   -> "S";  "int"     -> "I";  "long"  -> "J"
            "float"   -> "F";  "double"  -> "D"
            else      -> "L${t.replace('.', '/')};"
        }
    }

    private fun write(outDir: File, internalName: String, bytes: ByteArray) {
        val target = File(outDir, "$internalName.class")
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }

    /**
     * 预扫描所有被修类，建立「原始方法签名 → 补丁跳板」重定向表。
     * key   = "origOwnerInternal\nmethodName\norigDesc"
     * value = Triple(dispatcherInternal, trampName, trampDesc)
     *
     * 用途：当某补丁方法体中调用了同批次另一个被修类的方法（如 add 调 multiply），
     * 若 R8 已将目标方法内联，宿主中不再存在该虚方法，跳板直接调用对应补丁分发类的 static 跳板即可。
     */
    private fun buildCrossCallMap(
        topClasses: List<ClassNode>,
        classMapping: Map<String, String>,
        config: Config,
    ): Map<String, Triple<String, String, String>> {
        val skip = Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE
        val map  = HashMap<String, Triple<String, String, String>>()
        for (cn in topClasses) {
            val origDot     = cn.name.replace('/', '.')
            val rtInternal  = (classMapping[origDot] ?: origDot).replace('.', '/')
            val ownerType   = Type.getObjectType(rtInternal)
            val dispName    = "${config.patchPkg}/${cn.name.substringAfterLast('/')}Patch"
            for ((idx, m) in cn.methods.withIndex()) {
                if (m.name == "<init>" || m.name == "<clinit>") continue
                if (m.access and skip != 0) continue
                val isStatic   = m.access and Opcodes.ACC_STATIC != 0
                val trampArgs  = if (isStatic) Type.getArgumentTypes(m.desc)
                                 else arrayOf(ownerType, *Type.getArgumentTypes(m.desc))
                val trampDesc  = Type.getMethodDescriptor(Type.getReturnType(m.desc), *trampArgs)
                map["${cn.name}\n${m.name}\n${m.desc}"] = Triple(dispName, "_p$idx", trampDesc)
            }
        }
        return map
    }

    /**
     * MethodRemapper 子类：对「同批次补丁类中的方法调用」直接重定向到对应分发类的 static 跳板（INVOKESTATIC），
     * 完全绕过宿主中可能已被 R8 内联/移除的虚方法；其余调用走正常的 Remapper 逻辑。
     *
     * 栈一致性：INVOKEVIRTUAL owner.method(args) 消耗 [this, args...]，
     * 而重定向后的 INVOKESTATIC Patch._pN(owner_type, args) 同样消耗 [this, args...]（跳板首参 = self）。
     */
    private class CrossPatchMethodVisitor(
        delegate: MethodVisitor,
        remapper: Remapper,
        private val crossCallMap: Map<String, Triple<String, String, String>>,
    ) : MethodRemapper(delegate, remapper) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
            val redirect = crossCallMap["$owner\n$name\n$descriptor"]
            if (redirect != null) {
                val (dispInternal, trampName, trampDesc) = redirect
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, dispInternal, trampName, trampDesc, false)
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }
    }

    private fun buildClassRemap(
        classNodes: List<ClassNode>,
        topClasses: List<ClassNode>,
        classMapping: Map<String, String>,
        config: Config,
    ): Map<String, String> {
        val topNames = topClasses.mapTo(HashSet()) { it.name }
        val out = HashMap<String, String>()
        for (cn in topClasses) {
            val runtimeName = classMapping[cn.name.replace('/', '.')] ?: cn.name.replace('/', '.')
            out[cn.name] = runtimeName.replace('.', '/')
        }
        for (cn in classNodes) {
            if (cn.name !in topNames) {
                out[cn.name] = "${config.patchPkg}/${cn.name.substringAfterLast('/')}"
            }
        }
        return out
    }

    // 生成器 classpath 上没有 Calculator/IpChange 等类，帧计算需要的 common-super 找不到就回退 Object。
    // ⚠️ 局限：若某方法体内**两个不相关引用类型在分支汇合处合并**，被强行归并成 Object 会算出错误的
    //    StackMapTable -> 运行时 VerifyError。简单补丁(如 add，无此类合并)不触发；要彻底正确需把宿主/补丁
    //    类喂给 ASM 的类型解析器(自定义 ClassLoader 或预扫描类层次)。本教学版未做。
    private fun newWriter() = object : ClassWriter(COMPUTE_FRAMES) {
        override fun getCommonSuperClass(a: String, b: String): String {
            if (a == b) return a
            return try { super.getCommonSuperClass(a, b) } catch (t: Throwable) { "java/lang/Object" }
        }
    }

    private fun emitDefaultCtor(cw: ClassWriter) {
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    // 普通 class（非 data class）：含 Array<Type> 字段，且只按字段读取、从不比较，无需 equals/hashCode。
    private class Entry(val methodId: String, val trampName: String, val trampDesc: String, val trampArgs: Array<Type>, val ret: Type)

    /**
     * 为单个被修类生成分发类 <patchPkg>/<Simple>Patch。
     * @param runtimeDotName 该类在**运行时宿主**里的类名（release 为 R8 混淆名，如 a.a；debug 为原名）。
     *   跳板首参(self)类型与 ipcDispatch 里的 checkcast 必须用这个名字 —— 宿主传进来的 args[0] 是混淆类的实例，
     *   若仍用原始名 com/demo/app/Calculator，release 下该类已不存在 -> NoClassDefFoundError。
     */
    private fun genDispatcher(
        cn: ClassNode,
        runtimeDotName: String,
        config: Config,
        remapper: Remapper,
        crossCallMap: Map<String, Triple<String, String, String>>,
    ): Pair<String, ByteArray> {
        val origInternal = cn.name
        val ownerInternal = runtimeDotName.replace('.', '/')
        val simple   = origInternal.substringAfterLast('/')
        val dispName = "${config.patchPkg}/${simple}Patch"
        val dispType = Type.getObjectType(dispName)

        val cw = newWriter()
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, dispName, null, "java/lang/Object", arrayOf(config.ipChange))
        emitDefaultCtor(cw)

        val entries = emitTrampolines(cn, ownerInternal, origInternal, cw, remapper, crossCallMap)
        emitDispatchIndex(entries, dispType, cw)
        emitIpcDispatch(entries, dispType, cw)

        cw.visitEnd()
        return dispName to cw.toByteArray()
    }

    /** 复制 Kotlin 生成的 lambda/内部/默认参数 helper 类，并重写其 owner，避免 parent-first 加载到宿主旧类。 */
    private fun genHelper(cn: ClassNode, remapper: Remapper): Pair<String, ByteArray> {
        val cw = newWriter()
        cn.accept(ClassRemapper(cw, remapper))
        return remapper.map(cn.name) to cw.toByteArray()
    }

    /** 把被修类的每个可插桩方法转成 static 跳板 _p0/_p1/...，实例方法前插 self 首参保持 slot 对齐。 */
    private fun emitTrampolines(
        cn: ClassNode,
        ownerInternal: String,
        origInternal: String,
        cw: ClassWriter,
        remapper: Remapper,
        crossCallMap: Map<String, Triple<String, String, String>>,
    ): List<Entry> {
        val ownerType = Type.getObjectType(ownerInternal)
        val skip = Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE
        val entries = ArrayList<Entry>()

        for ((trampIdx, m) in cn.methods.withIndex()) {
            if (m.name == "<init>" || m.name == "<clinit>") continue
            if (m.access and skip != 0) continue
            val isStatic  = m.access and Opcodes.ACC_STATIC != 0
            val origArgs  = Type.getArgumentTypes(m.desc)
            val ret       = Type.getReturnType(m.desc)
            // 实例方法：前插 owner 作首参(self)；原 slot0=this，转 static 后 slot0=self，槽位不变，方法体原样复用。
            val trampArgs = if (isStatic) origArgs else arrayOf(ownerType, *origArgs)
            val trampDesc = Type.getMethodDescriptor(ret, *trampArgs)
            val trampName = "_p$trampIdx"

            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                trampName,
                trampDesc,
                null,
                m.exceptions?.toTypedArray()
            )
            m.accept(CrossPatchMethodVisitor(mv, remapper, crossCallMap))

            // methodId 用**原始**类名 —— 与 HotfixMethodVisitor 烤进宿主方法头的字符串常量对齐(R8 不改字符串)。
            entries += Entry(methodId(origInternal, m.name, m.desc), trampName, trampDesc, trampArgs, ret)
        }
        return entries
    }

    /** 生成静态字段 DISPATCH_IDX（Map<String,Integer>）及其 <clinit> 初始化，供 ipcDispatch O(1) 查表。 */
    private fun emitDispatchIndex(entries: List<Entry>, dispType: Type, cw: ClassWriter) {
        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "DISPATCH_IDX", "Ljava/util/Map;",
            "Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;", null
        )?.visitEnd()

        // <clinit>：初始化 DISPATCH_IDX —— 类加载时执行一次，后续每次 ipcDispatch 都是 O(1)
        val si = GeneratorAdapter(Opcodes.ACC_STATIC, AsmMethod("<clinit>", "()V"), null, null, cw)
        si.newInstance(HASHMAP_T)
        si.dup()
        si.invokeConstructor(HASHMAP_T, AsmMethod("<init>", "()V"))
        si.putStatic(dispType, "DISPATCH_IDX", MAP_T)
        for ((i, e) in entries.withIndex()) {
            si.getStatic(dispType, "DISPATCH_IDX", MAP_T)
            si.push(e.methodId)
            si.push(i)
            si.box(Type.INT_TYPE)
            si.invokeInterface(MAP_T, AsmMethod("put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
            si.pop()
        }
        si.returnValue()
        si.endMethod()
    }

    /** 生成 ipcDispatch：HashMap.get(methodId) -> Integer -> tableswitch -> 拆箱调跳板 -> 装箱返回。 */
    private fun emitIpcDispatch(entries: List<Entry>, dispType: Type, cw: ClassWriter) {
        val integerType = Type.getType(Integer::class.java)
        val ga = GeneratorAdapter(Opcodes.ACC_PUBLIC, AsmMethod("ipcDispatch", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"), null, null, cw)

        if (entries.isEmpty()) {
            ga.throwException(Type.getType(IllegalStateException::class.java), "unknown method")
            ga.endMethod()
            return
        }

        val nullBranch    = ga.newLabel()   // methodId 不在表中时的 pop+throw
        val defaultBranch = ga.newLabel()   // tableswitch default（理论上不可达）

        // Integer i = DISPATCH_IDX.get(methodId)
        ga.getStatic(dispType, "DISPATCH_IDX", MAP_T)
        ga.loadArg(0)
        ga.invokeInterface(MAP_T, AsmMethod("get", "(Ljava/lang/Object;)Ljava/lang/Object;"))

        // if (i == null) goto nullBranch（dup 保留栈顶供 nullBranch 的 pop 使用）
        ga.dup()
        ga.ifNull(nullBranch)

        // int idx = ((Integer) i).intValue()
        ga.checkCast(integerType)
        ga.invokeVirtual(integerType, AsmMethod("intValue", "()I"))

        // tableswitch 0..n-1，default -> defaultBranch
        val caseLabels = Array(entries.size) { ga.newLabel() }
        ga.visitTableSwitchInsn(0, entries.size - 1, defaultBranch, *caseLabels)

        for ((i, e) in entries.withIndex()) {
            ga.mark(caseLabels[i])
            e.trampArgs.forEachIndexed { argIdx, t ->
                ga.loadArg(1)
                ga.push(argIdx)
                ga.arrayLoad(OBJECT_T)
                ga.unbox(t)
            }
            ga.invokeStatic(dispType, AsmMethod(e.trampName, e.trampDesc))
            if (e.ret.sort == Type.VOID) ga.visitInsn(Opcodes.ACONST_NULL) else ga.box(e.ret)
            ga.returnValue()
        }

        // null 分支：弹掉栈顶 null 再抛
        ga.mark(nullBranch)
        ga.pop()
        ga.throwException(Type.getType(IllegalStateException::class.java), "unknown method")

        // default 分支（理论上不可达，防御用）
        ga.mark(defaultBranch)
        ga.throwException(Type.getType(IllegalStateException::class.java), "unknown method")

        ga.endMethod()
    }

    /** 生成 PatchesLoaderImpl：load() 返回 HashMap{运行时名 -> new <X>Patch()}；baseVersion() 返回常量。 */
    private fun genLoader(entries: Map<String, String>, config: Config): ByteArray {
        val cw = newWriter()
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, config.loaderInternal, null, "java/lang/Object", arrayOf(config.patchesLoader))
        emitDefaultCtor(cw)

        // load()Ljava/util/Map;
        val gl = GeneratorAdapter(Opcodes.ACC_PUBLIC, AsmMethod("load", "()Ljava/util/Map;"), null, null, cw)
        gl.newInstance(HASHMAP_T)
        gl.dup()
        gl.invokeConstructor(HASHMAP_T, AsmMethod("<init>", "()V"))
        val map = gl.newLocal(MAP_T)
        gl.storeLocal(map)
        for ((runtimeName, dispInternal) in entries) {
            val dispType = Type.getObjectType(dispInternal)
            gl.loadLocal(map)
            gl.push(runtimeName)
            gl.newInstance(dispType)
            gl.dup()
            gl.invokeConstructor(dispType, AsmMethod("<init>", "()V"))
            gl.invokeInterface(MAP_T, AsmMethod("put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
            gl.pop()
        }
        gl.loadLocal(map)
        gl.returnValue()
        gl.endMethod()

        // baseVersion()Ljava/lang/String;
        val gv = GeneratorAdapter(Opcodes.ACC_PUBLIC, AsmMethod("baseVersion", "()Ljava/lang/String;"), null, null, cw)
        gv.push(config.baseVersion)
        gv.returnValue()
        gv.endMethod()

        cw.visitEnd()
        return cw.toByteArray()
    }
}
