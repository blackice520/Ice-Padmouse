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
    TOGGLE_PANEL("toggle_panel", "显示/隐藏控制台"),
    NOOP("noop", "无动作");

    companion object {
        fun fromId(id: String?): Action = entries.firstOrNull { it.id == id } ?: NOOP
    }
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
    var cursorSpeed: Float = 6f,       // 光标速度倍率
    var scrollStep: Int = 280,         // 滚轮单次滑动像素
    var panelOpacity: Float = 0.85f,   // 控制台透明度 0.1..1
    var dragMode: Boolean = false,     // 摇杆默认是否拖拽模式
    var cursorAutoHide: Boolean = true,// 光标空闲自动隐藏
    var panelX: Float = -1f,           // 控制台位置（屏幕宽度比例，-1=默认居中偏下）
    var panelY: Float = -1f,           // 控制台位置（屏幕高度比例，-1=默认居中偏下）
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
        return JSONObject()
            .put("cursorSpeed", cursorSpeed.toDouble())
            .put("scrollStep", scrollStep)
            .put("panelOpacity", panelOpacity.toDouble())
            .put("dragMode", dragMode)
            .put("cursorAutoHide", cursorAutoHide)
            .put("panelX", panelX.toDouble())
            .put("panelY", panelY.toDouble())
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
}
