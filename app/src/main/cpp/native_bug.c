// 被修的 native 库：compute_add 有 bug。
// 注意 extern 可见 + 不内联，保证 dlsym 能按符号名找到、且内联 hook 能改写其序言。

#include <jni.h>

// noinline + 默认可见：保证 dlsym 能按符号名找到、且内联 hook 能改写其入口。
// 注意 release(-O2) 下本函数会被压成 2 条指令(sub w0,w0,w1; ret —— 仅 8 字节)，
// 紧随其后就是 nativeAdd。inline_hook.c 用「近距 trampoline + 入口只写 4 字节 B」来 hook，
// 只覆盖入口 1 条指令，因此即使这种极短函数也不会写穿相邻的 nativeAdd（生产级做法）。
__attribute__((noinline, visibility("default")))
int compute_add(int a, int b) {
    return a - b;   // BUG：应为 a + b
}

JNIEXPORT jint JNICALL
Java_com_demo_app_NativeBug_nativeAdd(JNIEnv *env, jobject thiz, jint a, jint b) {
    return compute_add(a, b);
}
