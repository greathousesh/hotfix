package com.demo.app

/**
 * 补丁源 —— 开发者**直接编写修好的原始类**（同包同名、纯业务代码，无任何分发样板）。
 *
 * 打补丁时 hotfix-plugin 的 generatePatchClasses{V} 任务会：
 *  1. 取 AGP 编译本文件得到的 com/demo/app/Calculator.class
 *  2. PatchOverrideGenerator(ASM) 把它转成分发类 com.demo.patch.CalculatorPatch（实现 IpChange）
 *  3. d8 只把生成的分发类(+PatchesLoaderImpl)打进 patch.dex；**本类绝不进 dex**
 *     （宿主已加载同名类，parent-first 会屏蔽补丁里的同名类）。
 *
 * 约束：补丁方法体只能引用宿主**已存在**的字段/方法（热修复无法给已加载的类加新成员，
 * 那属于冷补丁/重启场景）。想验证补丁是否真的生效：把下面改回 a - b，重打补丁后结果应变回 -1。
 */
class Calculator {
    fun add(a: Int, b: Int): Int {
        val fixed = object {
            fun value(): Int = a + b
        }
        return fixed.value()
    }

    companion object {
        fun multiply(a: Int, b: Int): Int = a * b   // 修复：改回乘法
    }
}
