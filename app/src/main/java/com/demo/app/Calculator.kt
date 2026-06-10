package com.demo.app

/**
 * 被热修复的业务类（Kotlin）。
 *
 * 不写任何插桩代码：编译时 hotfix-plugin（ASM）会自动注入
 *  - 静态字段 `$ipChange`
 *  - add() 方法头的 `if ($ipChange != null) return $ipChange.ipcDispatch(...)`
 * 这里只写纯业务逻辑（含一个 bug）。
 *
 * 注意：Kotlin 类默认 final、方法默认 final，不影响 ASM 改写方法体与注入静态字段。
 */
class Calculator {
    fun add(a: Int, b: Int): Int = a - b   // BUG：本应 a + b，补丁会修复
}
