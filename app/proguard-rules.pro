# 宿主 App 的 R8 规则。
# 核心热修复规则由 hotfix-sdk 的 consumer-rules.pro 自动合并（保 $ipChange / IpChange / PatchesLoader）。
#
# 设计选择：本 demo 让业务类（com.demo.app.Calculator 等）正常参与混淆，
# 补丁通过 build_patch.sh 解析 mapping.txt 用“混淆后类名”生成 PatchesLoaderImpl 的 key。
# 因此这里不 keep 业务类。
#
# 切勿开启 -adaptclassstrings（保持 methodId 字符串为原始类名，与补丁分发一致）。

# 保留 native 方法与被 hook 的 native 符号相关 JNI 入口（demo 的 NativeBug 走 JNI）
-keepclasseswithmembernames class * {
    native <methods>;
}
