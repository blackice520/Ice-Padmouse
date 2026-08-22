# Ice Padmouse 手柄鼠标映射（Android）· v2.1

在手机上模拟电脑鼠标操作的免 root 手柄映射应用，另含**游戏模式**（不使用焦点窗、手游全程保持焦点）。独立实现，无第三方 SDK。

## 功能

### 鼠标模式（L3 唤出，物理手柄直控）

- **物理手柄**（蓝牙/USB，免 root）：可聚焦无障碍窗口捕获手柄按键**和模拟摇杆**
  - 左摇杆 = 移动光标（死区 + 幂次曲线 + 灵敏度），右摇杆 = 滚动页面
  - 按键映射可在应用内逐键自定义（手柄图例点按改映射）
  - **L3（默认）= 唤出/关闭鼠标**；唤出过程绝不产生任何自动点击
- **虚拟摇杆控制台**（悬浮窗，可拖动位置、调节透明度）
  - 移动模式：摇杆移动光标，轻点摇杆 = 左键单击
  - 拖拽模式：按住摇杆 = 按住左键并拖动（进度条、窗口、游戏内拖拽）
  - 滚轮模式：上下推摇杆 = 滚轮滚动页面
- **鼠标光标**：6 种颜色可选，空闲自动隐藏（可关），摇杆一动即出现
- **自定义悬浮按键**（最多 12 个，任意位置/大小）
  - 鼠标动作：单击 / 双击 / 长按 / 上滑 / 下滑 / 左滑 / 右滑 / 滚轮上 / 滚轮下
  - 系统动作：主页 / 返回 / 最近任务 / 截屏 / 通知栏 / 快捷设置 / 音量± / 静音 / 播放暂停
- 编辑模式：拖动按键改位置、点击选动作、调整大小、删除、一键添加
- 控制台位置记忆、配置本地持久化（SharedPreferences + JSON）

### 游戏模式（不使用焦点窗）

针对"手游必须保持窗口焦点"的场景：游戏全程保持焦点，手柄按键**直接点击/滑动屏幕上的点位**，无需鼠标光标。

- **屏幕点位**：最多 10 个，悬浮标记拖动定位（可调透明度 0~100%，可常显于游戏画面且穿透触摸）
- **按键直连映射**：18 个手柄键 ×（点击 / 长按 / 上滑 / 下滑 / 左滑 / 右滑 / 系统动作）
  - 滑动支持**按住连发**（300ms 周期，单飞防互相取消）；滑动距离可调（80~400dp）
- **长按标记**：在当前屏幕直接弹出绑定面板（不跳回应用、不抢焦点）
- 说明：物理摇杆在游戏模式下不可用（免 root 的摇杆轴捕获必须持有窗口焦点，系统级限制）；十字键若以 HAT 轴上报，同样依赖焦点窗

## 权限（仅一项必需，设置中手动开启）

| 权限 | 用途 |
|------|------|
| 无障碍服务（手势模拟） | `dispatchGesture` 注入点击/拖拽/滑动手势；光标与手柄捕获使用无障碍专用窗口 `TYPE_ACCESSIBILITY_OVERLAY`（**不需要**"显示在其他应用上层"权限） |

> 无障碍服务不读取屏幕内容（`onAccessibilityEvent` 为空），仅在操作时注入手势。

## 与 MagicOS 和平共处（重要）

荣耀/MagicOS 自带 `ZRHungService`（`ZrHung.AppEyeFocusWindow` 焦点探针，运行在 system_server 内），会对"窗口焦点行为异常"的无障碍应用强杀并吊销无障碍服务（`dumpsys activity exit-info` 里表现为 `reason=10 USER REQUESTED / subreason=21 FORCE STOP`）。本应用的防强停设计：

- 服务 flags=0x62（含 `REQUEST_TOUCH_EXPLORATION_MODE` 读屏类豁免），运行时 `setServiceInfo` 设置，XML **不**静态声明 `accessibilityFlags`
- 焦点捕获窗 15×15、窗口 flags 与久经考验的同类应用逐位一致
- 鼠标激活期间**零焦点切换**（可选"空闲 2 分钟让出焦点"给需要焦点的游戏）
- 注入失败不重试风暴（普通模式最多重试 1 次；游戏模式单次派发、滑动单飞）

已知瑕疵：应用重启后**第一次**按"播放/暂停"可能触发手柄蓝牙 AVRCP 竞态导致手柄断联一次，手柄重连后正常（系统蓝牙栈行为，免 root 无法根除）。

## 技术要点

- 语言 Kotlin，minSdk 26 / targetSdk & compileSdk 36（Android 16）
- 无 root、无 native 库、无第三方 SDK
- 连续拖拽（按住-移动-松开）用 `GestureDescription` 链式笔划实现：
  `StrokeDescription(..., willContinue=true)` → `prev.continueStroke(path, ...)` 续接，
  终笔划 `willContinue=false` 结束并抬起手指
- 拖动类悬浮窗一律使用屏幕坐标 `rawX/rawY` 计算位移（局部坐标会因窗口移动形成位置振荡）
- 诊断日志走后台单线程异步写入（`AppLog`），2MB 滚动截断 + 5 秒心跳；
  本机 logcat 可读，`scripts/monitor.sh` 常驻抓取并在强停瞬间落全量现场快照
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
> （别名 joymouse，可用环境变量 `JOYMOUSE_KEYSTORE` / `JOYMOUSE_KEYSTORE_PASS` / `JOYMOUSE_KEY_ALIAS` / `JOYMOUSE_KEY_PASS` 覆盖）。
> 正式对外发布请务必更换为私人密钥并妥善保管。

## 安装到手机

手机开启「开发者选项 → 无线调试」。

```bash
# 手机: 开发者选项 → 无线调试 → 使用配对码配对设备（配对一次即可）
bash scripts/pair.sh
adb connect <手机IP>:<无线调试端口>
adb install app/build/outputs/apk/debug/app-debug.apk
```

安装后：开启无障碍服务 → 设置页将应用加入电池优化白名单 → 开始使用。
（无线调试在手机重启后会关闭，需重新开启；端口每次变化属正常。）

## 调试与监控

```bash
bash scripts/monitor.sh    # 常驻监控：logcat 抓取 + 强停瞬间全量现场快照
                           # RE_ENABLE=0 可关闭"无障碍服务被吊销后自动重新启用"
```

应用内日志（`adb shell run-as com.joymouse.app cat files/<name>`）：

| 文件 | 内容 |
|------|------|
| events.log | 心跳 / 焦点切换 / 鼠标开关 / 注入 / 游戏模式事件 |
| keys.log | 原始按键（含焦点窗 [view-raw] 来源诊断） |
| gestures.log | 手势派发与全局动作结果（`result=true/false`） |
| scroll.log | 滚轮链计数与冷却 |
| crash.log | Java 崩溃栈（若有） |

系统强停原因判别：`adb shell dumpsys activity exit-info com.joymouse.app` ——
`ZRHungService: BF and NFW` = 焦点探针强杀（本应用已规避）；
`iAwareF[SystemManagerSwipeUp]` = 用户划掉最近任务；`iAwareF[LowMem]` = 低内存回收；`PACKAGE UPDATED` = 安装更新。

## 更新日志

### v2.1

- **更换应用图标**：由 Gemini 生成的新图标（`assets/launcher-icon-1024x1024.jpg` 归档保存），生成各密度 mipmap PNG 图标（`mipmap-*/ic_launcher.png`）；移除自适应图标 XML（荣耀桌面会把空前景矢量回退成默认机器人 logo），替换原矢量鼠标图标
- versionCode=4 / versionName=2.1

### v2.0

- **新增游戏模式（不使用焦点窗）**：手游全程保持焦点；屏幕点位 + 手柄按键直连映射（点击/长按/四向滑动+按住连发）；长按标记当前屏幕弹绑定面板；标记透明度 0~100%、可选常显
- **根治 MagicOS 强杀**：对齐同类久经考验应用的 flags=0x62（读屏类豁免）/15×15 焦点窗/零焦点切换，90 秒必杀周期彻底打破
- **修复返回键无效**：全局动作执行期间抑制按键抢焦点，注入的返回键不再被自己的焦点窗吞掉
- **修复十字键映射无效**：HAT 轴（AXIS_HAT_X/Y）边沿检测翻译为十字键事件（部分手柄十字键不产生 KeyEvent）
- **修复播放/暂停无效**：改派发系统媒体键（KEYCODE_MEDIA_PLAY_PAUSE）
- **修复拖动抖动**：悬浮窗拖动改用屏幕坐标（局部坐标反馈振荡）
- **修复快速连按重试风暴**：注入不重试/单飞，普通模式重试降为 1 次
- 音量连按不限流、焦点保持选项化（默认始终持有）、日志异步化 + 心跳、monitor v2

### v1.1

- 修复 MagicOS 看门狗强停的初步尝试（运行时 setServiceInfo 等）
- 修复手指无法操作、滚动限流失效、全局动作限流

## 项目结构

```
app/src/main/java/com/joymouse/app/
├── MainActivity.kt                  # 设置 + 手柄映射 + 游戏模式页
├── AppLog.kt                        # 后台异步日志（2MB 滚动截断）
├── IcePadmouseApp.kt                # 全局崩溃落盘
├── config/AppConfig.kt              # 动作/按键/点位模型 + 配置持久化
├── service/GestureAccessibilityService.kt  # 手势注入 + 全局按键/动作
└── overlay/
    ├── OverlayController.kt         # 悬浮窗总控 + 60fps 主循环 + 游戏模式
    ├── GamepadInputView.kt          # 手柄焦点捕获视图（按键 + 摇杆轴）
    ├── JoystickView.kt              # 虚拟摇杆
    ├── MappedButtonView.kt          # 自定义按键
    ├── ActionPicker.kt              # 动作选择面板
    ├── GamePointView.kt             # 游戏模式点位标记
    └── GameBindingPicker.kt         # 游戏模式悬浮绑定面板
```
