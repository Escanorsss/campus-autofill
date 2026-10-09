package com.mike.campusautofill.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.mike.campusautofill.permissions.PermissionHelper
import com.mike.campusautofill.settings.UserSettings
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Metadata only. Never pass node text, credentials, Intent extras or exception messages here. */
object DiagnosticLog {
    private lateinit var app: Context
    private val lock = Any()
    private val dropped = AtomicInteger()
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(256), { r -> Thread(r, "campus-diagnostics") },
        { _, _ -> dropped.incrementAndGet() })
    private val recent = mutableMapOf<String, Long>()
    private const val MAX_BYTES = 512 * 1024L

    fun init(context: Context) {
        app = context.applicationContext
        i("App", "process start pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT} " +
            "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}")
        snapshot("process start")
        if (Build.VERSION.SDK_INT >= 30) {
            writer.execute {
                runCatching {
                    app.getSystemService(ActivityManager::class.java)
                        .getHistoricalProcessExitReasons(app.packageName, 0, 8).forEach {
                            append(line("I", "ExitHistory", "timestamp=${java.time.Instant.ofEpochMilli(it.timestamp)} " +
                                "reason=${it.reason} status=${it.status} importance=${it.importance} rss=${it.rss}"))
                        }
                }.onFailure { e("ExitHistory", "history unavailable", it) }
            }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Direct, synced append: the writer may not get another turn before process death.
            runCatching { append(line("E", "Crash", "thread=${thread.name} ${safeTrace(error)}"), true) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun d(tag: String, message: String) = record("D", tag, message)
    fun i(tag: String, message: String) = record("I", tag, message)
    fun w(tag: String, message: String) = record("W", tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) =
        record("E", tag, message + (error?.let { " ${safeTrace(it)}" } ?: ""))

    fun rate(key: String, message: String, interval: Long = 3000) {
        val now = SystemClock.elapsedRealtime()
        synchronized(recent) {
            if (now - (recent[key] ?: -interval) < interval) return
            if (recent.size > 128) recent.clear()
            recent[key] = now
        }
        i("Event", message)
    }

    private fun record(level: String, tag: String, message: String) {
        if (!::app.isInitialized) return
        val entry = line(level, tag, message)
        android.util.Log.println(when (level) {
            "E" -> android.util.Log.ERROR
            "W" -> android.util.Log.WARN
            "D" -> android.util.Log.DEBUG
            else -> android.util.Log.INFO
        }, "CampusAutofill", "$tag: $message")
        writer.execute { runCatching { append(entry) } }
    }

    private fun line(level: String, tag: String, message: String) =
        "${java.time.Instant.now()} uptime=${SystemClock.elapsedRealtime()} $level/$tag " +
            message.replace('\n', ' ').take(6000) + "\n"

    private fun safeTrace(error: Throwable): String = buildString {
        var current: Throwable? = error
        repeat(4) {
            val cause = current ?: return@repeat
            append(cause.javaClass.name).append(' ')
            cause.stackTrace.take(24).forEach { append(it.toString()).append("; ") }
            current = cause.cause?.takeUnless { it === cause }
        }
    }

    private fun directory() = File(app.filesDir, "diagnostics").apply { mkdirs() }

    private fun append(entry: String, sync: Boolean = false) = synchronized(lock) {
        val dir = directory()
        val current = File(dir, "events-0.log")
        if (current.length() >= MAX_BYTES) {
            File(dir, "events-3.log").delete()
            for (n in 2 downTo 0) File(dir, "events-$n.log").renameTo(File(dir, "events-${n + 1}.log"))
        }
        FileOutputStream(current, true).use {
            val lost = dropped.getAndSet(0)
            if (lost > 0) it.write(line("W", "Logger", "queue dropped=$lost").toByteArray())
            it.write(entry.toByteArray())
            if (sync) it.fd.sync()
        }
    }

    fun snapshot(reason: String) {
        runCatching {
            val p = PermissionHelper.status(app)
            val power = app.getSystemService(PowerManager::class.java)
            i("State", "$reason enabled=${p.accessibilityEnabled} connected=${p.serviceConnected} " +
                "paused=${UserSettings.isPaused} powerSave=${power.isPowerSaveMode} " +
                "interactive=${power.isInteractive} batteryExempt=${p.batteryExempt} overlay=${p.overlayGranted}")
        }.onFailure { e("State", "snapshot failed", it) }
    }

    /** Call from a background thread. The queue barrier preserves all preceding records. */
    fun report(): String {
        val barrier = java.util.concurrent.CountDownLatch(1)
        // Blocking enqueue for export only; ordinary event producers never block on a full queue.
        writer.queue.put(Runnable { barrier.countDown() })
        check(barrier.await(10, TimeUnit.SECONDS)) { "Diagnostic writer timed out" }
        return buildString {
            appendLine("CampusAutofill diagnostics — metadata only; no credentials or screen text")
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            appendLine("version=${info.versionName} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}")
            appendLine("exported=${java.time.Instant.now()} retained=4 x 512 KiB (oldest rotates out)")
            if (Build.VERSION.SDK_INT >= 30) {
                runCatching {
                    app.getSystemService(ActivityManager::class.java)
                        .getHistoricalProcessExitReasons(app.packageName, 0, 16).forEach {
                            appendLine("exit timestamp=${java.time.Instant.ofEpochMilli(it.timestamp)} " +
                                "reason=${it.reason} status=${it.status} importance=${it.importance} pss=${it.pss} rss=${it.rss}")
                        }
                }.onFailure { appendLine("exit history unavailable: ${it.javaClass.simpleName}") }
            }
            synchronized(lock) {
                for (n in 3 downTo 0) {
                    val file = File(directory(), "events-$n.log")
                    if (file.exists()) append(file.readText())
                }
            }
        }
    }
}
