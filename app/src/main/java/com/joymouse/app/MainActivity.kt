package com.joymouse.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
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
    private lateinit var tvOverlay: TextView
    private lateinit var tvIme: TextView
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvOpacityValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccess = findViewById(R.id.tvAccessStatus)
        tvOverlay = findViewById(R.id.tvOverlayStatus)
        tvIme = findViewById(R.id.tvImeStatus)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        tvOpacityValue = findViewById(R.id.tvOpacityValue)

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<Button>(R.id.btnTogglePanel).setOnClickListener { togglePanel() }
        findViewById<Button>(R.id.btnEditLayout).setOnClickListener { toggleEdit() }
        findViewById<Button>(R.id.btnIme).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
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
        val overlayOn = Settings.canDrawOverlays(this)
        tvOverlay.text = if (overlayOn) "● 已授权" else "○ 未授权"
        tvOverlay.setTextColor(
            if (overlayOn) getColor(R.color.status_on) else getColor(R.color.status_off)
        )
        findViewById<Button>(R.id.btnTogglePanel).text =
            if (OverlayController.instance?.panelVisible() == true) "隐藏悬浮控制台" else "显示悬浮控制台"

        // 手柄输入法状态
        val imeEnabled = imeEnabled()
        val imeActive = imeActive()
        tvIme.text = when {
            imeActive -> getString(R.string.gamepad_status_active)
            imeEnabled -> getString(R.string.gamepad_status_on)
            else -> getString(R.string.gamepad_status_off)
        }
        tvIme.setTextColor(
            when {
                imeActive -> getColor(R.color.status_on)
                imeEnabled -> getColor(R.color.accent)
                else -> getColor(R.color.status_off)
            }
        )
    }

    private fun imeEnabled(): Boolean {
        val im = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return im.enabledInputMethodList.any { it.component?.packageName == packageName }
    }

    private fun imeActive(): Boolean {
        val cur = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?: return false
        return cur.startsWith(packageName)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun togglePanel() {
        if (!isAccessibilityEnabled() || !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启无障碍服务与悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        var c = OverlayController.instance
        val svc = GestureAccessibilityService.instance
        if (c == null && svc != null) {
            // 服务已运行但控制台尚未拉起（例如授权晚于服务开启）
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
