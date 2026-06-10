package com.demo.hotfix.core

/**
 * 补丁包里“生成的加载器”要实现的接口（对应淘宝 AppPatchesLoaderImpl）。
 */
interface PatchesLoader {

    /**
     * @return key = 被修类的“运行时类名”（混淆构建下为混淆名）；value = 该类的 $override（IpChange 实例）。
     */
    fun load(): Map<String, IpChange>

    /** 补丁针对的 base 版本，用于与宿主校验一致性（防混淆映射错位）。 */
    fun baseVersion(): String
}
