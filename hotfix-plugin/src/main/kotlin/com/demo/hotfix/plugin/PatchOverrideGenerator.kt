package com.demo.hotfix.plugin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
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

        // mapping.txt 一次性解析成 orig(点分) -> obf(点分)；无 mapping(debug) 则为空表、查不到回退原名。
        val classMapping = parseClassMapping(config.mappingFile)
        // 非 synthetic 的非接口类均视为可热修类：覆盖顶层类、$Companion、命名内部类。
        // 匿名类/lambda/WhenMappings 等编译器合成类带 ACC_SYNTHETIC，走 genHelper 路径。
        val topClasses = classNodes.filter { cn ->
            cn.access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ANNOTATION) == 0
        }
        require(topClasses.isNotEmpty()) { "no patchable class found under $inDir" }

        val remapNames = buildClassRemap(classNodes, topClasses, classMapping, config)
        val remapper = object : Remapper() {
            override fun map(internalName: String): String =
                remapNames[internalName] ?: internalName
        }

        // 运行时类名 -> 分发类内部名（供 PatchesLoaderImpl 装 Map）
        val loaderEntries = LinkedHashMap<String, String>()
        for (cn in topClasses) {
            val origDot    = cn.name.replace('/', '.')
            val runtimeName = classMapping[origDot] ?: origDot
            val (dispName, bytes) = genDispatcher(cn, runtimeName, config, remapper)
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

    /**
     * 解析 proguard/R8 mapping.txt 的**类重命名行** `com.demo.app.Calculator -> a.a:`。
     * 只取顶格(列0)、以 `:` 结尾的类行；缩进的成员行(`    int f -> a`)与 `#` 注释行跳过。
     */
    private fun parseClassMapping(mappingFile: File?): Map<String, String> {
        if (mappingFile == null || !mappingFile.isFile) return emptyMap()
        val map = HashMap<String, String>()
        for (line in mappingFile.readLines()) {
            if (line.isEmpty() || line[0].isWhitespace() || line[0] == '#') continue
            if (!line.endsWith(":")) continue
            val arrow = line.indexOf(" -> ")
            if (arrow < 0) continue
            map[line.substring(0, arrow)] = line.substring(arrow + 4, line.length - 1)
        }
        return map
    }

    private fun write(outDir: File, internalName: String, bytes: ByteArray) {
        val target = File(outDir, "$internalName.class")
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
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
    ): Pair<String, ByteArray> {
        val origInternal = cn.name
        val ownerInternal = runtimeDotName.replace('.', '/')
        val simple   = origInternal.substringAfterLast('/')
        val dispName = "${config.patchPkg}/${simple}Patch"
        val dispType = Type.getObjectType(dispName)

        val cw = newWriter()
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, dispName, null, "java/lang/Object", arrayOf(config.ipChange))
        emitDefaultCtor(cw)

        val entries = emitTrampolines(cn, ownerInternal, origInternal, cw, remapper)
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
            m.accept(MethodRemapper(mv, remapper))

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
