# MiniHotfix —— 三合一热修复 SDK + Demo

一个教学用的 Android 热修复 SDK，把三种主流机制各实现一份最小可用版本：

| 维度 | 参考来源 | 本 SDK 实现 | 核心模块 |
|---|---|---|---|
| 代码(Kotlin/Java) | InstantRun / InstantPatch | 编译期 ASM 插桩 + DexClassLoader 加载补丁 dex + 反射注入 `$ipChange` | `hotfix-plugin` + `JavaPatcher` |
| 资源 | MonkeyPatcher | 反射重建 AssetManager，替换全部 Resources | `ResourcePatcher` |
| Native | tconhook / ShadowHook | arm64 近距 trampoline 内联 hook（4 字节 `B`） | `inline_hook.c` |

> ⚠️ **原理演示，不是生产框架。** 安全校验、增量更新、灰度回滚、多进程、ART 版本适配、native 指令重定位等均已简化。生产请用 Sophix / Tinker / ShadowHook 等成熟方案。

---

## 工程结构

```
HotfixDemo/
├── hotfix-sdk/                      # 热修复 SDK（Android Library）
│   └── src/main/
│       ├── java/com/demo/hotfix/
│       │   ├── Hotfix.kt            # 门面：init / installJavaPatch / installResourcePatch / installNativePatch
│       │   ├── NativeHook.kt        # Native hook 规格数据类
│       │   ├── core/IpChange.kt     # 方法重定向接口（ipcDispatch）
│       │   ├── core/PatchesLoader.kt
│       │   ├── core/JavaPatcher.kt  # 加载补丁 dex、注入 $ipChange
│       │   ├── core/PatchStore.kt   # 补丁落盘（filesDir/hotfix/，原子 rename）
│       │   └── res/ResourcePatcher.kt
│       └── cpp/inline_hook.c        # arm64 内联 hook
├── hotfix-plugin/                   # AGP 8 Gradle 插件（included build）
│   └── src/main/kotlin/.../plugin/
│       ├── HotfixPlugin.kt          # id 'com.demo.hotfix.instrument'：编译期插桩
│       ├── HotfixExtension.kt       # DSL: hotfix { instrumentPackages = [...] }
│       ├── HotfixClassVisitorFactory.kt / HotfixClassVisitor.kt / HotfixMethodVisitor.kt
│       ├── HotfixPatchPlugin.kt     # id 'com.demo.hotfix.patch'：打补丁流水线
│       ├── HotfixPatchExtension.kt  # DSL: hotfixPatch { baseVersion / loaderClass }
│       └── PatchOverrideGenerator.kt # ASM 生成分发类（CalculatorPatch）
├── app/                             # Demo App
│   └── src/main/
│       ├── java/com/demo/app/
│       │   ├── MainActivity.kt
│       │   └── Calculator.kt        # 被修类（有 bug，插件自动插桩）
│       └── cpp/native_bug.c         # 被修 native 函数（compute_add，有 bug）
├── patch-src/                       # 补丁源码（由 hotfix-patch 插件驱动打包）
│   ├── patched/com/demo/app/Calculator.kt  # ★ 开发者唯一手写的补丁：修好的原始类
│   ├── kotlin/com/demo/patch/
│   │   ├── PatchesLoaderImpl.kt     # 列出被修类
│   │   └── Mappings.kt              # 混淆名映射（由插件按 mapping.txt 生成）
│   ├── res_patch/                   # 改了字符串的资源补丁
│   ├── cpp/patch_native.c           # native 补丁函数（patched_add）
│   └── build.gradle                 # 应用 id 'com.demo.hotfix.patch'
├── serve_patches.sh                 # 本机补丁 HTTP server（配合 adb reverse）
└── settings.gradle                  # pluginManagement { includeBuild 'hotfix-plugin' }
```

---

## 核心机制

### 代码补丁（Java/Kotlin）

`hotfix-plugin` 在编译期（R8 之前）用 AGP 8 Instrumentation API + ASM 自动改写目标包下的每个类：

- 注入静态字段 `public static volatile IpChange $ipChange`
- 每个方法头注入：`if ($ipChange != null) return $ipChange.ipcDispatch("Owner.name(desc)", args)`
- 跳过 `<init>/<clinit>`、abstract/native/synthetic/bridge 方法及 R/BuildConfig

接入方（`app/build.gradle`）：
```groovy
hotfix {
    instrumentPackages = ['com.demo.app']   // 要插桩的包前缀列表
}
```

补丁侧（`patch-src/build.gradle`）：
```groovy
hotfixPatch {
    // baseVersion 不填则自动从 :app 的 versionName 读取
    // loaderClass 默认 "com.demo.patch.PatchesLoaderImpl"
}
```

开发者**只需写修好的原始类**（`patched/com/demo/app/Calculator.kt`，纯业务）。`PatchOverrideGenerator` 在打包期自动生成异名分发类 `CalculatorPatch`（实现 `IpChange`）——之所以用异名类，是因为 `DexClassLoader` parent-first，补丁 dex 里的同名类会被宿主屏蔽。

### 混淆（R8）处理

插桩在 R8 之前，带来三个处理点，已全部覆盖：

1. **methodId 用原始类名**（R8 不改写字符串常量，不要开 `-adaptclassstrings`）。补丁分发类的 switch 也用原始名，自动对齐。
2. **`Class.forName` 需要混淆后类名**。插件解析 `mapping.txt` 生成 `Mappings.CALCULATOR`，未混淆时回退原名。
3. **`$ipChange` 字段不能被 R8 删除**。`consumer-rules.pro` 用 `-keepclassmembers` 保住字段名，阻止 R8 把 `if ($ipChange != null)` 分支常量折叠掉。

### Native 补丁（arm64）

近距 trampoline 方案：

1. 扫 `/proc/self/maps` 在目标函数 ±128 MB 内找空闲页，`mmap` 为 trampoline
2. Trampoline 写 64 位绝对跳转：`LDR X17,#8 ; BR X17 ; <8 字节补丁地址>`
3. 目标函数入口只改写 **4 字节**（一条 `B <trampoline>`），不会写穿相邻短函数

全局 hook 表用 `pthread_mutex_t` 保护，同一目标函数重复 hook 时复用 trampoline 页（只更新补丁地址）。

### SDK 线程模型

- `Hotfix.init()` **必须在主线程调用**（内部 `check(Looper.myLooper() == Looper.getMainLooper())`）
- `install*` 在后台线程调用：InputStream 读取在调用线程完成，补丁应用 marshal 到主线程
- 补丁文件落盘用 tmp + `renameTo` 原子写，崩溃不丢已有补丁

---

## 如何运行

环境：SDK platform-34 + build-tools + NDK + arm64 设备/模拟器，JDK 17，Gradle Wrapper 已内置。

### Debug（不混淆）

```bash
# 1. 安装 App
./gradlew :app:installDebug

# 2. 打补丁产物
./gradlew :patch-src:assemblePatchDebug
# 产物：patch-src/build/patch/debug/patch.dex
#        patch-src/build/patch/debug/patch_res.apk
#        patch-src/build/patch/libpatch.so

# 3a. adb 直接推（调试用）
adb shell mkdir -p /sdcard/Android/data/com.demo.app/files/hotfix
adb push patch-src/build/patch/debug/patch.dex      /sdcard/Android/data/com.demo.app/files/hotfix/
adb push patch-src/build/patch/debug/patch_res.apk  /sdcard/Android/data/com.demo.app/files/hotfix/
adb push patch-src/build/patch/libpatch.so          /sdcard/Android/data/com.demo.app/files/hotfix/

# 3b. 或用 HTTP server 模拟网络下发
adb reverse tcp:8080 tcp:8080
./serve_patches.sh            # App 点"应用补丁"按钮会从 http://127.0.0.1:8080/debug/ 拉取
```

打开 App → 点三个按钮看到 bug 值（-1 / 旧文案 / -1）→ 点"★ 应用补丁"→ 再点三个按钮 → 结果变正确（5 / 新文案 / 5）。

### Release（开 R8 混淆）

```bash
# 1. 先构建 release APK（产出 mapping.txt）
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

# 2. 打 release 补丁（插件自动读取 mapping.txt 生成混淆名映射）
./gradlew :patch-src:assemblePatchRelease
# 产物：patch-src/build/patch/release/patch.dex
#        patch-src/build/patch/release/patch_res.apk
#        patch-src/build/patch/libpatch.so

# 3. 推送（同 debug，改 debug → release）
adb shell mkdir -p /sdcard/Android/data/com.demo.app/files/hotfix
adb push patch-src/build/patch/release/patch.dex     /sdcard/Android/data/com.demo.app/files/hotfix/
adb push patch-src/build/patch/release/patch_res.apk /sdcard/Android/data/com.demo.app/files/hotfix/
adb push patch-src/build/patch/libpatch.so           /sdcard/Android/data/com.demo.app/files/hotfix/
# 或: adb reverse tcp:8080 tcp:8080 && ./serve_patches.sh
```

冷启动测试：补丁安装成功后，force-stop 重启 App，三条路径应自动恢复（`auto-apply persisted * patch` 日志可验证）。

---

各文件头部都有详细注释，建议从 `Hotfix.kt` → `JavaPatcher.kt` → `PatchOverrideGenerator.kt` 顺序阅读。
