package com.joymouse.app.service

import android.service.notification.NotificationListenerService

/**
 * 通知使用权服务：仅用于解锁 MediaSessionManager.getActiveSessions 权限。
 * 授权后播放/暂停可直接经媒体会话 transportControls 控制——
 * 完全不触碰蓝牙/AVRCP 通道，避免"重启后第一次按 Y 导致手柄断联"的竞态。
 * 未授权时播放/暂停回退为媒体键派发（有上述副作用）。
 */
class MediaNotificationListener : NotificationListenerService()
