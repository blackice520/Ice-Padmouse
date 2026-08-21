# JoyMouse 手柄鼠标映射（Android）

在手机上模拟电脑鼠标操作的免 root 手柄/按键映射应用。

## 功能

- **虚拟摇杆控制台**（悬浮窗，可拖动位置、调节透明度）
  - 移动模式：摇杆移动光标，轻点摇杆 = 左键单击
  - 拖拽模式：按住摇杆 = 按住左键并拖动（进度条、窗口、游戏内拖拽）
  - 滚轮模式：上下推摇杆 = 滚轮滚动页面
- **物理手柄映射**（蓝牙/USB，免 root）：注册为"无界面输入法"捕获手柄按键
  - 在系统输入法设置中启用并切换到「JoyMouse 手柄输入法」即可
  - 默认映射：A=单击，B=长按(右键)，X=双击，Y=滚轮上，L1=滚轮下，R1=音量+，
    L2/R2=左右滑动，方向键=移动光标，SELECT=主页，START=最近任务，LOGO=显示/隐藏控制台
  - 限制：摇杆轴事件不进入输入法通道（同 Panda Gamepad Pro 方案），摇杆请用悬浮控制台
- **鼠标光标**：屏幕中心圆点光标，空闲 6 秒自动隐藏（可关），摇杆一动即出现
- **自定义悬浮按键**（最多 12 个，任意位置/大小）
  - 鼠标动作：单击 / 双击 / 长按 / 上滑 / 下滑 / 左滑 / 右滑 / 滚轮上 / 滚轮下
  - 系统动作：主页 / 返回 / 最近任务 / 截屏 / 通知栏 / 快捷设置 / 音量±
  - 显示/隐藏控制台、无动作
- **编辑模式**：拖动按键改位置、点击选动作、调整大小、删除、一键添加
- 长按任意自定义按键可直接进入编辑
- 控制台位置记忆（拖动后自动保存，旋转屏幕/重启后恢复）
- 配置本地持久化（SharedPreferences + JSON）
- JVM 单元测试：`gradle testDebugUnitTest`（配置序列化往返、非法值钳制、动作 ID 回退等 6 例）

## 权限（仅三项，均需在设置中手动开启）

| 权限 | 用途 |
|------|------|
| 无障碍服务（手势模拟） | 通过 `AccessibilityService.dispatchGesture` 注入点击/拖拽/滑动手势（免 root 唯一途径） |
| 悬浮窗 | 显示控制台、鼠标光标、自定义按键（`TYPE_APPLICATION_OVERLAY`） |
| 输入法（可选，仅物理手柄需要） | 「JoyMouse 手柄输入法」捕获蓝牙/USB 手柄按键并映射为鼠标动作 |

> 无障碍服务不读取屏幕内容（`canRetrieveWindowContent="false"`），仅在操作控制台时注入手势。

## 技术要点

- 语言 Kotlin，minSdk 26 / targetSdk & compileSdk 36（Android 16）
- 无 root、无 native 库、无第三方 SDK
- 连续拖拽（按住-移动-松开）用 `GestureDescription` 链式笔划实现：
  `StrokeDescription(..., willContinue=true)` → `prev.continueStroke(path, ...)` 续接，
  终笔划 `willContinue=false` 结束并抬起手指（API 语义已核对 Android 16 AOSP 源码）
- 构建：AGP 8.10.1 + Gradle 8.13 + JDK 17

## 在 WSL 中构建

```bash
export JAVA_HOME=~/android-dev/tools/jdk-17.0.20+8
export ANDROID_HOME=~/android-dev/sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH

cd JoyMouse
gradle assembleDebug        # 调试包（可直装）: app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease      # 签名正式包: app/build/outputs/apk/release/app-release.apk
```

> release 签名：本地开发密钥 `~/android-dev/joymouse-release.jks`
> （别名 joymouse / 口令 joymouse2025，可用环境变量 `JOYMOUSE_KEYSTORE` / `JOYMOUSE_KEYSTORE_PASS` / `JOYMOUSE_KEY_ALIAS` / `JOYMOUSE_KEY_PASS` 覆盖）。
> 正式对外发布请务必更换为私人密钥并妥善保管。

## 安装到手机

手机开启「开发者选项 → USB 调试 / 无线调试」。

**方式一：无线调试（推荐，无需 USB 直通）**
```bash
# 手机: 开发者选项 → 无线调试 → 使用配对码配对设备
adb pair <手机IP>:<配对端口> <配对码>
adb connect <手机IP>:<无线调试端口>
adb install app/build/outputs/apk/debug/app-debug.apk
```

**方式二：USB 直通 WSL（Windows 需 usbipd-win）**
```powershell
winget install usbipd
usbipd list                        # 找到手机的 busid
usbipd bind --busid <busid>
usbipd attach --wsl --busid <busid>
```
```bash
# WSL 内
adb devices                       # 应能看到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

安装后在 App 内依次开启两项权限即可使用。

## 项目结构

```
app/src/main/java/com/joymouse/app/
├── MainActivity.kt                  # 权限引导 + 设置
├── config/AppConfig.kt              # 动作枚举/按键模型/配置持久化
├── service/GestureAccessibilityService.kt  # 手势注入（点击/拖拽/滑动/全局动作）
├── service/GamepadImeService.kt     # 物理手柄输入法（按键捕获与映射）
└── overlay/
    ├── OverlayController.kt         # 悬浮窗总控（控制台/光标/编辑模式）
    ├── JoystickView.kt              # 虚拟摇杆
    ├── MappedButtonView.kt          # 自定义按键
    └── ActionPicker.kt              # 动作选择面板
```
