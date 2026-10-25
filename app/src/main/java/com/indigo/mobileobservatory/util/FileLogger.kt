package com.indigo.mobileobservatory.util

import android.content.Context
import android.app.ActivityManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

object FileLogger {
    private var logFile: File? = null
    private var enabled = false
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var crashDiagnosticsInstalled = false

    fun init(context: Context) {
        try {
            val logDir = File(context.getExternalFilesDir(null), "logs")
            if (!logDir.exists()) logDir.mkdirs()
            logFile = File(logDir, "app.log")
            enabled = true
            installCrashDiagnostics(context)
            i("FileLogger", "FileLogger initialized: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e("FileLogger", "Failed to init FileLogger", e)
        }
    }

    @Synchronized private fun installCrashDiagnostics(context: Context) {
        if (crashDiagnosticsInstalled) return
        crashDiagnosticsInstalled = true
        Thread.getDefaultUncaughtExceptionHandler()?.let { previous ->
            Thread.setDefaultUncaughtExceptionHandler(CrashLoggingHandler(previous) { e("Crash", it) })
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.getHistoricalProcessExitReasons(context.packageName, 0, 3)?.forEach { exit ->
                i("ProcessExit", "timestamp=${exit.timestamp} reason=${exit.reason} status=${exit.status} " +
                    "description=${exit.description?.take(1024)}")
            }
        }
    }

    fun getLogFile(): File? = logFile

    fun clearLog() {
        logFile?.let {
            if (it.exists()) it.delete()
        }
    }

    @Synchronized private fun writeLog(level: String, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logMessage = "$timestamp $level/$tag: $message"
        
        if (!enabled || logFile == null) return
        
        try {
            PrintWriter(FileWriter(logFile, true)).use { writer ->
                writer.println(logMessage)
            }
        } catch (e: Exception) {
            Log.e("FileLogger", "Failed to write log", e)
        }
    }

    fun v(tag: String, message: String) {
        Log.v(tag, message)
        writeLog("V", tag, message)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeLog("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeLog("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        writeLog("W", tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
        writeLog("E", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
        writeLog("E", tag, message)
        throwable?.let {
            writeLog("E", tag, "  Exception: ${it.javaClass.name}: ${it.message}")
            it.stackTrace.take(5).forEach { frame ->
                writeLog("E", tag, "    at $frame")
            }
        }
    }
}
