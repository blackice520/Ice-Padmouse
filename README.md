# JoyMouse 手柄鼠标映射（Android）· v1.1

在手机上模拟电脑鼠标操作的免 root 手柄/按键映射应用，独立实现。

## 功能

- **物理手柄**（蓝牙/USB，免 root）：可聚焦无障碍窗口捕获手柄按键**和模拟摇杆**
  - 左摇杆 = 移动光标（死区 + 幂次曲线 + 灵敏度），右摇杆 = 滚动页面
  - A=单击，B=长按(右键)，X=双击，Y=滚轮上，L1=静音，R2=截屏，
    十字键=音量/媒体，Select=主页，Start=最近任务
  - **L3（左摇杆按下）= 唤出/关闭鼠标**；唤出过程绝不产生任何自动点击
- **虚拟摇杆控制台**（悬浮窗，可拖动位置、调节透明度）
  - 移动模式：摇杆移动光标，轻点摇杆 = 左键单击
  - 拖拽模式：按住摇杆 = 按住左键并拖动（进度条、窗口、游戏内拖拽）
  - 滚轮模式：上下推摇杆 = 滚轮滚动页面
- **鼠标光标**：6 种颜色可选，空闲自动隐藏（可关），摇杆一动即出现
- **自定义悬浮按键**（最多 12 个，任意位置/大小）
  - 鼠标动作：单击 / 双击 / 长按 / 上滑 / 下滑 / 左滑 / 右滑 / 滚轮上 / 滚轮下
  - 系统动作：主页 / 返回 / 最近任务 / 截屏 / 通知栏 / 快捷设置 / 音量± / 静音 / 快进快退
- **编辑模式**：拖动按键改位置、点击选动作、调整大小、删除、一键添加
- 长按任意自定义按键可直接进入编辑
- 控制台位置记忆、配置本地持久化（SharedPreferences + JSON）
- JVM 单元测试 8 例：`gradle testDebugUnitTest`

## 权限（仅一项必需，设置中手动开启）

| 权限 | 用途 |
|------|------|
| 无障碍服务（手势模拟） | `dispatchGesture` 注入点击/拖拽/滑动手势；光标与手柄捕获使用无障碍专用窗口 `TYPE_ACCESSIBILITY_OVERLAY`（**不需要**"显示在其他应用上层"权限）；`filterKeyEvents` 在**运行时** `setServiceInfo` 动态开启，用于鼠标休眠时全局响应唤出键（L3） |

> 无障碍服务不读取屏幕内容（`onAccessibilityEvent` 为空），仅在操作时注入手势。
> `filterKeyEvents` 不在 XML 里静态声明（`accessibilityFlags`），避免被 MagicOS 无障碍看门狗判定高危强停——见 `GestureAccessibilityService.onServiceConnected`。

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

## 更新日志

### v1.1

- **修复闪退**：根因是 MagicOS 无障碍看门狗对「XML 静态声明 `flagRequestFilterKeyEvents`（全局按键拦截）+ 读屏幕内容 + 注入手势」判定高危，每隔 1~2 分钟强停应用。改为在运行时 `setServiceInfo` 动态开启 filterKeyEvents，仅用于鼠标休眠时响应 L3 唤出。
- **修复手指无法操作**：焦点窗改为跟随鼠标开关切换聚焦（鼠标关闭即 `FLAG_NOT_FOCUSABLE`，触摸/按键穿透给下层应用）。
- **修复滚动限流失效**：`scrollGestureBusy` 曾把滚动计数清零导致限流形同虚设，改为只在摇杆真正回中时重置；右摇杆/虚拟摇杆滚动加 6 次上限 + 1.2s 冷却。
- 全局系统动作（主页/返回/最近任务/截屏）限流提高到 1000ms。

## 项目结构

```
app/src/main/java/com/joymouse/app/
├── MainActivity.kt                  # 权限引导 + 设置
├── config/AppConfig.kt              # 动作枚举/按键模型/手柄映射/配置持久化
├── service/GestureAccessibilityService.kt  # 手势注入（点击/拖拽/滑动/全局动作）+ 全局唤出键
└── overlay/
    ├── OverlayController.kt         # 悬浮窗总控 + 60fps 手柄主循环（速度/拖拽/滚动）
    ├── GamepadInputView.kt          # 手柄焦点捕获视图（按键 + 摇杆轴）
    ├── JoystickView.kt              # 虚拟摇杆
    ├── MappedButtonView.kt          # 自定义按键
    └── ActionPicker.kt              # 动作选择面板
```
