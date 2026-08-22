package com.joymouse.app

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 全局崩溃捕获：把未捕获异常写入 files/crash.log，
 * 之后可通过 `adb shell run-as com.joymouse.app cat files/crash.log` 读取定位。
 * 崩溃栈必须同步落盘（进程即将死亡，后台队列来不及刷出）。
 */
class IcePadmouseApp : Application() {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = "${System.currentTimeMillis()} [${thread.name}]\n$sw\n----\n"
                AppLog.writeSync(this, "crash.log", text)
            } catch (_: Throwable) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
