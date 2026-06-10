# MiniHotfix —— 三合一热修复 SDK + Demo

一个教学用的 Android 热修复 SDK，把从淘宝 APK 逆向分析得到的三种机制各实现一份最小可用版本：

| 维度 | 生产对应（淘宝） | 本 SDK 实现 | 入口 |
|---|---|---|---|
| 代码(Kotlin/Java) | InstantPatch（InstantRun 改造，`$change` 重定向） | DexClassLoader 加载补丁 dex + 反射给 `$ipChange` 字段注入 override | `JavaPatcher` |
| 资源 | MonkeyPatcher（反射重建 AssetManager） | `monkeyPatchExistingResources` 反射替换全部 Resources 的 AssetManager | `ResourcePatcher` |
| Native | InlinePatch / tconhook（C++ 内联 hook） | arm64 最小内联 hook（改写函数前 16 字节跳到补丁函数） | `NativeHotfix` |

> ⚠️ 这是**原理演示**，不是生产框架。安全校验、增量、灰度回滚、多进程、ART 版本适配、native 指令重定位（保留调用原函数能力）等都做了简化。生产请用 Sophix / Tinker / ShadowHook 等成熟方案。

## Java 插桩：已升级为真正的 ASM Gradle 插件（`hotfix-plugin/`）
不再手写插桩。`hotfix-plugin` 用 **AGP 8 Instrumentation API + ASM** 在编译期自动改写 `com.demo.app` 下每个类：
- 注入字段 `public static volatile IpChange $ipChange;`（`HotfixClassVisitor`）
- 每个方法头注入 `if ($ipChange != null) return $ipChange.ipcDispatch("Owner.name(desc)", arrayOf(this?, 参数...))`（`HotfixMethodVisitor`，基于 `AdviceAdapter`，自动处理参数装箱/返回值拆箱）
- 方法名用 `ipcDispatch`（非淘宝的 `ipc$dispatch`）：Kotlin 标识符不允许含 `$`，且 `@JvmName` 不能用于接口抽象方法；字段 `$ipChange` 由 ASM 直接写字节码，不受此限
- 跳过 `<init>/<clinit>`、abstract/native/synthetic/bridge 方法与 R/BuildConfig
- 因新增分支，插件设 `FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS` 让 AGP 重算 stackmap

接入方式：根 `settings.gradle` 里 `pluginManagement { includeBuild 'hotfix-plugin' }`，`app/build.gradle` 应用 `id 'com.demo.hotfix.instrument'`。
methodId 约定 `类全名.方法名+描述符`（如 `com.demo.app.Calculator.add(II)I`），补丁分发按此命中——务必与插件生成规则一致。
插件源码即真正可用实现（Kotlin），见 `hotfix-plugin/src/main/kotlin/`。

## 补丁分发类：由字节码自动生成（InstantPatch `$override` 风格）
开发者**不再手写** `ipcDispatch` 的 `when(methodId)`，而是直接编写**修好的原始类** `patched/com/demo/app/Calculator.kt`（同包同名、纯业务）。打包期 `hotfix-plugin` 里的 `PatchOverrideGenerator`（ASM）把它转成分发类 `com.demo.patch.CalculatorPatch`（实现 `IpChange`）：
- 每个原方法 → 一个 **static 跳板**，实例作首参（实例方法原 `this` 在 slot0，转 static 后 slot0=self，槽位不变，方法体原样复用）
- 生成 `ipcDispatch`：按 methodId `equals` 命中后，从 `args[]` 取参拆箱、`invokestatic` 跳板、返回值装箱
- methodId 严格用插件同一格式 `com.demo.app.Calculator.add(II)I`

**为何还要异名分发类、而不能直接发修好的 `Calculator`**：宿主已加载 `com.demo.app.Calculator`，补丁 `DexClassLoader`(parent=宿主) 是 parent-first，补丁 dex 里同名类会被宿主屏蔽、永远加载不到——这正是 InstantRun 用 `$override` 异名类的原因。所以 `build_patch.sh` 只把**生成的分发类**打进 `patch.dex`，`patched/` 里修好的原始类**只在生成时被读取、绝不进 dex**。
`PatchesLoaderImpl` 用 `Class.forName("com.demo.patch.CalculatorPatch")` 反射实例化生成类（不静态依赖它，从而让仅供 IDE 索引的 `:patch-src` 模块也能编译）。
> 简化：跳板里若引用宿主自身字段/其它方法会解析到宿主类（demo 的 `add` 无此类引用）；生产框架(InstantRun)还会重定向这些内部访问，本 demo 未做。

## 工程结构
```
HotfixDemo/
├── settings.gradle / build.gradle / gradle.properties
├── hotfix-sdk/                     # 热修复 SDK（Android Library）
│   └── src/main/
│       ├── java/com/demo/hotfix/
│       │   ├── Hotfix.kt           # 门面：init + 三种 applyXxxPatch
│       │   ├── core/IpChange.kt    # 方法重定向接口（方法名 ipcDispatch）
│       │   ├── core/PatchesLoader.kt
│       │   ├── core/JavaPatcher.kt # 加载补丁 dex、注入 $ipChange
│       │   ├── res/ResourcePatcher.kt
│       │   └── nativehook/NativeHotfix.kt
│       └── cpp/                     # 内联 hook native 实现
│           ├── CMakeLists.txt
│           └── inline_hook.c
├── app/                            # Demo App
│   └── src/main/
│       ├── java/com/demo/app/
│       │   ├── MainActivity.kt
│       │   └── Calculator.kt        # 被修类（纯业务，插件自动插桩）
│       ├── cpp/ (native_bug.c)      # 被修 native 函数（有 bug）
│       └── res/ (布局/字符串)
├── patch-src/                      # 补丁源码 + 打包脚本
│   ├── patched/com/demo/app/Calculator.kt         # ★补丁源：开发者直接写「修好的原始类」(纯业务，无样板)
│   ├── kotlin/com/demo/patch/PatchesLoaderImpl.kt # 列出被修类(反射实例化生成的分发类)
│   ├── kotlin/com/demo/patch/Mappings.kt          # 由 build_patch.sh 按 mapping.txt 生成
│   ├── res_patch/                   # 改了颜色/字符串的资源
│   ├── build.gradle                 # 仅供 Android Studio 索引补丁源(不产出补丁，见文件注释)
│   └── build_patch.sh               # kotlinc + ASM 生成分发类 + d8 打补丁 dex
└── (全工程 Kotlin：app / hotfix-sdk / hotfix-plugin / patch-src 均为 .kt)
```

## 混淆(R8) 与 dexopt 处理
插桩发生在 **R8 之前**，由此带来三个必须处理的点，本工程已全部覆盖：

1. **方法头烤入的 methodId 是“原始类名”**（R8 默认不改写字符串常量）。补丁 `CalculatorPatch` 的 switch 也用原始名 → 自动对齐，无需重映射。**前提：不要开启 `-adaptclassstrings`**（见 `consumer-rules.pro` 说明）。
2. **`Class.forName` 需要“混淆后类名”**。补丁不写死类名，而由 `build_patch.sh` 解析 `mapping.txt` 生成 `Mappings.CALCULATOR`（未混淆时回退原名），`PatchesLoaderImpl.load()` 用它做 key，`JavaPatcher` 据此 `forName` + 反射 `$ipChange`。
3. **`$ipChange` 字段不能被改名 / 不能被常量折叠成 null**。`hotfix-sdk/consumer-rules.pro` 用 `-keepclassmembers ... $ipChange` 保住字段名，并让 R8 视其为“可能被反射修改”，从而**不删除**方法头的 `if ($ipChange != null)` 分支。这是混淆下热修复能生效的关键。

**dexopt**（`JavaPatcher`）：按 API 版本区分 —— `<26` 用 `optimizedDirectory` 做 odex；`>=26` 该参数被系统忽略，用 `code_cache`，首次解释/JIT、系统择机 `dex2oat`（如需可后台预热，本 demo 未做）。

release 打补丁流程：`./gradlew :app:assembleRelease` 生成 `mapping.txt` → `export MAPPING=.../release/mapping.txt` → 跑 `build_patch.sh`（会用混淆名生成补丁）。

## 演示效果
App 主界面三个按钮 + 一个“打补丁”按钮：
1. **Java**：`Calculator.add(2,3)` 修复前显示 `-1`（bug：写成了 a-b），打补丁后显示 `5`。
2. **Resource**：标题文字/背景色，打补丁后变成补丁包里的新值。
3. **Native**：`nativeAdd(2,3)` 修复前 `-1`，内联 hook 后 `5`。

## 如何运行

已内置 Gradle Wrapper（`./gradlew`，Gradle 8.9 / AGP 8.5.2 / JDK 17，需装好 SDK platform-34 + build-tools + NDK + arm64 设备/模拟器）。

> 全工程已是 **Kotlin**（app / SDK / 插件 / 补丁）。打补丁需要 `kotlinc`（脚本会自动探测 PATH 或 Android Studio 内置的 Kotlin）。

### A. 快速验证（debug，不混淆）
```bash
./gradlew :app:installDebug      # 装到设备
```
打开 App → 点三个按钮看到错误结果（-1 / 旧文案 / -1）。
再用 debug 变体打补丁（不需要 mapping；build_patch.sh 用 kotlinc 自编 SDK 接口，无需 HOTFIX_SDK_CLASSES）：
```bash
export ANDROID_SDK=~/Library/Android/sdk
export BUILD_TOOLS=$ANDROID_SDK/build-tools/34.0.0
export SDK_JAR=$ANDROID_SDK/platforms/android-34/android.jar
export ANDROID_NDK=$(ls -d $ANDROID_SDK/ndk/* | tail -1)
# 如 kotlinc 不在 PATH：export KOTLINC="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc"
unset MAPPING                     # debug 无混淆 -> 用原始类名
( cd patch-src && bash build_patch.sh )
adb shell mkdir -p /sdcard/Android/data/com.demo.app/files/hotfix
adb push patch-src/out/{patch.dex,patch_res.apk,libpatch.so} /sdcard/Android/data/com.demo.app/files/hotfix/
```
点“应用补丁”→ 再点三个按钮 → 结果变正确（5 / 新文案 / 5）。

> 补丁为何能用宿主的 Kotlin 运行时：补丁 dex 由 DexClassLoader 加载、parent=宿主 ClassLoader，
> 而宿主是 Kotlin 应用、已含 kotlin-stdlib，所以补丁**不打包 stdlib/接口**，运行时从宿主解析。
> 编译补丁时还用 `-Xno-*-assertions` 关掉 Kotlin 空检查，进一步减少对 stdlib 的依赖。

### B. 一键 release（开 R8 混淆，验证混淆下仍生效）★推荐
```bash
./make_release_patch.sh --push
```
该脚本自动：`assembleRelease`（产 mapping.txt + 可安装 apk）→ 解析 mapping 用**混淆名**打补丁 → `adb install` + 推送补丁。
之后开 App，流程同上；这次 `Calculator` 已被混淆成 `a.b.c` 之类，补丁仍能命中——证明 R8 处理正确。

各文件头部都有详细注释，建议从 `Hotfix.kt` 读起。
