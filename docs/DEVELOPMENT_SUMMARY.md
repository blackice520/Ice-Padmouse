# Ice Padmouse 开发总结与经验（v1.0 迭代记录）

> 手柄鼠标映射 APK：无障碍手势注入 + 悬浮控制台 + 物理手柄映射，免 root。
> 参考应用：Gamepad Mouse v1.3.0（已逆向分析，独立实现，未复制代码）。

## 一、最终架构

```
GestureAccessibilityService（无障碍服务）
├── dispatchGesture 手势注入（点击/双击/长按/滑动/滚动/链式拖拽）
├── filterKeyEvents 全局按键通道 ← 手柄按键统一入口（不依赖窗口焦点）
└── TYPE_ACCESSIBILITY_OVERLAY 窗口族（无需悬浮窗权限）
    ├── 光标窗口（FLAG_NOT_TOUCHABLE 穿透）
    ├── 手柄焦点窗（1×1 可聚焦，捕获摇杆轴 MotionEvent）
    ├── 悬浮控制台（虚拟摇杆 + 模式键 + 自定义按键显隐）
    ├── 自定义悬浮按键（最多 12 个）
    └── 动作选择面板

OverlayController
├── 60fps 主循环：速度积分 / 拖拽状态机 / 滚动 / 空闲超时
├── 注入可靠性：触摸挂起 → 手指全抬补发 → 失败自动重试(5次)
├── 注入穿透：注入期间本应用窗口临时 FLAG_NOT_TOUCHABLE
└── 输入模型：左摇杆速度(死区+幂次曲线) / 右摇杆滚动 / 按键映射表
```

## 二、关键设计决策

| 决策 | 原因 |
|------|------|
| TYPE_ACCESSIBILITY_OVERLAY 替代 TYPE_APPLICATION_OVERLAY | 免悬浮窗权限（对齐参考应用），只留 1 个必需权限 |
| 手柄焦点窗(1×1)替代 IME 方案 | IME 收不到摇杆轴事件；焦点窗能同时收按键+轴 |
| 按键统一走服务 onKeyEvent | 不依赖焦点窗是否拿到焦点，杜绝"按键丢了" |
| 速度模型替代位移模型 | 摇杆帽到边缘后位移归零；速度模型推住不放持续移动 |
| 注入前触摸挂起+重试 | 注入手势会被真实触摸打断（系统行为） |
| 光标/面板坐标 FLAG_LAYOUT_IN_SCREEN | 窗口坐标与注入手势统一为屏幕坐标系，否则点击偏一个状态栏高度 |
| 光标 FLAG_NOT_TOUCHABLE | 否则注入点击打在自己光标窗口上被吞掉 |
| 注入期间窗口穿透 | 点击精确落在光标处，不被自己的悬浮窗挡住/触发悬浮键 |
| 移除"触摸休眠" | 参考应用的行为，但对虚拟控制台是灾难（碰一下鼠标就没了） |

## 三、踩坑实录（按时间线）

1. **WSL 环境**：JDK17 + cmdline-tools + Gradle8.13 + AGP8.10.1，无线 adb 调试（配对端口≠连接端口！）；荣耀 logcat 加密 → 用 `dumpsys dropbox` 抓崩溃。
2. **PairIP 逆向**：参考应用带 Google PairIP 保护，jadx 部分还原；核心逻辑（窗口 flags、输入模型、曲线公式、v() 误点击）均可读。
3. **参考应用缺陷**：唤出鼠标时 `v()` 会在 (0,200) 注入点击（用户反馈"左上角自动点击"）——我们的实现刻意排除。
4. **负速度曲线**：照搬公式 `v=norm^2.5*c+(1-c)*norm`，默认灵敏度下 c>1 导致中段速度为负——光标反向乱飘。改为恒正的 `norm^exp`。
5. **点击被自己吞**：光标窗口忘了 FLAG_NOT_TOUCHABLE → 注入点击打在光标上。参考应用 flags=792 里就有。
6. **坐标错位**：窗口缺 FLAG_LAYOUT_IN_SCREEN → 窗口坐标原点在状态栏下方，点击偏上一个状态栏高度。
7. **注入被打断**：真实触摸会取消注入手势 → 手指挂起机制 + 5 次重试。
8. **速度失控**：速度换算少了时间维度（54k px/s）→ 重新标定。
9. **状态不同步**："光标"按钮与"鼠标激活"是两个开关 → 统一语义，按摇杆自动唤醒。
10. **服务重启不恢复**：onServiceConnected 残留 canDrawOverlays 检查（权限已移除）→ 永远 false，控制台不自动恢复。
11. **窗口复用崩溃**：隐藏面板只移除不清理引用，重显示复用已移除窗口 → 彻底清空+全新重建。
12. **配置迁移**：默认映射升级用 mappingVersion 机制，避免覆盖用户自定义。
13. **闪退排查**：全局崩溃落盘 files/crash.log（荣耀 logcat 加密的替代方案）。
14. **MagicOS 无障碍看门狗强停（真·闪退根因）**：`dumpsys activity exit-info` 显示 system_server 以 `reason=10 USER REQUESTED / subreason=21 FORCE STOP` 每 1~2 分钟强停前台服务。参考应用（gamepadmouse）也有强停记录，但都是 `iAwareF[SystemManagerSwipeUp]`（用户划掉）和 `iAwareF[LowMem]`（后台低内存回收）——是正常清理；Ice Padmouse 是 `state=empty` 无 iAware 标签的异常强停。根因是 XML 里静态声明 `accessibilityFlags=flagRequestFilterKeyEvents`（全局按键拦截）被看门狗判定高危。修复：XML 去掉静态 flag，改为运行时 `setServiceInfo(FLAG_REQUEST_FILTER_KEY_EVENTS)`（对齐参考应用）。
15. **焦点窗常驻聚焦导致手指无法操作**：去掉静态 filterKeyEvents 后，为保 L3 唤出把焦点窗改成常驻聚焦，结果焦点窗持续抢焦点、干扰手指触摸。修复：焦点窗跟随鼠标开关切换聚焦（`if (!mouseActive) FLAG_NOT_FOCUSABLE`，对齐参考应用），L3 唤出靠运行时 filterKeyEvents 的 onKeyEvent。
16. **滚动限流形同虚设**：`scrollGestureBusy` 状态把 `scrollChainCount` 清零，导致「连续 N 次后暂停」从未生效。修复：只在摇杆真正回中（`scrollLen <= dead`）时重置计数与冷却。
17. **闪退真凶（v1.1 铁证定位）**：logcat 其实可读（"荣耀 logcat 加密"结论有误）。每次"闪退"时 logcat 都有 `ZRHungService: BF and NFW forceStop package: com.joymouse.app`——MagicOS 的 `ZrHung.AppEyeFocusWindow` 焦点探针（system_server 内）强停应用并吊销无障碍服务（`enabled_accessibility_services` 被清）。触发条件：旧实现**每次注入手势都切换窗口焦点**（focusable=false → 派发 → 400ms → focusable=true），滚动/连点时每秒十几次焦点振荡，反复制造"无焦点窗口(NFW)"状态。铁证：同设备参考应用 Gamepad Mouse 在同一时段**从未**被 ZRHungService 强停（只有 iAwareF 低内存/划掉清理）。修复：a) 注入时默认不动焦点（对齐参考应用）；b) 可选"焦点租约"clickFocusRelease（整批注入最多让出一次焦点、2.5s 冷却，默认关，兼容失焦不响应点击的游戏）；c) XML 去掉静态 accessibilityFlags，运行时 setServiceInfo 启用（flags=0x20 实测生效）；d) 全部诊断日志改后台单线程异步写入+2MB 滚动截断（主线程零文件 I/O，也消除日志导致的卡顿帧）；e) 5 秒心跳日志（强停瞬间现场=日志尾部）；f) monitor.sh v2：常驻 logcat 抓取 + 强停时全量快照（exit-info/app日志/logcat 过滤/窗口焦点）。
18. **焦点长期霸占 = IME 目标异常（v1.1 第二轮）**：去掉注入抖动后仍被强停（16:25:50/16:27:36/16:29:09，约 90s 一次）。`dumpsys window` 实测发现：手柄焦点窗长期持焦时，系统把它当作 `imeInputTarget`（不可见 1×1 窗口成为输入法目标，属异常输入状态，探针敏感）。且经查证 `AccessibilityService` 并无 onGenericMotionEvent 公共 API——摇杆轴事件确实只能靠焦点窗，无法彻底删除。修复：**焦点按需保持**——手柄按键/摇杆事件到达即续期，空闲 5s 让出焦点（窗口保留）；按键仍走 filterKeyEvents 服务通道，只损失"空闲后第一次纯摇杆拨动"。另：`cmd appops set com.joymouse.app CREATE_ACCESSIBILITY_OVERLAY allow`（荣耀私有 op，每次强停瞬间都伴随 `AppOps: Operation not started` 报错，显式放行疑似有效）。

## 四、与参考应用的功能对照

| 功能 | Gamepad Mouse | Ice Padmouse |
|------|:---:|:---:|
| 左摇杆移光标（死区+曲线） | ✅ | ✅（曲线已修正） |
| 右摇杆滚动 | ✅ | ✅ |
| A/B/X/Y 按键映射 | ✅ | ✅（X=唤出光标 Y=播放/暂停 B=返回） |
| L3 唤出鼠标 | ✅ | ✅ |
| 逐键自定义映射 UI | ✅ | ✅（手柄图例点按改映射） |
| 光标样式 | 6色+自定义PNG | 6色 |
| 虚拟控制台（无手柄可用） | ❌ | ✅ |
| 自定义悬浮按键 | ❌ | ✅ |
| DeX 外接屏 | ✅(Pro) | 未实现（后续） |
| 锁屏黑屏时钟 | ✅(Pro) | 未实现 |

## 五、验证与质量

- JVM 单元测试 9 例（配置序列化、迁移、映射表完整性、默认值合理性）
- lint 零错误；debug/release 双构建；apksigner 签名验证
- 真机验证：荣耀 AAK-AN00 / Android 16 / 无线 adb
- 崩溃可诊断：全局 UncaughtExceptionHandler 落盘 + dropbox

## 六、经验总结

1. **参考应用逆向是最高效的学习方式**：先读清单（权限/服务）→ 再读核心类 → 复刻架构而非代码。PairIP 壳挡不住思路。
2. **无障碍注入的三大坑**：窗口坐标空间（LAYOUT_IN_SCREEN）、自己窗口吞触摸（NOT_TOUCHABLE/穿透）、真实触摸打断注入（挂起+重试）。
3. **虚拟手柄 ≠ 物理手柄**：手指是输入源时，注入手势与手指抢时间——必须等手指完全抬起。
4. **数学公式要验证**：参考应用的曲线公式在默认参数下是坏的——抄公式必须代入默认值算一遍。
5. **状态单一来源**：光标可见/鼠标激活/控制台显示各自独立开关必然不同步——统一语义或联动。
6. **国产 ROM 调试**：logcat 可能加密 → dropbox 抓崩溃、崩溃落盘、run-as 读文件。
7. **升级要迁移**：改默认值必须带版本号，否则用户旧配置不生效且会被覆盖。
19. **游戏模式（v1.5，不使用焦点窗）**：手游需要全程保持窗口焦点，而普通模式靠焦点窗捕获物理摇杆，二者冲突（系统级限制）。新增游戏模式：`applyFocusViewFocusable(true)` 在 gameMode 下直接拦截，游戏全程保持焦点；手柄按键经 filterKeyEvents 全局通道直达控制器（与鼠标激活状态无关）。功能：屏幕点位（GamePoint，可拖动编辑，最多 10 个）、按键直连映射（点击/长按/上滑/下滑/左滑/右滑，滑动按住连发 300ms；另支持系统动作）、滑动距离可调（80-400dp）。物理摇杆在游戏模式下不可用（免 root 硬限制，键事件无需焦点、轴事件必须有焦点窗）。
20. **强杀已停止**：对齐参考应用（flags=0x62 含 REQUEST_TOUCH_EXPLORATION_MODE 读屏类豁免 + 15×15 焦点窗 + 注入前后零焦点切换）后，16:51:55 最后一次强杀，此后持续数小时零强杀（90 秒必杀周期被打破）。

---

# v1.3~v1.5 迭代总结（闪退根治 + 游戏模式）

## 一、闪退机制（最终结论）

**现象**：应用"闪退"实为系统强杀。`dumpsys activity exit-info` 每次都是
`reason=10 USER REQUESTED / subreason=21 FORCE STOP from pid 3009 (system)`，
crash.log 恒为空（无 Java 崩溃）。强杀后系统还会**吊销无障碍服务**（
enabled_accessibility_services 被清），用户需重新开启。

**真凶**：logcat 其实可读（此前"荣耀 logcat 加密"结论错误）。每次强杀瞬间：
`ZRHungService: BF and NFW forceStop package: com.joymouse.app`
—— MagicOS 的 `ZrHung.AppEyeFocusWindow` 焦点探针（system_server 内）。

**修复历程**（按时间）：
1. 旧实现每次注入手势都切换焦点（focusable=false→派发→400ms→true），
   滚动时每秒十几次焦点振荡 → 探针判异常 → 90 秒必杀周期。
2. 去掉注入焦点切换 → 仍被杀。
3. 焦点按需保持（空闲 5s 让出）→ 仍被杀。
4. **转折点：完全对齐参考应用 Gamepad Mouse（同设备从未被该探针强杀）**：
   - 服务 flags=0x62：REQUEST_FILTER_KEY_EVENTS(0x20) + INCLUDE_NOT_IMPORTANT_
     VIEWS(0x2) + **REQUEST_TOUCH_EXPLORATION_MODE(0x40，读屏类豁免)**；
     运行时 setServiceInfo，XML 不静态声明 accessibilityFlags；
   - 焦点捕获窗 1×1 → 15×15，flags 逐位对齐（NOT_TOUCH_MODAL|LAYOUT_IN_SCREEN|
     WATCH_OUTSIDE_TOUCH，去掉 LAYOUT_NO_LIMITS）；
   - 鼠标激活期间零焦点切换（v1.4 默认始终持有；可选"空闲 2 分钟让出"给
     需要焦点的游戏）。
5. 结果：最后一次 ZRHung 强杀 16:51:55，之后数小时零强杀。

**设备侧辅助**：CREATE_ACCESSIBILITY_OVERLAY appop 显式 allow、电池白名单、
RUN_ANY_IN_BACKGROUND、monitor v2 强杀后自动重新启用无障碍服务。
无 root（荣耀不开放解锁 BL），ZRHungService 在 system_server 内无法禁用。

## 二、本轮踩坑清单

1. logcat 可读性误判（以为加密）→ 整个定位的钥匙，先验证诊断通道再下结论。
2. 无线 adb 频繁断连、端口每次变 → 排查前先 `adb devices`；手机重启后
   无线调试默认关闭。
3. 焦点窗长期持焦会成为 imeInputTarget（dumpsys window 可见）→ 异常输入态。
4. AccessibilityService 无 onGenericMotionEvent 公共 API（编译期证实）→
   摇杆轴事件免 root 只能靠焦点窗 → 游戏模式只能放弃物理摇杆（硬限制）。
5. 拖动悬浮窗用局部坐标 → 窗口移动改变局部坐标映射 → 位置振荡"震动"→
   必须用 rawX/rawY 屏幕坐标（面板拖动/点位标记/自定义按键三处统一）。
6. 注入重试风暴：并发手势互相取消 + 取消视为失败重试 → 两条重试链
   attempt=2,2,3,3,4,4,5,5 来回取消（events.log 实锤）→ 游戏模式注入
   不重试 + 滑动单飞；普通模式重试 5→2。
7. 播放/暂停：getActiveSessions 无通知使用权时永远空 → 一直走双击兜底
   无效 → 改 dispatchMediaKeyEvent(KEYCODE_MEDIA_PLAY_PAUSE) 媒体键派发。
8. setGameMode 状态双写：Activity 先写配置再调控制器，控制器 early-return
   吞掉副作用（标记未同步隐藏）→ 状态唯一入口归控制器，UI 只转发。
9. ACTION_OUTSIDE 事件坐标恒为 (0,0)，不是幽灵触摸。
10. 用户划掉最近任务 = force-stop（iAwareF[SystemManagerSwipeUp]）+服务吊销
    —— 与探针强杀要区分（exit-info 的 description 标签）。
11. 主线程高频文件 I/O 日志拖卡顿 → AppLog 后台单线程 + 2MB 滚动截断 + 5s 心跳。
12. "闪退后过一会又打开"其实是 monitor v2 每 15s 检测服务被吊销并自动重新启用。
13. exit-info 里 reason=16 PACKAGE UPDATED 是 adb install -r 的正常强停。
14. 快速双击滑动键 → 持续点按屏幕（重试风暴，见 6）。

## 三、游戏模式（v1.5，不使用焦点窗）

需求：手游需全程保持窗口焦点，而物理摇杆捕获必须焦点窗 → 冲突。
方案：gameMode=true 时 `applyFocusViewFocusable(true)` 全拦截，游戏全程
持焦；手柄按键走 filterKeyEvents 全局通道（无需焦点）直连屏幕点位：
点击/长按/上滑/下滑/左滑/右滑（滑动按住连发 300ms、单飞防风暴）。
点位编辑：悬浮标记拖动（rawX/rawY）、长按弹 GameBindingPicker 面板
（无障碍窗口、不抢焦点）、透明度 0-100%、"显示点位标记"随游戏模式
开关同步。物理摇杆在游戏模式下不可用（系统级限制）。

## 四、v2.0 发布前收尾修复

21. **返回键无效（真因）**：performGlobalAction(BACK) result=true 但无反应——MagicOS
    的 BACK/NOTIFICATIONS/QUICK_SETTINGS 以注入按键实现，注入键路由到当前焦点窗
    （我们的 15×15 捕获窗）被自己吞掉。且让出焦点后触发动作的按键事件本身会在
    ~74ms 内经 renewFocusHold 抢回焦点（events.log 实锤）。修复：全局动作期间
    focusSuppressUntil(1.5s) 抑制按键抢焦点，让出焦点→300ms→注入→700ms→取回。
22. **十字键映射无效**：该手柄十字键从不产生 KeyEvent（keys.log 零条 code=19/20），
    而是以 AXIS_HAT_X/Y 轴事件随摇杆 MotionEvent 上报。修复：onGamepadMotion 解析
    HAT 轴，边沿检测翻译为十字键按下/抬起。教训：键位"没反应"先查事件是否到达、
    以什么形式到达（服务 logKeyEvent + 焦点窗 [view-raw] 双路原始日志）。
23. **音量连按不流畅**：两个限流误伤——全局动作 1000ms 冷却 + 按键 250ms 消抖。
    音量是纯 AudioManager 调用（无注入风险）→ 豁免冷却；消抖仅对真实 KeyEvent
    双通道生效（HAT 翻译是单通道）。教训：限流要按"是否系统高危操作"分类，
    不要一刀切。
24. **播放/暂停与手柄断联**：dispatchMediaKeyEvent 经过蓝牙 AVRCP 通道，应用重启后
    首次派发与手柄 AVRCP 通道建立竞态 → 断联一次，重连后正常（用户实测
    "重启后第一次按 Y 必断"）。尝试通知使用权+transportControls 方案：本 ROM 上
    授权后 getActiveSessions 仍被拒/空，Y 反而失效 → 整体回退。保留媒体键方案，
    首次断联作为已知瑕疵记录。教训：ROM 对 MediaSessionManager 权限的收紧
    不可假设 AOSP 行为；改动前先小步验证。
25. **无线 adb 反复掉线**：排查期间设备端口多次变化，多次误判"日志为空"。
    教训：任何 adb 命令失败先 `adb devices` 确认连接，再下结论。
26. **发布**：versionCode=3 / versionName=2.0，assembleRelease 签名验证通过。
