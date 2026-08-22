package com.joymouse.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.joymouse.app.config.Action
import com.joymouse.app.config.ConfigStore
import com.joymouse.app.overlay.OverlayController
import com.joymouse.app.service.GestureAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var tvAccess: TextView
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvAccelValue: TextView
    private lateinit var tvOpacityValue: TextView
    private lateinit var tvDeadzoneValue: TextView
    private lateinit var tvGamepadSpeedValue: TextView
    private lateinit var tvStyleValue: TextView
    private lateinit var diagram: GamepadDiagramView

    private val cursorStyles = listOf(
        "orange" to "橙",
        "white" to "白",
        "red" to "红",
        "green" to "绿",
        "blue" to "蓝",
        "black" to "黑",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccess = findViewById(R.id.tvAccessStatus)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        tvAccelValue = findViewById(R.id.tvAccelValue)
        tvOpacityValue = findViewById(R.id.tvOpacityValue)
        tvDeadzoneValue = findViewById(R.id.tvDeadzoneValue)
        tvGamepadSpeedValue = findViewById(R.id.tvGamepadSpeedValue)
        tvStyleValue = findViewById(R.id.tvStyleValue)
        diagram = findViewById(R.id.diagram)

        // 手柄图例：点按键 → 选择动作
        diagram.onKeyTap = { key -> showMappingPickerFor(key) }

        // 底部导航切换分区
        val pageVirtual = findViewById<View>(R.id.pageVirtual)
        val pageMapping = findViewById<ScrollView>(R.id.pageMapping)
        val pageGame = findViewById<ScrollView>(R.id.pageGame)
        val pageSettings = findViewById<ScrollView>(R.id.pageSettings)
        findViewById<BottomNavigationView>(R.id.bottomNav).setOnNavigationItemSelectedListener { item ->
            pageVirtual.visibility = if (item.itemId == R.id.nav_virtual) View.VISIBLE else View.GONE
            pageMapping.visibility = if (item.itemId == R.id.nav_mapping) View.VISIBLE else View.GONE
            pageGame.visibility = if (item.itemId == R.id.nav_game) View.VISIBLE else View.GONE
            pageSettings.visibility = if (item.itemId == R.id.nav_settings) View.VISIBLE else View.GONE
            true
        }

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<Button>(R.id.btnTogglePanel).setOnClickListener { togglePanel() }
        findViewById<Button>(R.id.btnEditLayout).setOnClickListener { toggleEdit() }
        findViewById<Button>(R.id.btnStyle).setOnClickListener {
            val config = ConfigStore.load(this)
            val idx = cursorStyles.indexOfFirst { it.first == config.cursorStyle }
            val next = cursorStyles[(idx + 1) % cursorStyles.size]
            config.cursorStyle = next.first
            ConfigStore.save(this, config)
            tvStyleValue.text = next.second
            OverlayController.instance?.onCursorStyleChanged()
        }

        val config = ConfigStore.load(this)

        val sbSpeed = findViewById<SeekBar>(R.id.sbSpeed)
        sbSpeed.max = 20
        sbSpeed.progress = config.cursorSpeed.toInt().coerceIn(1, 20)
        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                config.cursorSpeed = p.coerceAtLeast(1).toFloat()
                tvSpeedValue.text = "×$p"
                ConfigStore.save(this@MainActivity, config)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        tvSpeedValue.text = "×${config.cursorSpeed.toInt()}"

        // 摇杆加速时间（0..800ms）
        val sbAccel = findViewById<SeekBar>(R.id.sbAccel)
        sbAccel.max = 800
        sbAccel.progress = config.accelTime.coerceIn(0, 800)
        sbAccel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                config.accelTime = p
                tvAccelValue.text = if (p == 0) "0ms" else "${p}ms"
                ConfigStore.save(this@MainActivity, config)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        tvAccelValue.text = if (config.accelTime == 0) "0ms" else "${config.accelTime}ms"

        val sbOpacity = findViewById<SeekBar>(R.id.sbOpacity)
        sbOpacity.max = 90
        sbOpacity.progress = ((config.panelOpacity - 0.1f) * 100).toInt().coerceIn(0, 90)
        sbOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = 0.1f + p / 100f
                config.panelOpacity = v
                tvOpacityValue.text = "${(v * 100).toInt()}%"
                ConfigStore.save(this@MainActivity, config)
                OverlayController.instance?.applyPanelOpacity()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        tvOpacityValue.text = "${(config.panelOpacity * 100).toInt()}%"

        val sbDeadzone = findViewById<SeekBar>(R.id.sbDeadzone)
        sbDeadzone.max = 50
        sbDeadzone.progress = config.deadzone.coerceIn(0, 50)
        sbDeadzone.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                config.deadzone = p
                tvDeadzoneValue.text = "$p%"
                ConfigStore.save(this@MainActivity, config)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        tvDeadzoneValue.text = "${config.deadzone}%"

        // 手柄摇杆速度（1..100）
        val sbGamepadSpeed = findViewById<SeekBar>(R.id.sbGamepadSpeed)
        sbGamepadSpeed.max = 100
        sbGamepadSpeed.progress = config.mouseSpeed.coerceIn(1, 100)
        sbGamepadSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                config.mouseSpeed = p.coerceAtLeast(1)
                tvGamepadSpeedValue.text = "${config.mouseSpeed}"
                ConfigStore.save(this@MainActivity, config)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        tvGamepadSpeedValue.text = "${config.mouseSpeed}"
        tvStyleValue.text = cursorStyles.firstOrNull { it.first == config.cursorStyle }?.second ?: "橙"

        val swAutoHide = findViewById<SwitchCompat>(R.id.swCursorAutoHide)
        swAutoHide.isChecked = config.cursorAutoHide
        swAutoHide.setOnCheckedChangeListener { _, checked ->
            config.cursorAutoHide = checked
            ConfigStore.save(this, config)
            OverlayController.instance?.onCursorSettingsChanged()
        }

        val swFocusIdle = findViewById<SwitchCompat>(R.id.swFocusIdle)
        swFocusIdle.isChecked = config.focusIdleRelease
        swFocusIdle.setOnCheckedChangeListener { _, checked ->
            config.focusIdleRelease = checked
            ConfigStore.save(this, config)
        }

        val swSwapAB = findViewById<SwitchCompat>(R.id.swSwapAB)
        swSwapAB.isChecked = config.swapAB
        swSwapAB.setOnCheckedChangeListener { _, checked ->
            config.swapAB = checked
            ConfigStore.save(this, config)
        }

        buildGamepadMapUi()
        buildGameUi()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        diagram.refresh()
        buildGameUi()
    }

    private fun refreshStatus() {
        val accessOn = isAccessibilityEnabled()
        tvAccess.text = if (accessOn) "● 已开启" else "○ 未开启"
        tvAccess.setTextColor(
            if (accessOn) getColor(R.color.status_on) else getColor(R.color.status_off)
        )
        val batteryOn = batteryExempt()
        val tvBattery = findViewById<TextView>(R.id.tvBatteryStatus)
        tvBattery.text = if (batteryOn) "● 已在白名单" else "○ 未加入白名单"
        tvBattery.setTextColor(
            if (batteryOn) getColor(R.color.status_on) else getColor(R.color.status_off)
        )
        findViewById<Button>(R.id.btnTogglePanel).text =
            if (OverlayController.instance?.panelVisible() == true) "隐藏悬浮控制台" else "显示悬浮控制台"
    }

    private fun batteryExempt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    /** 手柄图例点按键 → 选择动作 */
    private fun showMappingPickerFor(key: String) {
        val cfg = ConfigStore.load(this)
        val keyLabel = keyLabels[key] ?: key
        val actions = Action.entries.filter {
            it != Action.TOGGLE_PANEL && it != Action.MEDIA_FORWARD && it != Action.MEDIA_REWIND
        }
        val labels = actions.map { it.label }.toTypedArray()
        val idx = actions.indexOfFirst { it.id == cfg.gamepadMap[key] }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("$keyLabel 的动作")
            .setSingleChoiceItems(labels, idx) { d, which ->
                cfg.gamepadMap[key] = actions[which].id
                ConfigStore.save(this, cfg)
                diagram.refresh()
                buildGamepadMapUi()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun togglePanel() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }
        var c = OverlayController.instance
        val svc = GestureAccessibilityService.instance
        if (c == null && svc != null) {
            c = OverlayController(svc).also { it.show() }
        }
        if (c == null) {
            Toast.makeText(this, "服务正在启动，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        c.togglePanel()
        refreshStatus()
    }

    private fun toggleEdit() {
        val c = OverlayController.instance
        if (c == null) {
            Toast.makeText(this, "请先显示悬浮控制台（需要无障碍服务已开启）", Toast.LENGTH_SHORT).show()
            return
        }
        c.setEditing(!c.editMode)
        refreshStatus()
    }

    private val keyLabels = mapOf(
        "a" to "A 键", "b" to "B 键", "x" to "X 键", "y" to "Y 键",
        "lb" to "L1 肩键", "rb" to "R1 肩键", "lt" to "L2 扳机", "rt" to "R2 扳机",
        "up" to "十字键 ↑", "down" to "十字键 ↓", "left" to "十字键 ←", "right" to "十字键 →",
        "start" to "Start 键", "select" to "Select 键", "mode" to "Logo 键", "center" to "十字键确认",
        "l3" to "左摇杆按下 L3", "r3" to "右摇杆按下 R3",
    )

    /** 手柄按键映射列表：点行 → 选择动作 */
    private fun buildGamepadMapUi() {
        val container = findViewById<LinearLayout>(R.id.llGamepadMap)
        container.removeAllViews()
        val cfg = ConfigStore.load(this)

        fun addRow(title: String, value: String, onPick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { onPick() }
            }
            row.addView(
                TextView(this).apply {
                    text = title
                    textSize = 14f
                    setTextColor(0xFF333333.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            row.addView(
                TextView(this).apply {
                    text = value
                    textSize = 14f
                    setTextColor(getColor(R.color.primary))
                }
            )
            container.addView(row)
        }

        // 唤出键（特殊）
        val toggleKeyNames = listOf("l3", "r3", "a", "b", "x", "y", "start", "select", "mode", "center")
        addRow("唤出/关闭鼠标", keyLabels[cfg.toggleKey] ?: cfg.toggleKey) {
            val names = toggleKeyNames.map { keyLabels[it] ?: it }.toTypedArray()
            val idx = toggleKeyNames.indexOf(cfg.toggleKey).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("唤出/关闭鼠标的键")
                .setSingleChoiceItems(names, idx) { d, which ->
                    cfg.toggleKey = toggleKeyNames[which]
                    ConfigStore.save(this, cfg)
                    buildGamepadMapUi()
                    diagram.refresh()
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 普通按键映射
        listOf("a", "b", "x", "y", "lb", "rb", "lt", "rt", "up", "down", "left", "right", "start", "select", "mode", "center")
            .forEach { key ->
                addRow(keyLabels[key] ?: key, Action.fromId(cfg.gamepadMap[key]).label) {
                    showMappingPickerFor(key)
                }
            }
    }

    // ================= 游戏模式（不使用焦点窗）页面 =================

    private val gameKeyOrder = listOf(
        "a", "b", "x", "y", "lb", "rb", "lt", "rt",
        "up", "down", "left", "right", "start", "select", "mode", "center", "l3", "r3"
    )

    private fun buildGameUi() {
        val container = findViewById<LinearLayout>(R.id.llGameContent)
        container.removeAllViews()
        val cfg = ConfigStore.load(this)

        fun text(s: String, size: Float = 13f, color: Int = 0xFF555555.toInt(), bold: Boolean = false) =
            TextView(this).apply {
                text = s
                textSize = size
                setTextColor(color)
                if (bold) paint.isFakeBoldText = true
                setPadding(0, dp(4), 0, dp(4))
            }

        fun row(right: View, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                setTextColor(0xFF333333.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(right)
        }

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { onClick() }
        }

        // 开关：游戏模式
        val sw = SwitchCompat(this)
        sw.isChecked = cfg.gameMode
        sw.setOnCheckedChangeListener { _, on ->
            cfg.gameMode = on
            ConfigStore.save(this, cfg)
            OverlayController.instance?.setGameMode(on)
            buildGameUi()
        }
        container.addView(row(sw, "游戏模式（不使用焦点窗）"))
        container.addView(text("开启后游戏全程保持窗口焦点：物理摇杆不可用；手柄按键直接点击/滑动屏幕点位。", 12f))

        // 滑动距离
        container.addView(text("滑动距离（上/下/左/右滑）", 13f, 0xFF333333.toInt(), true))
        val sbDist = SeekBar(this)
        sbDist.max = 320 // 80..400 dp
        sbDist.progress = cfg.gameSwipeDistance - 80
        val tvDist = TextView(this).apply { text = "${cfg.gameSwipeDistance}dp"; textSize = 13f }
        val distRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        sbDist.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        distRow.addView(sbDist)
        distRow.addView(tvDist)
        sbDist.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                cfg.gameSwipeDistance = p + 80
                tvDist.text = "${cfg.gameSwipeDistance}dp"
                ConfigStore.save(this@MainActivity, cfg)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        container.addView(distRow)

        // 点位管理
        container.addView(text("屏幕点位（按键点击/滑动的目标位置）", 13f, 0xFF333333.toInt(), true))
        container.addView(row(
            btn(if (OverlayController.instance?.gamePointEditing() == true) "完成点位编辑" else "编辑点位位置") {
                val c = OverlayController.instance
                if (c != null) {
                    val on = !c.gamePointEditing()
                    c.setGamePointEditing(on)
                    if (on) {
                        Toast.makeText(this, "拖动屏幕上的橙色标记到游戏按键位置", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "点位已保存", Toast.LENGTH_SHORT).show()
                    }
                    buildGameUi()
                } else {
                    Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                }
            },
            "把标记拖到游戏按键上"
        ))
        container.addView(row(
            btn("添加点位") {
                val c = OverlayController.instance
                if (c != null) {
                    val p = c.addGamePoint()
                    if (p != null) {
                        // 添加后立即进入点位编辑：标记马上出现在屏幕上，拖到游戏按键处
                        c.setGamePointEditing(true)
                        Toast.makeText(
                            this,
                            "已添加 ${p.label}：把屏幕上的橙色标记拖到游戏按键上，完成后回本页点[完成点位编辑]",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    buildGameUi()
                } else {
                    Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                }
            },
            "最多 10 个"
        ))
        cfg.gamePoints.forEach { p ->
            container.addView(row(
                btn("删除") {
                    OverlayController.instance?.deleteGamePoint(p.id)
                    buildGameUi()
                },
                "${p.label}  (${(p.x * 100).toInt()}%, ${(p.y * 100).toInt()}%)"
            ))
        }
        if (cfg.gamePoints.isEmpty()) {
            container.addView(text("还没有点位：先点[添加点位]，再点[编辑点位位置]，把屏幕上的标记拖到游戏按键上。", 12f))
        }

        // 按键绑定
        container.addView(text("手柄按键绑定", 13f, 0xFF333333.toInt(), true))
        gameKeyOrder.forEach { key ->
            val label = gameBindingLabel(cfg, cfg.gameKeyMap[key])
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(0, dp(8), 0, dp(8))
                addView(TextView(this@MainActivity).apply {
                    text = keyLabels[key] ?: key
                    textSize = 14f
                    setTextColor(0xFF333333.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 13f
                    setTextColor(getColor(R.color.primary))
                })
                setOnClickListener { showGameBindingPicker(cfg, key) }
            }
            container.addView(rowView)
        }
    }

    /** 游戏模式绑定串 → 显示文字 */
    private fun gameBindingLabel(cfg: com.joymouse.app.config.AppConfig, binding: String?): String {
        if (binding.isNullOrBlank() || binding == "none") return "无动作"
        val prefix = binding.substringBefore(':')
        val actionName = when (prefix) {
            "tap" -> "点击"
            "longpress" -> "长按"
            "swipe_up" -> "上滑"
            "swipe_down" -> "下滑"
            "swipe_left" -> "左滑"
            "swipe_right" -> "右滑"
            else -> null
        }
        if (actionName != null && binding.contains(':')) {
            val p = cfg.gamePoints.firstOrNull { it.id == binding.substringAfter(':').toLongOrNull() }
            return "$actionName ${p?.label ?: "?"}"
        }
        return when (binding) {
            "home" -> "主页"
            "back" -> "返回"
            "recents" -> "最近任务"
            "notifications" -> "通知栏"
            "quick_settings" -> "快捷设置"
            "screenshot" -> "截屏"
            "vol_up" -> "音量+"
            "vol_down" -> "音量-"
            "mute" -> "静音"
            "media_play_pause" -> "播放/暂停"
            "toggle_panel" -> "显示控制台"
            else -> binding
        }
    }

    /** 游戏模式按键绑定选择弹窗 */
    private fun showGameBindingPicker(cfg: com.joymouse.app.config.AppConfig, key: String) {
        val options = mutableListOf<Pair<String, String>>()
        options.add("none" to "无动作")
        cfg.gamePoints.forEach { p ->
            options.add("tap:${p.id}" to "点击 ${p.label}")
            options.add("longpress:${p.id}" to "长按 ${p.label}")
            options.add("swipe_up:${p.id}" to "上滑 ${p.label}")
            options.add("swipe_down:${p.id}" to "下滑 ${p.label}")
            options.add("swipe_left:${p.id}" to "左滑 ${p.label}")
            options.add("swipe_right:${p.id}" to "右滑 ${p.label}")
        }
        options.add("home" to "主页")
        options.add("back" to "返回")
        options.add("recents" to "最近任务")
        options.add("notifications" to "通知栏")
        options.add("quick_settings" to "快捷设置")
        options.add("screenshot" to "截屏")
        options.add("vol_up" to "音量+")
        options.add("vol_down" to "音量-")
        options.add("mute" to "静音")
        options.add("media_play_pause" to "播放/暂停")
        options.add("toggle_panel" to "显示控制台")

        val labels = options.map { it.second }.toTypedArray()
        val idx = options.indexOfFirst { it.first == cfg.gameKeyMap[key] }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("${keyLabels[key] ?: key} 的游戏模式动作")
            .setSingleChoiceItems(labels, idx) { d, which ->
                cfg.gameKeyMap[key] = options[which].first
                ConfigStore.save(this, cfg)
                d.dismiss()
                buildGameUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 供状态栏通知等外部入口复用 */
    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
