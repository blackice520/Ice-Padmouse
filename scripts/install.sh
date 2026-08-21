#!/usr/bin/env bash
# JoyMouse 一键安装脚本（WSL 中运行）
# 用法: bash scripts/install.sh
set -e

source ~/android-dev/env.sh

APK=/home/yupd/andorid/JoyMouse/app/build/outputs/apk/debug/app-debug.apk

echo "== 当前设备 =="
adb devices

if ! adb devices | grep -q "device$"; then
    echo ""
    echo "❌ 没有检测到已连接的设备。请先完成无线调试连接："
    echo "   1. 手机: 设置 → 开发者选项 → 无线调试 → 使用配对码配对设备"
    echo "   2. 电脑: adb pair <手机IP>:<配对端口>  → 输入配对码"
    echo "   3. 电脑: adb connect <手机IP>:<端口>"
    exit 1
fi

echo ""
echo "== 安装 APK =="
adb install -r "$APK"
echo ""
echo "✅ 安装完成！在手机上打开 JoyMouse，开启无障碍服务后即可使用。"
echo "   查看实时日志:  adb logcat --pid=\$(adb shell pidof com.joymouse.app)"
