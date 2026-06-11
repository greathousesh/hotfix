package com.demo.hotfix.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import javax.inject.Inject

/**
 * 把补丁打包整条流水线做成 Gradle 任务，取代旧的 build_patch.sh / make_*.sh。
 *
 * 收益：Gradle daemon 常驻 + Kotlin 增量编译 + 任务 up-to-date 跳过 + ASM 在 daemon 进程内调用——
 * 旧脚本每次冷启 2×kotlinc + 嵌套 gradle + 独立 java/d8 的开销全部消除；输入不变的步骤直接跳过。
 *
 * 应用在 :patch-src（AGP library）上，对 debug/release 各注册：
 *   generatePatchClasses{V}（进程内 ASM，复用 PatchOverrideGenerator）-> dexPatch{V}(d8)
 *   linkPatchRes{V}(aapt2, 用宿主 stable-ids) / compilePatchNative(clang) / assemblePatch{V}
 * 只生成补丁产物到 build/patch/{variant}，不做设备推送（推送请自行用 adb）。
 *
 * 被修类的字节码直接取 AGP 的 compile{V}Kotlin 产物（patched/Calculator.kt），不再手调 kotlinc。
 * SDK/build-tools/NDK 路径从 local.properties / 环境变量解析（与旧脚本一致，避免依赖 AGP 内部 API）。
 */
class HotfixPatchPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("hotfixPatch", HotfixPatchExtension::class.java)
        val tk = Toolchain.resolve(project)

        // baseVersion：优先用 extension 显式配置；否则从根项目 :app 的 versionName 自动读取。
        val baseVersionProvider: org.gradle.api.provider.Provider<String> = project.provider {
            ext.baseVersion ?: resolveAppVersionName(project)
        }

        // native 补丁与变体无关，注册一次
        val nativeTask = project.tasks.register("compilePatchNative", NativeTask::class.java) {
            it.group = GROUP
            it.clang.set(tk.clang.path)
            it.src.set(project.layout.projectDirectory.file("cpp/patch.c"))
            it.outSo.set(project.layout.buildDirectory.file("patch/libpatch.so"))
        }

        for (variant in listOf("debug", "release")) {
            val V = variant.replaceFirstChar { it.uppercase() }
            val patchDir = project.layout.buildDirectory.dir("patch/$variant")
            // AGP+KGP 把被修类编到这里（patched/Calculator.kt -> com/demo/app/Calculator.class）
            val kotlinClasses = project.layout.buildDirectory.dir("tmp/kotlin-classes/$variant")
            val hostApk = project.rootProject.layout.projectDirectory
                .file("app/build/outputs/apk/$variant/app-$variant.apk")
            // release 用 R8 的 mapping.txt 把被修类解析成混淆名；debug 无此文件 -> 用原始名
            val mapping = project.rootProject.layout.projectDirectory
                .file("app/build/outputs/mapping/release/mapping.txt")
            // d8 --classpath 要的接口(IpChange/PatchesLoader)从 hotfix-sdk 的 compile classes.jar 取
            val sdkJar = project.rootProject.layout.projectDirectory.file(
                "hotfix-sdk/build/intermediates/compile_library_classes_jar/$variant/bundleLibCompileToJar$V/classes.jar"
            )

            val gen = project.tasks.register("generatePatchClasses$V", GeneratePatchTask::class.java) {
                it.group = GROUP
                it.dependsOn("compile${V}Kotlin")
                it.classesDir.set(kotlinClasses)
                it.baseVersion.set(baseVersionProvider)
                it.loaderClass.set(project.provider { ext.loaderClass })
                // release 的 mapping.txt 由 :app 的 R8 产出 -> 显式依赖，否则 Gradle 报隐式依赖错
                if (variant == "release") {
                    it.mappingFile.set(mapping)
                    it.dependsOn(":app:assemble$V")
                }
                it.outputDir.set(patchDir.map { d -> d.dir("gen") })
            }

            val dex = project.tasks.register("dexPatch$V", DexPatchTask::class.java) {
                it.group = GROUP
                it.dependsOn(gen, ":hotfix-sdk:bundleLibCompileToJar$V")
                it.d8.set(File(tk.buildTools, "d8").path)
                it.androidJar.set(project.layout.file(project.provider { tk.androidJar }))
                it.classpathJar.set(sdkJar)
                it.classesDir.set(patchDir.map { d -> d.dir("gen") })
                it.outDex.set(patchDir.map { d -> d.file("patch.dex") })
            }

            val res = project.tasks.register("linkPatchRes$V", LinkResTask::class.java) {
                it.group = GROUP
                it.dependsOn(":app:assemble$V")
                it.aapt2.set(File(tk.buildTools, "aapt2").path)
                it.androidJar.set(project.layout.file(project.provider { tk.androidJar }))
                it.resDir.set(project.layout.projectDirectory.dir("res_patch"))
                it.hostApk.set(hostApk)
                it.outApk.set(patchDir.map { d -> d.file("patch_res.apk") })
            }

            project.tasks.register("assemblePatch$V") {
                it.group = GROUP
                it.description = "构建 $variant 三类补丁(patch.dex/patch_res.apk/libpatch.so) -> build/patch/$variant"
                it.dependsOn(dex, res, nativeTask)
            }
        }
    }

    companion object {
        const val GROUP = "hotfix patch"

        /** 从根项目 :app 的 AGP extension 读取 versionName，找不到时回退 "1.0.0"。 */
        internal fun resolveAppVersionName(project: Project): String {
            val appProject = project.rootProject.findProject(":app") ?: return "1.0.0"
            return try {
                // 避免硬依赖 AppExtension，用反射读取，以兼容不同 AGP 版本
                val androidExt = appProject.extensions.findByName("android") ?: return "1.0.0"
                val defaultConfig = androidExt.javaClass.getMethod("getDefaultConfig").invoke(androidExt)
                defaultConfig.javaClass.getMethod("getVersionName").invoke(defaultConfig) as? String ?: "1.0.0"
            } catch (t: Throwable) {
                project.logger.warn("[HotfixPatchPlugin] 无法自动读取 versionName，回退到 1.0.0：${t.message}")
                "1.0.0"
            }
        }
    }
}

// ---------------- 工具链解析（SDK/build-tools/NDK/android.jar），不依赖 AGP 内部 API ----------------
private class Toolchain(
    val buildTools: File, val androidJar: File, val clang: File,
) {
    companion object {
        fun resolve(project: Project): Toolchain {
            val sdk = sdkDir(project)
            val bt = File(sdk, "build-tools").listFiles()
                ?.filter { it.isDirectory && File(it, "d8").exists() && File(it, "aapt2").exists() }
                ?.maxWithOrNull(VER_COMPARATOR) // 选最高且含 d8+aapt2（避开残缺的 37.0.0）
                ?: error("no build-tools with d8+aapt2 under $sdk/build-tools")
            val androidJar = File(sdk, "platforms").listFiles()
                ?.filter { File(it, "android.jar").exists() }
                ?.maxByOrNull { it.name.substringAfter('-').toIntOrNull() ?: 0 }
                ?.let { File(it, "android.jar") }
                ?: error("no platforms/android-*/android.jar under $sdk")
            val ndk = File(sdk, "ndk").listFiles()?.filter { it.isDirectory }
                ?.maxWithOrNull(VER_COMPARATOR) ?: error("no NDK under $sdk/ndk")
            val host = File(ndk, "toolchains/llvm/prebuilt").listFiles()?.firstOrNull()
                ?: error("no NDK llvm prebuilt under $ndk")
            val clang = File(host, "bin/aarch64-linux-android24-clang")
            return Toolchain(bt, androidJar, clang)
        }

        private fun sdkDir(project: Project): File {
            File(project.rootProject.projectDir, "local.properties").takeIf { it.exists() }?.let { lp ->
                val p = Properties(); lp.inputStream().use { p.load(it) }
                p.getProperty("sdk.dir")?.let { return File(it) }
            }
            System.getenv("ANDROID_SDK_ROOT")?.let { return File(it) }
            System.getenv("ANDROID_HOME")?.let { return File(it) }
            return File(System.getProperty("user.home"), "Library/Android/sdk")
        }
    }
}

// 版本号按数字段比较，避免字典序把 "9.0.0" 排到 "36.1.0" 前面
private fun verKey(f: File): List<Int> = f.name.split('.').map { it.toIntOrNull() ?: 0 }
private fun List<Int>.compareToVer(o: List<Int>): Int {
    for (i in 0 until maxOf(size, o.size)) {
        val c = getOrElse(i) { 0 }.compareTo(o.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}
private val VER_COMPARATOR = Comparator<File> { a, b -> verKey(a).compareToVer(verKey(b)) }

// ---------------- 任务：ASM 生成（进程内，复用 PatchOverrideGenerator） ----------------
abstract class GeneratePatchTask : DefaultTask() {
    @get:InputDirectory abstract val classesDir: DirectoryProperty
    @get:Optional @get:InputFile abstract val mappingFile: RegularFileProperty
    @get:Input abstract val baseVersion: Property<String>
    @get:Input abstract val loaderClass: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        val mf = mappingFile.orNull?.asFile?.takeIf { it.exists() }
        PatchOverrideGenerator.generate(classesDir.get().asFile, out, baseVersion.get(), mf, loaderClass.get())
    }
}

// ---------------- 任务：d8 -> patch.dex ----------------
abstract class DexPatchTask : DefaultTask() {
    @get:Input abstract val d8: Property<String>
    @get:InputFile abstract val androidJar: RegularFileProperty
    @get:InputFile abstract val classpathJar: RegularFileProperty
    @get:InputDirectory abstract val classesDir: DirectoryProperty
    @get:OutputFile abstract val outDex: RegularFileProperty
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction fun run() {
        val outFile = outDex.get().asFile
        val outDir = outFile.parentFile.apply { mkdirs() }
        val classes = classesDir.get().asFile.walkTopDown().filter { it.extension == "class" }.map { it.path }.toList()
        // 新版 d8(>=9.x) 不再按 ':' 拆 --classpath，每条目单独一个 flag。
        execOps.exec {
            it.commandLine(buildList {
                add(d8.get()); add("--min-api"); add("24")
                add("--output"); add(outDir.path)
                add("--lib"); add(androidJar.get().asFile.path)
                add("--classpath"); add(classpathJar.get().asFile.path)
                addAll(classes)
            })
        }
        File(outDir, "classes.dex").renameTo(outFile)   // d8 写的是 classes.dex
    }
}

// ---------------- 任务：aapt2 compile+link（宿主 stable-ids 锁 id）-> patch_res.apk ----------------
abstract class LinkResTask : DefaultTask() {
    @get:Input abstract val aapt2: Property<String>
    @get:InputFile abstract val androidJar: RegularFileProperty
    @get:InputDirectory abstract val resDir: DirectoryProperty
    @get:InputFile abstract val hostApk: RegularFileProperty
    @get:OutputFile abstract val outApk: RegularFileProperty
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction fun run() {
        val aapt2 = aapt2.get()
        val work = temporaryDir
        val compiled = File(work, "res_compiled.zip")
        execOps.exec { it.commandLine(aapt2, "compile", "--dir", resDir.get().asFile.path, "-o", compiled.path) }

        // 从宿主 apk dump 出真实资源 id，生成 --stable-ids，把补丁同名资源钉到宿主 id
        val dump = ByteArrayOutputStream()
        execOps.exec { it.commandLine(aapt2, "dump", "resources", hostApk.get().asFile.path); it.standardOutput = dump }
        val re = Regex("""resource (0x[0-9a-fA-F]+) ([a-z]+)/(\S+)""")
        val ids = LinkedHashSet<String>()
        dump.toString().lineSequence().forEach { line ->
            re.find(line)?.let { m -> ids += "com.demo.app:${m.groupValues[2]}/${m.groupValues[3]} = ${m.groupValues[1]}" }
        }
        val stableIds = File(work, "stable_ids.txt").apply { writeText(ids.sorted().joinToString("\n")) }
        val manifest = File(work, "AndroidManifest.xml").apply {
            writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.demo.app"/>""")
        }
        outApk.get().asFile.parentFile.mkdirs()
        execOps.exec {
            it.commandLine(
                aapt2, "link", "-o", outApk.get().asFile.path,
                "-I", androidJar.get().asFile.path,
                "--manifest", manifest.path,
                "--stable-ids", stableIds.path,
                compiled.path,
            )
        }
    }
}

// ---------------- 任务：NDK clang -> libpatch.so (arm64) ----------------
abstract class NativeTask : DefaultTask() {
    @get:Input abstract val clang: Property<String>
    @get:InputFile abstract val src: RegularFileProperty
    @get:OutputFile abstract val outSo: RegularFileProperty
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction fun run() {
        outSo.get().asFile.parentFile.mkdirs()
        execOps.exec {
            it.commandLine(clang.get(), "-shared", "-fPIC", "-O2", "-o", outSo.get().asFile.path, src.get().asFile.path)
        }
    }
}

