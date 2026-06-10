# MiniHotfix —— 三合一热修复 SDK + Demo

一个教学用的 Android 热修复 SDK，把从淘宝 APK 逆向分析得到的三种机制各实现一份最小可用版本：

| 维度 | 生产对应（淘宝） | 本 SDK 实现 | 入口 |
|---|---|---|---|
| 代码(Kotlin/Java) | InstantPatch（InstantRun 改造，`$change` 重定向） | DexClassLoader 加载补丁 dex + 反射给 `$ipChange` 字段注入 override | `JavaPatcher` |
| 资源 | MonkeyPatcher（反射重建 AssetManager） | 反射替换进程内全部 ResourcesImpl 的 AssetManager | `ResourcePatcher` |
| Native | InlinePatch / tconhook（C++ 内联 hook） | arm64 内联 hook：**近距 trampoline + 入口 4 字节 B**（可 hook 短函数） | `NativeHotfix` |

> ⚠️ 这是**原理演示**，不是生产框架。安全校验、增量、灰度回滚、多进程、ART 版本适配、native 指令重定位（保留调用原函数能力）等都做了简化。生产请用 Sophix / Tinker / ShadowHook 等成熟方案。

环境：Gradle 8.9 / AGP 8.6.0 / JDK 17 / compileSdk 35 / minSdk 24 / NDK 28.1.13356709，需 arm64 设备或模拟器。全工程为 Kotlin（app / SDK / 插件 / 补丁），native 为 C。

---

## 1. SDK 对外接口（数据流式，SDK 自管路径 + 启动自动续用）

`Hotfix` 门面只暴露**数据流**接口：调用方把补丁字节流（本地文件 / 网络下载 / 任意来源）交进来，SDK 自己决定落盘路径、立即应用、并持久化；下次冷启动自动重新应用。

```kotlin
// 初始化（主线程，如 Activity/Application onCreate）；会自动重应用已持久化的补丁
Hotfix.init(application, baseVersion = "1.0.0")     // baseVersion 须与打补丁时一致

// 安装补丁：保存到内部沙盒 + 立即应用 + 持久化。请在后台线程调用（流读取可能是网络 IO）
Hotfix.installJavaPatch(inputStream)
Hotfix.installResourcePatch(inputStream)
Hotfix.installNativePatch(inputStream, listOf(NativeHook("native_bug", "compute_add", "patched_add")))
```

- **`PatchStore`**（`core/`，内部）统一管理 `filesDir/hotfix/`：写 `patch.dex` / `patch_res.apk` / `libpatch.so` + `native_hooks.txt`（每行 `lib|sym|patchSym`，供冷启动续用）。落在内部沙盒：app 自己拥有，无文件 owner/SELinux/权限问题，**debug/release 通用**。
- 线程：流的保存在调用线程（后台），真正的应用统一 marshal 到主线程（`Hotfix.onMain`）。
- **启动自动续用**：`init` 扫描已安装补丁并重新应用 —— 安装一次后，杀进程重启无需再点按钮即生效。

---

## 2. Java 插桩：AGP 8 Instrumentation API + ASM 编译期改写（`hotfix-plugin/`）

`hotfix-plugin` 在编译期自动改写 `com.demo.app` 下每个类：
- 注入字段 `public static volatile IpChange $ipChange;`（`HotfixClassVisitor`）
- 每个方法头注入 `if ($ipChange != null) return $ipChange.ipcDispatch("Owner.name(desc)", arrayOf(this?, 参数...))`（`HotfixMethodVisitor`，基于 `AdviceAdapter`，自动处理参数装箱/返回值拆箱）
- 方法名用 `ipcDispatch`（非淘宝的 `ipc$dispatch`）：Kotlin 标识符不允许含 `$`，且 `@JvmName` 不能用于接口抽象方法；字段 `$ipChange` 由 ASM 直接写字节码，不受此限
- 跳过 `<init>/<clinit>`、abstract/native/synthetic/bridge 方法与 R/BuildConfig
- 因新增分支，插件设 `FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS` 让 AGP 重算 stackmap

接入：根 `settings.gradle` 里 `pluginManagement { includeBuild 'hotfix-plugin' }`，`app/build.gradle` 应用 `id 'com.demo.hotfix.instrument'`。
methodId 格式 `类全名.方法名+描述符`（如 `com.demo.app.Calculator.add(II)I`）由 `MethodId.kt` 的 `methodId()` **单一来源**提供，插桩器与补丁生成器都调它，避免格式漂移导致补丁静默失配。

## 3. 补丁分发类：由字节码自动生成（InstantPatch `$override` 风格）

开发者**不手写** `ipcDispatch` 的 `when(methodId)`，而是直接编写**修好的原始类** `patch-src/patched/com/demo/app/Calculator.kt`（同包同名、纯业务）。打包期 `PatchOverrideGenerator`（ASM）把它转成分发类 `com.demo.patch.CalculatorPatch`（实现 `IpChange`），并生成 `com.demo.patch.PatchesLoaderImpl`：
- 每个原方法 → 一个 **static 跳板**，实例作首参（实例方法原 `this` 在 slot0，转 static 后 slot0=self，槽位不变，方法体原样复用）
- 生成 `ipcDispatch`：按 methodId `equals` 命中后，从 `args[]` 取参拆箱、`invokestatic` 跳板、返回值装箱
- 跳板 self 首参与 `checkcast` 用**运行时(混淆)类名**（见第 6 节）；methodId 用**原始**类名

**为何还要异名分发类、而不能直接发修好的 `Calculator`**：宿主已加载 `com.demo.app.Calculator`，补丁 `DexClassLoader`(parent=宿主) 是 parent-first，补丁 dex 里同名类会被宿主屏蔽、永远加载不到——这正是 InstantRun 用 `$override` 异名类的原因。所以 `patch.dex` 只含**生成的分发类 + PatchesLoaderImpl**，`patched/` 里修好的原始类**只在生成时被读取、绝不进 dex**。
`JavaPatcher` 用 `Class.forName(运行时类名)` 反射拿到宿主类，把生成的 override 实例写进其 `$ipChange` 字段 —— 即时生效。

---

## 4. 打补丁流水线（Gradle 插件 `com.demo.hotfix.patch`，应用于 `:patch-src`）

整条流水线是 Gradle 任务（不再有任何 shell 脚本）。被修类字节码直接取 AGP 的 `compile{V}Kotlin` 产物，进程内复用 `PatchOverrideGenerator`，受 Gradle 增量/up-to-date 加持。

```bash
# debug（不混淆）
./gradlew :patch-src:assemblePatchDebug

# release（开 R8 混淆）—— 需先产 mapping.txt
./gradlew :app:assembleRelease
./gradlew :patch-src:assemblePatchRelease
```

每个变体的任务链：`generatePatchClasses{V}`(ASM 生成分发类) → `dexPatch{V}`(d8) → `linkPatchRes{V}`(aapt2 + 宿主 stable-ids 锁资源 id) + `compilePatchNative`(clang) → `assemblePatch{V}`。
产物：`patch-src/build/patch/{debug,release}/{patch.dex,patch_res.apk}` 与 `patch-src/build/patch/libpatch.so`。
工具链（SDK/build-tools/NDK/android.jar）从 `local.properties` 的 `sdk.dir` 解析；自动避开残缺的 build-tools 版本（挑同时含 `d8`+`aapt2` 的最高版）。

## 5. 补丁网络下发（替代 adb push）

补丁通过本机 HTTP server 下发，App 下载为数据流后直接喂给 `Hotfix.install*`。无文件权限问题、debug/release 通用。

```bash
# 1) 生成补丁产物（见第 4 节）
# 2) 起 server（root = patch-src/build/patch，目录布局直接对应 URL）
./serve_patches.sh                      # python3 -m http.server，默认 8080
# 3) 让设备 127.0.0.1:8080 指向本机（USB 真机 / 模拟器通用）
adb reverse tcp:8080 tcp:8080
```

App 按 `BuildConfig.BUILD_TYPE` 取对应补丁：`/{debug|release}/patch.dex`、`/{debug|release}/patch_res.apk`、共用 `/libpatch.so`。Manifest 需 `INTERNET` 权限与 `android:usesCleartextTraffic="true"`（明文 HTTP）。

---

## 6. 混淆(R8) 与 dexopt 处理

插桩发生在 **R8 之前**，由此带来三个必须处理的点，本工程已全部覆盖：

1. **方法头烤入的 methodId 是“原始类名”**（R8 默认不改写字符串常量）。补丁分发类的 if-链也用原始名 → 自动对齐。**前提：不要开启 `-adaptclassstrings`**（见 `hotfix-sdk/consumer-rules.pro`）。
2. **补丁里所有“宿主类引用”需用混淆后类名**。`PatchOverrideGenerator` 解析 `mapping.txt`（`generatePatchClassesRelease` 显式依赖 `:app:assembleRelease`）把被修类解析成运行时名，用于：(a) `PatchesLoaderImpl` 的 Map key（`JavaPatcher` 据此 `Class.forName`）；(b) **分发类跳板 self 首参类型与 `ipcDispatch` 里的 `checkcast`**。少了 (b) 在 release 会 `NoClassDefFoundError: com/demo/app/Calculator`（该类已被改名为如 `a.a`）。debug 无 mapping → 回退原始名。
3. **`$ipChange` 字段不能被改名 / 不能被常量折叠成 null**。`hotfix-sdk/consumer-rules.pro` 用 `-keepclassmembers ... $ipChange` 保住字段名，并让 R8 视其为“可能被反射修改”，从而**不删除**方法头的 `if ($ipChange != null)` 分支。这是混淆下热修复能生效的关键。

**dexopt**（`JavaPatcher`）：`<26` 用 `optimizedDirectory` 做 odex；`>=26` 该参数被忽略，用 `code_cache`，首次解释/JIT、系统择机 `dex2oat`。

## 7. Native 内联 hook 细节（`hotfix-sdk/src/main/cpp/inline_hook.c`）

arm64 整函数替换，能 hook **任意短函数**：
1. 在目标函数 ±128MB 内扫 `/proc/self/maps` 找空闲页，`mmap` 一页做 trampoline，放 64 位绝对跳转 `LDR X17,#8; BR X17; <8 字节补丁地址>`（补丁可在任意远处）。
2. 目标函数入口只改写 **1 条指令(4 字节)**：`B <trampoline>`（arm64 直接分支范围 ±128MB）。

只动入口一条指令，即使函数体只有 8 字节（release `-O2` 下的 `compute_add = sub+ret`）也不会写穿相邻函数 —— 早期“入口直接写 16 字节”正是因此把后一个函数 `nativeAdd` 写穿导致 SIGILL。仍是整函数替换（不保留调原函数能力；要调原函数还需把入口指令重定位到返回 trampoline）。

## 8. 能力边界（热修 vs 冷补丁）

热修复（`$ipChange` 重定向）本质是**换方法体**，方法体只能引用宿主**已存在**的字段/方法。
- ✅ 改方法体（引用已有成员）：支持。
- ❌ **新增实例字段**：无法给已加载的类的对象加字段（`getfield` → `NoSuchFieldError`）。这是 ART/JVM 的硬限制（`RedefineClasses` 不允许增删字段/方法），所有热更框架同理 —— InstantRun 遇到结构性变更会降级为**冷补丁（整 dex 替换 + 重启进程）**。本 demo 只实现热路径。
- ⚠️ 新增方法并在补丁内调用：需生成器把补丁内对本类的调用重定向到跳板，本 demo 未做。

---

## 9. 演示效果

主界面三个按钮 + 一个“★ 应用补丁”按钮：
1. **Java**：`Calculator.add(2,3)` 修复前 `-1`（bug：写成 a-b），打补丁后 `5`。
2. **Resource**：`string/patch_text` 打补丁后变为补丁包里的新文案。
3. **Native**：`nativeAdd(2,3)` 修复前 `-1`，内联 hook 后 `5`。

“应用补丁”会从本机 server 下载三类补丁并安装（见第 5 节）。安装后杀进程重启，无需再点按钮即自动续用。

## 10. 如何运行

内置 Gradle Wrapper（`./gradlew`）。需装好 SDK platform-35 + build-tools(含 d8/aapt2) + NDK + arm64 设备/模拟器。

```bash
# A. debug（不混淆，最快验证）
./gradlew :app:installDebug                 # 装宿主
./gradlew :patch-src:assemblePatchDebug     # 出 debug 补丁
./serve_patches.sh & adb reverse tcp:8080 tcp:8080
# 开 App → 点三按钮(错: -1/旧文案/-1) → 点★应用补丁 → 再点三按钮(对: 5/新文案/5)

# B. release（开 R8 混淆，验证混淆下仍生效）
./gradlew :app:assembleRelease              # 产 mapping.txt + 可安装 apk
./gradlew :patch-src:assemblePatchRelease   # 用混淆名打补丁
adb install -r app/build/outputs/apk/release/app-release.apk
./serve_patches.sh & adb reverse tcp:8080 tcp:8080
# 流程同上；这次 Calculator 已被混淆成 a.a，补丁仍命中 —— 证明 R8 处理正确
```

## 工程结构
```
HotfixDemo/
├── settings.gradle / build.gradle / gradle.properties / serve_patches.sh
├── hotfix-sdk/                          # 热修复 SDK（Android Library）
│   ├── consumer-rules.pro               # 保 $ipChange / IpChange / PatchesLoader 的 R8 规则
│   └── src/main/
│       ├── java/com/demo/hotfix/
│       │   ├── Hotfix.kt                # 门面：init(自动续用) + 三种 installXxxPatch(InputStream)
│       │   ├── NativeHook.kt            # native hook 规格(targetLib/targetSym/patchSym)
│       │   ├── core/IpChange.kt         # 方法重定向接口(ipcDispatch)
│       │   ├── core/PatchesLoader.kt    # 补丁加载器接口(由插件生成 Impl)
│       │   ├── core/JavaPatcher.kt      # 加载补丁 dex、注入 $ipChange
│       │   ├── core/PatchStore.kt       # 补丁落盘/持久化(filesDir/hotfix)
│       │   ├── res/ResourcePatcher.kt
│       │   └── nativehook/NativeHotfix.kt
│       └── cpp/ (CMakeLists.txt, inline_hook.c)   # 近距 trampoline 内联 hook
├── hotfix-plugin/                       # Gradle 插件(Kotlin)
│   └── src/main/kotlin/com/demo/hotfix/plugin/
│       ├── HotfixPlugin.kt / HotfixClassVisitor.kt / HotfixMethodVisitor.kt  # 编译期插桩
│       ├── MethodId.kt                  # methodId 格式单一来源
│       ├── PatchOverrideGenerator.kt    # ASM 生成分发类 + PatchesLoaderImpl
│       └── HotfixPatchPlugin.kt         # 打补丁流水线(generate/dex/linkRes/native/assemble)
├── app/                                 # Demo App
│   └── src/main/
│       ├── java/com/demo/app/{MainActivity,Calculator,NativeBug}.kt
│       ├── cpp/native_bug.c             # 被修 native 函数(有 bug)
│       └── res/
└── patch-src/                           # 补丁源(开发者只写这里) + 打补丁插件
    ├── patched/com/demo/app/Calculator.kt   # ★补丁源：修好的原始类(纯业务，无样板)
    ├── cpp/patch.c                          # native 补丁函数
    ├── res_patch/values/strings.xml         # 资源补丁
    └── build.gradle                         # 应用 com.demo.hotfix.patch 插件
```

各文件头部都有详细注释，建议从 `Hotfix.kt` 读起。
