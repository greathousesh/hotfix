// MiniHotfix native 内联 hook（arm64，对应淘宝 tconhook/InlinePatch 的核心思想）。
//
// 生产级做法：**近距 trampoline + 入口只写 4 字节 B**，从而能 hook 任意短函数：
//   1) 在目标函数 ±128MB 内分配一页(扫 /proc/self/maps 找空隙)做 trampoline，里面放 64 位绝对跳转：
//          LDR X17,#8 ; BR X17 ; <8 字节补丁绝对地址>     —— 补丁可在任意远处
//   2) 目标函数入口只改写 **1 条指令(4 字节)**：B <trampoline>（arm64 直接分支 B 的范围 ±128MB）。
// 只动入口一条指令，即使目标函数只有 8 字节(release 的 compute_add = sub+ret)也不会写穿相邻函数——
// 这正是早期“入口直接写 16 字节绝对跳转”会在 release 把后一个函数 nativeAdd 写穿、导致 SIGILL 的根因。
//
// 仍是“整函数替换”：不保留“调用原函数”的能力。若要调原函数，还需把入口被覆盖的指令**重定位**
// 到一个返回 trampoline(修正 ADR/ADRP/B/LDR-literal 等 PC 相关指令)再跳回 target+4；本 demo 不需要。
//
// 仅 arm64 (__aarch64__)。其它 ABI 直接返回错误。

#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <inttypes.h>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

#define TAG "MiniHotfix"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

#ifdef __aarch64__
#define B_RANGE  (128 * 1024 * 1024)   // arm64 直接分支 B 的可达范围 ±128MB
#define NEAR_WIN (124 * 1024 * 1024)   // 找空闲页时留点余量，保证落在 B 范围内

// 扫 /proc/self/maps，在 [target-NEAR_WIN, target+NEAR_WIN] 内找一页空闲虚拟地址给 trampoline。
// 选离 target 最近的空隙，再用 MAP_FIXED_NOREPLACE 精确占位(不会覆盖已有映射)。
static void *alloc_near_page(uintptr_t target) {
    const size_t page = (size_t) sysconf(_SC_PAGESIZE);
    const uintptr_t lo = (target > (uintptr_t) NEAR_WIN) ? target - NEAR_WIN : page;
    const uintptr_t hi = target + NEAR_WIN;

    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) { LOGE("open /proc/self/maps failed"); return NULL; }

    char line[512];
    uintptr_t prev_end = lo;
    uintptr_t best = 0, best_dist = (uintptr_t) -1;
    while (fgets(line, sizeof(line), f)) {
        uintptr_t s, e;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &s, &e) != 2) continue;
        if (s > prev_end) {                         // 空隙 [prev_end, s) 与窗口求交
            uintptr_t cs = prev_end > lo ? prev_end : lo;
            uintptr_t ce = s < hi ? s : hi;
            cs = (cs + page - 1) & ~(page - 1);     // 向上对齐到页
            if (cs + page <= ce) {
                uintptr_t d = cs > target ? cs - target : target - cs;
                if (d < best_dist) { best_dist = d; best = cs; }
            }
        }
        if (e > prev_end) prev_end = e;
        if (prev_end >= hi) break;
    }
    fclose(f);
    if (!best) return NULL;

    void *m = mmap((void *) best, page, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
    if (m == MAP_FAILED) return NULL;
    if (m != (void *) best) { munmap(m, page); return NULL; }  // 内核未尊重地址 -> 放弃
    return m;
}

static int do_inline_hook(void *target, void *patch) {
    const size_t page = (size_t) sysconf(_SC_PAGESIZE);
    uintptr_t addr = (uintptr_t) target;

    // 1) 近距 trampoline：64 位绝对跳转到补丁(补丁可在任意远处)。
    void *tramp = alloc_near_page(addr);
    if (!tramp) { LOGE("no free page within +/-128MB of target"); return -3; }
    uint32_t *t = (uint32_t *) tramp;
    t[0] = 0x58000051;                  // LDR X17, #8
    t[1] = 0xD61F0220;                  // BR  X17
    uint64_t pa = (uint64_t) patch;
    memcpy(&t[2], &pa, sizeof(pa));     // 8 字节补丁绝对地址
    __builtin___clear_cache((char *) tramp, (char *) tramp + 16);
    if (mprotect(tramp, page, PROT_READ | PROT_EXEC) != 0) {
        LOGE("mprotect tramp RX failed"); munmap(tramp, page); return -4;
    }

    // 2) 目标入口只写 4 字节 B -> trampoline（只覆盖 1 条指令，短函数也安全）。
    intptr_t delta = (intptr_t) ((uintptr_t) tramp - addr);
    if (delta < -(intptr_t) B_RANGE || delta >= (intptr_t) B_RANGE) {
        LOGE("trampoline out of B range: delta=%td", delta); return -5;
    }
    uint32_t br = 0x14000000u | (((uint32_t) (delta >> 2)) & 0x03FFFFFFu);   // B imm26

    uintptr_t pstart = addr & ~(page - 1);
    uintptr_t pend = (addr + 4 + page - 1) & ~(page - 1);
    if (mprotect((void *) pstart, pend - pstart, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("mprotect target RWX failed"); return -2;
    }
    *(uint32_t *) target = br;
    __builtin___clear_cache((char *) target, (char *) target + 4);
    mprotect((void *) pstart, pend - pstart, PROT_READ | PROT_EXEC);

    LOGI("hooked: target=%p -> tramp=%p -> patch=%p (B delta=%td)", target, tramp, patch, delta);
    return 0;
}
#endif

JNIEXPORT jint JNICALL
Java_com_demo_hotfix_nativehook_NativeHotfix_nativeInlineHook(
        JNIEnv *env, jclass clz,
        jstring jTargetLib, jstring jTargetSym,
        jstring jPatchSo, jstring jPatchSym) {
#ifndef __aarch64__
    LOGE("inline hook demo only supports arm64");
    return -100;
#else
    const char *targetLib = (*env)->GetStringUTFChars(env, jTargetLib, 0);
    const char *targetSym = (*env)->GetStringUTFChars(env, jTargetSym, 0);
    const char *patchSo   = (*env)->GetStringUTFChars(env, jPatchSo, 0);
    const char *patchSym  = (*env)->GetStringUTFChars(env, jPatchSym, 0);
    int ret = -1;

    // 1. 找到“被修 so”中目标函数地址（so 已被 App 加载，用 RTLD_DEFAULT/重新 dlopen 都可）
    char libname[256];
    snprintf(libname, sizeof(libname), "lib%s.so", targetLib);
    void *h_target = dlopen(libname, RTLD_NOW); // 已加载则返回同一句柄
    if (!h_target) { LOGE("dlopen target %s failed: %s", libname, dlerror()); goto done; }
    void *target = dlsym(h_target, targetSym);
    if (!target) { LOGE("dlsym target %s failed", targetSym); goto done; }

    // 2. dlopen 补丁 so，找到替换函数
    void *h_patch = dlopen(patchSo, RTLD_NOW);
    if (!h_patch) { LOGE("dlopen patch %s failed: %s", patchSo, dlerror()); goto done; }
    void *patch = dlsym(h_patch, patchSym);
    if (!patch) { LOGE("dlsym patch %s failed", patchSym); goto done; }

    // 3. 内联 hook
    ret = do_inline_hook(target, patch);
    LOGI("inline hook %s -> %s ret=%d", targetSym, patchSym, ret);

done:
    (*env)->ReleaseStringUTFChars(env, jTargetLib, targetLib);
    (*env)->ReleaseStringUTFChars(env, jTargetSym, targetSym);
    (*env)->ReleaseStringUTFChars(env, jPatchSo, patchSo);
    (*env)->ReleaseStringUTFChars(env, jPatchSym, patchSym);
    return ret;
#endif
}
