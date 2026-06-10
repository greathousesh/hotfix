#!/usr/bin/env bash
# 本机补丁下发 server —— 配合 App 的网络下发热修复（替代 adb 推送）。
#
# 目录布局(serve root = patch-src/build/patch)：
#   <root>/debug/patch.dex   <root>/debug/patch_res.apk
#   <root>/release/patch.dex <root>/release/patch_res.apk
#   <root>/libpatch.so       (native 补丁与变体无关，共用)
# App 按 BuildConfig.BUILD_TYPE 请求 /{debug|release}/patch.dex 等。
#
# 用法：
#   1) 先生成补丁产物:
#        ./gradlew :patch-src:assemblePatchDebug
#        ./gradlew :app:assembleRelease && ./gradlew :patch-src:assemblePatchRelease   # release 需 mapping
#   2) 把设备 127.0.0.1:PORT 映射到本机(USB 真机/模拟器通用):
#        adb reverse tcp:8080 tcp:8080
#   3) 起 server:
#        ./serve_patches.sh            # 默认 8080
#        ./serve_patches.sh 9000       # 自定义端口(记得同步 adb reverse 与 App 里的 PATCH_SERVER)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)/patch-src/build/patch"
PORT="${1:-8080}"

if [ ! -d "$ROOT" ]; then
  echo "补丁目录不存在: $ROOT" >&2
  echo "先跑 ./gradlew :patch-src:assemblePatchDebug (和/或 assemblePatchRelease) 生成补丁。" >&2
  exit 1
fi

echo "补丁 server 根目录: $ROOT"
echo "监听 http://127.0.0.1:$PORT  (别忘了: adb reverse tcp:$PORT tcp:$PORT)"
echo "可用补丁:"
ls -1 "$ROOT"/*/patch.dex "$ROOT"/libpatch.so 2>/dev/null | sed "s#$ROOT/#  /#" || true
exec python3 -m http.server "$PORT" --directory "$ROOT"
