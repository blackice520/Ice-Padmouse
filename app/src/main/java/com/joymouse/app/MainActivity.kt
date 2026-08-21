package com.joymouse.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.joymouse.app.config.ConfigStore
import com.joymouse.app.overlay.OverlayController
import com.joymouse.app.service.GestureAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var tvAccess: TextView
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvOpacityValue: TextView
    private lateinit var tvDeadzoneValue: TextView
    private lateinit var tvStyleValue: TextView

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
        tvOpacityValue = findViewById(R.id.tvOpacityValue)
        tvDeadzoneValue = findViewById(R.id.tvDeadzoneValue)
        tvStyleValue = findViewById(R.id.tvStyleValue)

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

        // 手柄摇杆死区
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
        tvStyleValue.text = cursorStyles.firstOrNull { it.first == config.cursorStyle }?.second ?: "橙"

        val swAutoHide = findViewById<SwitchCompat>(R.id.swCursorAutoHide)
        swAutoHide.isChecked = config.cursorAutoHide
        swAutoHide.setOnCheckedChangeListener { _, checked ->
            config.cursorAutoHide = checked
            ConfigStore.save(this, config)
            OverlayController.instance?.onCursorSettingsChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val accessOn = isAccessibilityEnabled()
        tvAccess.text = if (accessOn) "● 已开启" else "○ 未开启"
        tvAccess.setTextColor(
            if (accessOn) getColor(R.color.status_on) else getColor(R.color.status_off)
        )
        findViewById<Button>(R.id.btnTogglePanel).text =
            if (OverlayController.instance?.panelVisible() == true) "隐藏悬浮控制台" else "显示悬浮控制台"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
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

    /** 供状态栏通知等外部入口复用 */
    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
