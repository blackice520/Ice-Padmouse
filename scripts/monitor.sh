#!/bin/bash
# JoyMouse 监控 v2
# - 常驻 logcat 抓取到 logcat.log（本设备 logcat 可读，此前"荣耀 logcat 加密"结论有误）
# - 每 15 秒采样：进程 PID / exit-info 强停计数 / ZRHung 强停次数 / 无障碍服务是否被吊销
# - 检测到进程变化或强停时，落全量现场快照到 monitor.log
# 用法: bash scripts/monitor.sh   （RE_ENABLE=0 可关闭自动重新启用无障碍服务）
ADB=/home/yupd/android-dev/sdk/platform-tools/adb
DIR=/home/yupd/andorid
OUT=$DIR/monitor.log
LCAT=$DIR/logcat.log
PKG=com.joymouse.app
RE_ENABLE=${RE_ENABLE:-1}

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$OUT"; }

# ---- 常驻 logcat 抓取（已运行则复用） ----
if [ ! -f "$DIR/.logcat.pid" ] || ! kill -0 "$(cat "$DIR/.logcat.pid" 2>/dev/null)" 2>/dev/null; then
  nohup $ADB logcat -v threadtime > "$LCAT" 2>&1 &
  echo $! > "$DIR/.logcat.pid"
  sleep 1
fi

latest_exit_ts() {
  $ADB shell dumpsys activity exit-info "$PKG" 2>/dev/null \
    | grep -oE 'timestamp=[0-9-]+ [0-9:.]+' | head -1
}
force_stop_count() {
  $ADB shell dumpsys activity exit-info "$PKG" 2>/dev/null | grep -c 'reason=10'
}
svc_enabled() {
  $ADB shell settings get secure enabled_accessibility_services 2>/dev/null \
    | tr -d '\r\n' | grep -q "com.joymouse.app" && echo yes || echo no
}
zrhung_count() { grep -c "ZRHungService: BF and NFW forceStop package: $PKG" "$LCAT" 2>/dev/null || true; }

re_enable_svc() {
  if [ "$RE_ENABLE" = "1" ] && [ "$(svc_enabled)" = "no" ]; then
    local list new newlist
    list=$($ADB shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n')
    new="com.joymouse.app/com.joymouse.app.service.GestureAccessibilityService"
    if [ -z "$list" ] || [ "$list" = "null" ]; then newlist="$new"; else newlist="$list:$new"; fi
    $ADB shell settings put secure enabled_accessibility_services "$newlist" 2>/dev/null
    $ADB shell settings put secure accessibility_enabled 1 2>/dev/null
    log "!!! 无障碍服务已被系统吊销，自动重新启用"
  fi
}

snapshot() {
  echo "--- exit-info ---" >> "$OUT"
  $ADB shell dumpsys activity exit-info "$PKG" 2>/dev/null \
    | grep -E 'timestamp=|reason=|description=' | head -6 >> "$OUT"
  echo "--- app events.log ---" >> "$OUT"
  $ADB shell run-as "$PKG" tail -40 files/events.log 2>/dev/null >> "$OUT"
  echo "--- app keys.log ---" >> "$OUT"
  $ADB shell run-as "$PKG" tail -12 files/keys.log 2>/dev/null >> "$OUT"
  echo "--- app gestures.log ---" >> "$OUT"
  $ADB shell run-as "$PKG" tail -12 files/gestures.log 2>/dev/null >> "$OUT"
  echo "--- app scroll.log ---" >> "$OUT"
  $ADB shell run-as "$PKG" tail -12 files/scroll.log 2>/dev/null >> "$OUT"
  echo "--- app crash.log ---" >> "$OUT"
  $ADB shell run-as "$PKG" tail -12 files/crash.log 2>/dev/null >> "$OUT"
  echo "--- logcat tail (filtered) ---" >> "$OUT"
  tail -3000 "$LCAT" 2>/dev/null \
    | grep -E 'joymouse|ZRHung|AppEye|AppOps|IHwWindowManager|InputDispatcher|AccessibilityManagerService|ActivityManager|WindowManager' \
    | tail -150 >> "$OUT"
  echo "--- window focus ---" >> "$OUT"
  $ADB shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' >> "$OUT"
  echo "--- input focus ---" >> "$OUT"
  $ADB shell dumpsys input 2>/dev/null | grep -A4 'FocusedWindows' | head -6 >> "$OUT"
}

echo "===== JoyMouse monitor v2 start $(date '+%Y-%m-%d %H:%M:%S') =====" > "$OUT"
PREV_PID=$($ADB shell pidof "$PKG" 2>/dev/null | tr -d '\r\n')
PREV_COUNT=$(force_stop_count)
PREV_ZR=$(zrhung_count)
PREV_CRASH=$($ADB shell run-as "$PKG" cat files/crash.log 2>/dev/null | wc -l)
log "baseline pid=$PREV_PID force_stop=$PREV_COUNT zrhung=$PREV_ZR svc=$(svc_enabled) latest_exit=$(latest_exit_ts)"
re_enable_svc

while true; do
  sleep 15
  PID=$($ADB shell pidof "$PKG" 2>/dev/null | tr -d '\r\n')
  COUNT=$(force_stop_count)
  ZR=$(zrhung_count)
  CRASH_LINES=$($ADB shell run-as "$PKG" cat files/crash.log 2>/dev/null | wc -l)
  EXIT_TS=$(latest_exit_ts)

  if [ "${CRASH_LINES:-0}" -gt "${PREV_CRASH:-0}" ] 2>/dev/null; then
    log "!!! JAVA CRASH detected (crash.log grew ${PREV_CRASH} -> ${CRASH_LINES})"
    snapshot
    PREV_CRASH=$CRASH_LINES
  fi

  if [ "$PID" != "$PREV_PID" ] || [ "$COUNT" != "$PREV_COUNT" ] || [ "$ZR" != "$PREV_ZR" ]; then
    log "!!! PROCESS CHANGE: pid $PREV_PID -> $PID ; force_stop $PREV_COUNT -> $COUNT ; zrhung $PREV_ZR -> $ZR ; latest_exit=$EXIT_TS"
    snapshot
    PREV_PID=$PID
    PREV_COUNT=$COUNT
    PREV_ZR=$ZR
  else
    log "ok pid=$PID force_stop=$COUNT zrhung=$ZR svc=$(svc_enabled) latest_exit=$EXIT_TS"
  fi
  re_enable_svc
done
