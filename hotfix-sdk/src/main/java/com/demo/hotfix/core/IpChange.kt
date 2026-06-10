package com.demo.hotfix.core

/**
 * 方法重定向接口（对应淘宝 InstantPatch 的 IpChange）。
 *
 * 注意：方法名用 `ipcDispatch`（不是淘宝的 `ipc$dispatch`）——因为 Kotlin 标识符不允许含 `$`，
 * 连反引号也不行，且 @JvmName 不能用在接口抽象方法上。我们自己的 SDK 用合法名即可，
 * 只要插件注入的 invokeInterface 与补丁实现都用同一个名字。
 *
 * 编译期插件给每个类注入 `public static volatile IpChange $ipChange;`（该字段名带 `$`，
 * 由 ASM 直接写字节码，不经 Kotlin 编译器，所以不受标识符限制），并在每个方法头插入：
 * ```
 * val ip = ClassXxx.`$ipChange`
 * if (ip != null) return ip.ipcDispatch("ClassXxx.method(desc)", arrayOf(this, arg1, ...))
 * ```
 */
interface IpChange {

    /**
     * @param methodId 方法唯一标识（类名+方法签名）
     * @param args     约定 args[0]=this（实例方法）或省略（静态方法），其后为实参（基本类型已装箱）
     * @return 新方法体返回值（基本类型会被装箱）
     */
    fun ipcDispatch(methodId: String, args: Array<Any?>): Any?
}
