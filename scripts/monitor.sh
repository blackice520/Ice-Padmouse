#!/bin/bash
# JoyMouse 崩溃/强停监控脚本
# 每 20 秒采样一次进程 PID 与强停记录，检测到异常时抓取详细快照。
ADB=/home/yupd/android-dev/sdk/platform-tools/adb
OUT=/home/yupd/andorid/monitor.log
PKG=com.joymouse.app

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$OUT"; }

# 获取最新的 exit-info 时间戳（秒级字符串），无则空
latest_exit_ts() {
  $ADB shell dumpsys activity exit-info "$PKG" 2>/dev/null \
    | grep -oE 'timestamp=[0-9-]+ [0-9:.]+' | head -1
}

# 强停总数（reason=10 USER REQUESTED）
force_stop_count() {
  $ADB shell dumpsys activity exit-info "$PKG" 2>/dev/null | grep -c 'reason=10'
}

echo "===== JoyMouse monitor start $(date '+%Y-%m-%d %H:%M:%S') =====" > "$OUT"

PREV_PID=$($ADB shell pidof "$PKG" 2>/dev/null | tr -d '\r\n')
PREV_COUNT=$(force_stop_count)
log "baseline pid=$PREV_PID force_stop_count=$PREV_COUNT latest_exit=$(latest_exit_ts)"

while true; do
  sleep 20
  PID=$($ADB shell pidof "$PKG" 2>/dev/null | tr -d '\r\n')
  COUNT=$(force_stop_count)
  EXIT_TS=$(latest_exit_ts)
  CRASH=$($ADB shell run-as "$PKG" cat files/crash.log 2>/dev/null | tail -5)

  if [ -n "$CRASH" ]; then
    log "!!! JAVA CRASH detected (crash.log):"
    echo "$CRASH" >> "$OUT"
  fi

  if [ "$PID" != "$PREV_PID" ] || [ "$COUNT" != "$PREV_COUNT" ]; then
    log "!!! PROCESS CHANGE: pid $PREV_PID -> $PID ; force_stop $PREV_COUNT -> $COUNT ; latest_exit=$EXIT_TS"
    echo "--- newest exit-info entry ---" >> "$OUT"
    $ADB shell dumpsys activity exit-info "$PKG" 2>/dev/null \
      | grep -E 'timestamp=|reason=|description=' | head -6 >> "$OUT"
    echo "--- gestures.log tail ---" >> "$OUT"
    $ADB shell run-as "$PKG" tail -8 files/gestures.log 2>/dev/null >> "$OUT"
    echo "--- scroll.log tail ---" >> "$OUT"
    $ADB shell run-as "$PKG" tail -8 files/scroll.log 2>/dev/null >> "$OUT"
    echo "--- keys.log tail ---" >> "$OUT"
    $ADB shell run-as "$PKG" tail -8 files/keys.log 2>/dev/null >> "$OUT"
    PREV_PID=$PID
    PREV_COUNT=$COUNT
  else
    log "ok pid=$PID force_stop_count=$COUNT latest_exit=$EXIT_TS"
  fi
done
