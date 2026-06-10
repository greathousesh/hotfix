# === MiniHotfix consumer rules（接入 SDK 后自动合并进宿主的 R8 配置）===

# 1) 重定向接口 / 加载器接口：补丁在运行时按这些类型调用，名字与成员必须稳定
-keep class com.demo.hotfix.core.IpChange { *; }
-keep class com.demo.hotfix.core.PatchesLoader { *; }

# 2) 插件注入的静态字段 $ipChange —— 热修复能否生效的关键两点：
#    (a) JavaPatcher 反射按字面量 "$ipChange" 查找该字段 → 名字不能被混淆；
#    (b) 该字段只由反射写入（R8 看不到写入点），若不 keep，R8 的成员值传播会判定它
#        “恒为 null”，从而把每个方法头的 `if ($ipChange != null) {...}` 分支整段删除，
#        热修复彻底失效。被 keep 的成员会被 R8 视作“可能被外部(反射/JNI)修改”，
#        从而禁用该常量折叠 —— 分支得以保留。
-keepclassmembers class ** {
    public static volatile com.demo.hotfix.core.IpChange $ipChange;
}

# 3) 切勿开启 -adaptclassstrings：
#    插桩发生在 R8 之前，方法头烤入的 methodId 字符串是“原始类名”
#    （如 com.demo.app.Calculator.add(II)I），补丁 CalculatorPatch 的 switch 也按原名匹配。
#    R8 默认不改写字符串常量；一旦开启 -adaptclassstrings 会把它改成混淆名，导致 methodId 失配。
