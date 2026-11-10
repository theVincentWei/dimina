#!/usr/bin/env bash
# smoke-redirect.sh — Dimina redirectTo 事件链路设备冒烟（2026-11-11 断链事故回归守卫）
#
# 背景：updatePath 原位复用旧桥导致 redirectTo 换页后页内 tap 的 render→service
# 链路静默死亡（三层：桥不重建 / invoke 分发按页 id 找不到桥回调 / messageInvoke
# bridgeId 守卫误拦）。三层修复后的信号：
#   ✓ 换页出现 "starting fresh bridge (redirectTo)"
#   ✓ tap 后 service 收到事件（DiminaRenderBridge publish 带 methodName）
#   ✗ 不得出现 "NO invoke handler" / "DROP msg from service (bridgeId mismatch)"
#
# 用法：在一个终端跑本脚本，然后（同一设备上）人工或 adb 驱动任一小程序走一次
# redirectTo 并点击页内按钮；窗口期结束输出 PASS/FAIL。
#   ./scripts/smoke-redirect.sh [SERIAL] [窗口秒数=60]
#
# 依赖：adb 在 PATH（或 ANDROID_ADB 环境变量指定）；设备已装含本修复的 dsh-go。
set -euo pipefail

SERIAL=${1:-}
WINDOW=${2:-60}
ADB="${ANDROID_ADB:-adb}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"

echo "[smoke] watching logcat for ${WINDOW}s — 现在去触发一次 redirectTo 并点击页内按钮…"

TMP=$(mktemp)
"$ADB" logcat -c
"$ADB" logcat -v time >"$TMP" &
LOGPID=$!
trap 'kill $LOGPID 2>/dev/null || true' EXIT
sleep "$WINDOW"
kill $LOGPID 2>/dev/null || true

FRESH=$(grep -c "starting fresh bridge (redirectTo)" "$TMP" || true)
TAPS=$(grep -cE '"methodName":"(on[A-Z])[^\"]*"' "$TMP" || true)
NOHANDLER=$(grep -c "NO invoke handler" "$TMP" || true)
DROPPED=$(grep -c "DROP msg from service" "$TMP" || true)

echo "[smoke] fresh-bridge redirects: $FRESH | service taps delivered: $TAPS | unhandled invokes: $NOHANDLER | dropped service invokes: $DROPPED"

if [ "$FRESH" -ge 1 ] && [ "$TAPS" -ge 1 ] && [ "$NOHANDLER" -eq 0 ] && [ "$DROPPED" -eq 0 ]; then
	echo "[smoke] PASS"
	exit 0
fi
echo "[smoke] FAIL — 见上方计数与 $TMP 完整日志"
exit 1
