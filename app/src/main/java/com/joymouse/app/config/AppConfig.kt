package com.joymouse.app.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 可分配给按键的动作 */
enum class Action(val id: String, val label: String, val isGlobal: Boolean = false) {
    CLICK("click", "单击"),
    DOUBLE_CLICK("dblclick", "双击"),
    LONG_PRESS("longpress", "长按"),
    SWIPE_UP("swipe_up", "向上滑动"),
    SWIPE_DOWN("swipe_down", "向下滑动"),
    SWIPE_LEFT("swipe_left", "向左滑动"),
    SWIPE_RIGHT("swipe_right", "向右滑动"),
    SCROLL_UP("scroll_up", "滚轮向上"),
    SCROLL_DOWN("scroll_down", "滚轮向下"),
    HOME("home", "主页", true),
    BACK("back", "返回", true),
    RECENTS("recents", "最近任务", true),
    SCREENSHOT("screenshot", "截屏", true),
    NOTIFICATIONS("notifications", "通知栏", true),
    QUICK_SETTINGS("quick_settings", "快捷设置", true),
    VOLUME_UP("vol_up", "音量加", true),
    VOLUME_DOWN("vol_down", "音量减", true),
    MUTE("mute", "静音", true),
    MEDIA_FORWARD("media_forward", "快进（双击屏右 80% 处）"),
    MEDIA_REWIND("media_rewind", "快退（双击屏左 20% 处）"),
    TOGGLE_MOUSE("toggle_mouse", "显示/隐藏鼠标"),
    TOGGLE_PANEL("toggle_panel", "显示/隐藏控制台"),
    NOOP("noop", "无动作");

    companion object {
        fun fromId(id: String?): Action = entries.firstOrNull { it.id == id } ?: NOOP
    }
}

/** 动作的短标签（用于手柄图例等紧凑场景） */
fun Action.shortLabel(): String = when (this) {
    Action.CLICK -> "单击"
    Action.DOUBLE_CLICK -> "双击"
    Action.LONG_PRESS -> "长按"
    Action.SWIPE_UP -> "上滑"
    Action.SWIPE_DOWN -> "下滑"
    Action.SWIPE_LEFT -> "左滑"
    Action.SWIPE_RIGHT -> "右滑"
    Action.SCROLL_UP -> "滚上"
    Action.SCROLL_DOWN -> "滚下"
    Action.HOME -> "主页"
    Action.BACK -> "返回"
    Action.RECENTS -> "任务"
    Action.SCREENSHOT -> "截屏"
    Action.NOTIFICATIONS -> "通知"
    Action.QUICK_SETTINGS -> "快捷"
    Action.VOLUME_UP -> "音量+"
    Action.VOLUME_DOWN -> "音量-"
    Action.MUTE -> "静音"
    Action.MEDIA_FORWARD -> "快进"
    Action.MEDIA_REWIND -> "快退"
    Action.TOGGLE_MOUSE -> "鼠标"
    Action.TOGGLE_PANEL -> "面板"
    Action.NOOP -> "无"
}

/** 一个可自定义的悬浮按键。x/y 为相对屏幕的百分比坐标 (0..1)。 */
data class MappedButton(
    val id: Long,
    var label: String,
    var action: Action,
    var x: Float,
    var y: Float,
    var sizeDp: Int
)

/** 全部用户配置 */
data class AppConfig(
    var cursorSpeed: Float = 6f,       // 虚拟摇杆光标速度倍率
    var scrollStep: Int = 280,         // 滚轮单次滑动像素
    var panelOpacity: Float = 0.85f,   // 控制台透明度 0.1..1
    var dragMode: Boolean = false,     // 摇杆默认是否拖拽模式
    var cursorAutoHide: Boolean = true,// 光标空闲自动隐藏
    var panelX: Float = -1f,           // 控制台位置（屏幕宽度比例，-1=默认居中偏下）
    var panelY: Float = -1f,           // 控制台位置（屏幕高度比例，-1=默认居中偏下）
    var mouseSpeed: Int = 50,          // 手柄光标速度 1..100
    var sensitivity: Int = 15,         // 手柄灵敏度 1..100（幂次曲线强度）
    var deadzone: Int = 15,            // 摇杆死区 0..50 (%)
    var scrollSpeed: Int = 100,        // 右摇杆滚动速度 1..100
    var mouseTimeout: Int = 0,         // 鼠标空闲自动关闭（秒，0=关闭）
    var accelTime: Int = 300,          // 摇杆加速时间 ms（0=无加速，越大起步越柔和）
    var cursorStyle: String = "orange",// 光标样式 orange/white/red/green/blue/black
    var swapAB: Boolean = false,       // 交换 A/B 键
    var hapticEnabled: Boolean = true, // 触觉反馈
    var vibrationIntensity: Int = 255, // 振动强度 0..255
    var toggleKey: String = "l3",      // 唤出/关闭鼠标的手柄键
    var buttonsVisible: Boolean = true,// 自定义按键是否显示
    var gamepadMap: MutableMap<String, String> = mutableMapOf(), // 手柄键名 -> 动作 id
    var buttons: MutableList<MappedButton> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        buttons.forEach { b ->
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("label", b.label)
                    .put("action", b.action.id)
                    .put("x", b.x.toDouble())
                    .put("y", b.y.toDouble())
                    .put("size", b.sizeDp)
            )
        }
        val gm = JSONObject()
        gamepadMap.forEach { (k, v) -> gm.put(k, v) }
        return JSONObject()
            .put("cursorSpeed", cursorSpeed.toDouble())
            .put("scrollStep", scrollStep)
            .put("panelOpacity", panelOpacity.toDouble())
            .put("dragMode", dragMode)
            .put("cursorAutoHide", cursorAutoHide)
            .put("panelX", panelX.toDouble())
            .put("panelY", panelY.toDouble())
            .put("mouseSpeed", mouseSpeed)
            .put("sensitivity", sensitivity)
            .put("deadzone", deadzone)
            .put("scrollSpeed", scrollSpeed)
            .put("mouseTimeout", mouseTimeout)
            .put("accelTime", accelTime)
            .put("cursorStyle", cursorStyle)
            .put("swapAB", swapAB)
            .put("hapticEnabled", hapticEnabled)
            .put("vibrationIntensity", vibrationIntensity)
            .put("toggleKey", toggleKey)
            .put("buttonsVisible", buttonsVisible)
            .put("gamepadMap", gm)
            .put("buttons", arr)
    }

    companion object {
        fun fromJson(s: String?): AppConfig {
            val cfg = AppConfig()
            if (s.isNullOrBlank()) return cfg
            return try {
                val o = JSONObject(s)
                cfg.cursorSpeed = o.optDouble("cursorSpeed", 6.0).toFloat()
                cfg.scrollStep = o.optInt("scrollStep", 280)
                cfg.panelOpacity = o.optDouble("panelOpacity", 0.85).toFloat().coerceIn(0.1f, 1f)
                cfg.dragMode = o.optBoolean("dragMode", false)
                cfg.cursorAutoHide = o.optBoolean("cursorAutoHide", true)
                cfg.panelX = o.optDouble("panelX", -1.0).toFloat()
                cfg.panelY = o.optDouble("panelY", -1.0).toFloat()
                cfg.mouseSpeed = o.optInt("mouseSpeed", 50).coerceIn(1, 100)
                cfg.sensitivity = o.optInt("sensitivity", 15).coerceIn(1, 100)
                cfg.deadzone = o.optInt("deadzone", 15).coerceIn(0, 50)
                cfg.scrollSpeed = o.optInt("scrollSpeed", 100).coerceIn(1, 100)
                cfg.mouseTimeout = o.optInt("mouseTimeout", 0)
                cfg.accelTime = o.optInt("accelTime", 300).coerceIn(0, 2000)
                cfg.cursorStyle = o.optString("cursorStyle", "orange")
                cfg.swapAB = o.optBoolean("swapAB", false)
                cfg.hapticEnabled = o.optBoolean("hapticEnabled", true)
                cfg.vibrationIntensity = o.optInt("vibrationIntensity", 255).coerceIn(0, 255)
                cfg.toggleKey = o.optString("toggleKey", "l3")
                cfg.buttonsVisible = o.optBoolean("buttonsVisible", true)
                val gm = o.optJSONObject("gamepadMap")
                if (gm != null) {
                    val it = gm.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        cfg.gamepadMap[k] = gm.optString(k, "noop")
                    }
                }
                val arr = o.optJSONArray("buttons") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    cfg.buttons.add(
                        MappedButton(
                            id = b.optLong("id", System.currentTimeMillis() + i),
                            label = b.optString("label", "键"),
                            action = Action.fromId(b.optString("action")),
                            x = b.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                            y = b.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                            sizeDp = b.optInt("size", 52)
                        )
                    )
                }
                cfg
            } catch (t: Throwable) {
                cfg
            }
        }
    }
}

/** 配置持久化（SharedPreferences + JSON，无需额外依赖） */
object ConfigStore {
    private const val PREFS = "joymouse_config"
    private const val KEY = "config_json"

    @Volatile
    private var cached: AppConfig? = null

    /** 进程内共享同一份配置对象（主界面与悬浮窗控制器读写一致） */
    fun load(context: Context): AppConfig {
        cached?.let { return it }
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cfg = AppConfig.fromJson(sp.getString(KEY, null))
        if (cfg.buttons.isEmpty()) cfg.buttons.addAll(defaultButtons())
        if (cfg.gamepadMap.isEmpty()) cfg.gamepadMap.putAll(defaultGamepadMap())
        cached = cfg
        return cfg
    }

    fun save(context: Context, config: AppConfig) {
        cached = config
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, config.toJson().toString()).apply()
    }

    /** 默认布局：三个常用鼠标动作键 */
    fun defaultButtons(): List<MappedButton> = listOf(
        MappedButton(1, "左键", Action.CLICK, 0.72f, 0.68f, 56),
        MappedButton(2, "双击", Action.DOUBLE_CLICK, 0.85f, 0.60f, 48),
        MappedButton(3, "长按", Action.LONG_PRESS, 0.85f, 0.78f, 48),
    )

    /**
     * 手柄默认映射（键名 -> 动作 id）。
     * 参考 gamepad mouse 的默认设置：十字键=音量/媒体，L1=静音，R2=截屏，
     * A=单击 B=长按(右键) X=双击 Y=滚轮上，L3=唤出/关闭鼠标，Start/Select=最近任务/主页。
     */
    fun defaultGamepadMap(): Map<String, String> = linkedMapOf(
        "a" to "click",
        "b" to "longpress",
        "x" to "dblclick",
        "y" to "scroll_up",
        "lb" to "mute",
        "rb" to "noop",
        "lt" to "noop",
        "rt" to "screenshot",
        "up" to "vol_up",
        "down" to "vol_down",
        "left" to "media_rewind",
        "right" to "media_forward",
        "start" to "recents",
        "select" to "home",
        "mode" to "toggle_panel",
        "l3" to "noop", // 特殊键：唤出/关闭鼠标（见 toggleKey）
        "r3" to "noop",
        "center" to "click",
    )

    /** 键名 -> KeyEvent keyCode */
    fun keyCodeOf(keyName: String): Int = when (keyName) {
        "a" -> android.view.KeyEvent.KEYCODE_BUTTON_A
        "b" -> android.view.KeyEvent.KEYCODE_BUTTON_B
        "x" -> android.view.KeyEvent.KEYCODE_BUTTON_X
        "y" -> android.view.KeyEvent.KEYCODE_BUTTON_Y
        "lb" -> android.view.KeyEvent.KEYCODE_BUTTON_L1
        "rb" -> android.view.KeyEvent.KEYCODE_BUTTON_R1
        "lt" -> android.view.KeyEvent.KEYCODE_BUTTON_L2
        "rt" -> android.view.KeyEvent.KEYCODE_BUTTON_R2
        "l3" -> android.view.KeyEvent.KEYCODE_BUTTON_THUMBL
        "r3" -> android.view.KeyEvent.KEYCODE_BUTTON_THUMBR
        "start" -> android.view.KeyEvent.KEYCODE_BUTTON_START
        "select" -> android.view.KeyEvent.KEYCODE_BUTTON_SELECT
        "mode" -> android.view.KeyEvent.KEYCODE_BUTTON_MODE
        "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
        "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        "center" -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
        else -> -1
    }

    /** keyCode -> 键名 */
    fun keyNameOf(keyCode: Int): String? =
        listOf("a", "b", "x", "y", "lb", "rb", "lt", "rt", "l3", "r3", "start", "select", "mode", "up", "down", "left", "right", "center")
            .firstOrNull { keyCodeOf(it) == keyCode }
}
