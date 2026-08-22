package com.joymouse.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/**
 * 统一日志落盘。
 *
 * 为什么改：旧实现每个按键/滚动/触摸事件都在主线程直接 open/write/close 一个文件，
 * 高频操作下主线程被文件 I/O 拖慢（卡顿帧），并且事件量大时文件无限增长。
 *
 * 本实现：
 * - 专用单线程后台队列串行写入，主线程零 I/O（崩溃处理器除外，它必须同步写）；
 * - 单文件上限 2MB，超限时保留尾部 512KB 后继续追加（滚动式日志）；
 * - 所有诊断日志（events/keys/gestures/scroll）统一走这里。
 */
object AppLog {

    private const val MAX_FILE_SIZE = 2L * 1024 * 1024
    private const val KEEP_TAIL = 512L * 1024

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "joymouse-log").apply { isDaemon = true }
    }

    /** 异步追加一行（自动补换行）。调用线程不阻塞。 */
    fun write(context: Context, name: String, line: String) {
        try {
            val text = line + "\n"
            executor.execute {
                try {
                    append(context.filesDir, name, text)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** 同步写入（进程即将死亡的关键日志，如服务销毁/崩溃栈）。 */
    fun writeSync(context: Context, name: String, text: String) {
        try {
            append(context.filesDir, name, text)
        } catch (_: Throwable) {
        }
    }

    private fun append(dir: File, name: String, text: String) {
        val f = File(dir, name)
        if (f.length() > MAX_FILE_SIZE) {
            try {
                val tail = if (f.length() > KEEP_TAIL) {
                    RandomAccessFile(f, "r").use { raf ->
                        raf.seek(f.length() - KEEP_TAIL)
                        val buf = ByteArray(KEEP_TAIL.toInt())
                        val n = raf.read(buf)
                        String(buf, 0, n.coerceAtLeast(0), Charsets.UTF_8)
                    }
                } else ""
                FileOutputStream(f, false).use {
                    it.write(("$tail\n--- truncated, keeping tail ---\n").toByteArray())
                }
            } catch (_: Throwable) {
                f.delete()
            }
        }
        FileOutputStream(f, true).use { it.write(text.toByteArray()) }
    }
}
