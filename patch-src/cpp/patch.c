// 补丁 native 库 libpatch.so：提供修复后的函数 patched_add。
// 内联 hook 会把宿主 libnative_bug.so 里 compute_add 的入口跳到这里。
__attribute__((visibility("default")))
int patched_add(int a, int b) {
    return a + b;   // ★ 修复（原 compute_add 是 a - b）
}
