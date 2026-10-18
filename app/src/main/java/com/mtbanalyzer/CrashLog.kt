package com.mtbanalyzer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash so it can be read without a cable: the stack trace and the app's
 * recent logcat go to a file as the process dies, and the next launch offers to copy or
 * share them.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"
    private const val LOGCAT_LINES = 400

    fun install(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(context, thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Shows the saved crash, if there is one, and forgets it once dealt with. */
    fun offerLastCrash(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull()
        if (text.isNullOrBlank()) { file.delete(); return }

        AlertDialog.Builder(context)
            .setTitle("The app crashed last time")
            .setMessage(text.substringBefore("--- logcat").take(1500))
            .setPositiveButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "MTB Analyzer crash")
                    .putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share crash report"))
                file.delete()
            }
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("MTB Analyzer crash", text))
                Toast.makeText(context, "Crash report copied", Toast.LENGTH_SHORT).show()
                file.delete()
            }
            .setNegativeButton("Dismiss") { _, _ -> file.delete() }
            .show()
    }

    private fun report(context: Context, thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val logcat = runCatching {
            // An app may read its own log lines without any permission.
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "$LOGCAT_LINES"))
            proc.inputStream.bufferedReader().readText()
        }.getOrElse { "(logcat unavailable: $it)" }
        return buildString {
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("App: $version")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
            appendLine()
            appendLine("--- logcat (last $LOGCAT_LINES lines) ---")
            append(logcat)
        }
    }
}
