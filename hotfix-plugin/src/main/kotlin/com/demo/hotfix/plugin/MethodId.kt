package com.demo.hotfix.plugin

/**
 * methodId 的**唯一**格式来源：`owner(点分) + "." + 方法名 + 描述符`，如 `com.demo.app.Calculator.add(II)I`。
 *
 * 两处必须严格一致、否则补丁会**静默匹配不上**（不报错、只是热修不生效）：
 *  - [HotfixMethodVisitor] 把它烤进宿主方法头的 `ipcDispatch(methodId, ...)` 字符串常量；
 *  - [PatchOverrideGenerator] 用它生成补丁分发类 `ipcDispatch` 里的 if-链 key。
 * R8 默认不改写字符串常量，所以两边都用**原始(未混淆)** owner 内部名即可对齐。
 */
internal fun methodId(ownerInternal: String, name: String, desc: String): String =
    ownerInternal.replace('/', '.') + "." + name + desc
