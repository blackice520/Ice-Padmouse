#!/usr/bin/env bash
# JoyMouse 无线配对脚本
# 用法: bash scripts/pair.sh
# 注意: 先打开手机配对弹窗，再运行本脚本（配对码约 60 秒过期）
set -e

source ~/android-dev/env.sh

echo "=============================================="
echo "第 1 步（手机，先做）："
echo "  设置 → 开发者选项 → 无线调试 →"
echo "  点『使用配对码配对设备』→ 弹出配对窗口"
echo "  弹窗【不要关闭】，把弹窗里的信息填到下面"
echo "=============================================="
read -p "手机 IP（弹窗/主界面都有）: " IP
read -p "配对端口（弹窗里的端口）: " PORT
read -p "6 位配对码（弹窗里的）: " CODE

echo ""
echo "== 1/3 测试连通性 =="
if python3 -c "
import socket
s = socket.socket(); s.settimeout(3)
r = s.connect_ex(('$IP', int('$PORT')))
print('✅ 端口可达，继续' if r == 0 else '❌ 端口不可达')
exit(0 if r == 0 else 1)
"; then
    :
else
    echo ""
    echo "端口不可达，请检查："
    echo "  1. 配对弹窗是否还开着？（过期就重新点开，端口会变）"
    echo "  2. 手机和电脑是否连的同一个 WiFi？"
    echo "  3. 手机是否挂着企业 VPN/不同网络？（10.x 网段可能不通）"
    echo "  备选：手机开热点 → 电脑连手机热点 → 重新运行本脚本"
    exit 1
fi

echo ""
echo "== 2/3 执行配对 =="
printf '%s\n' "$CODE" | adb pair "$IP:$PORT"

echo ""
echo "== 3/3 完成 =="
echo "配对成功后，看手机『无线调试』主界面的 IP 地址与端口（例如 $IP:5555），然后："
echo "  adb connect $IP:5555"
echo "  bash scripts/install.sh"
