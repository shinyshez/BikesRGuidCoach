package com.mtbanalyzer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.BufferedReader
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
 *
 * Everything is bounded. The clipboard and share intents go through Binder, which refuses
 * a transaction over about 1 MB, and an unbounded report (a deep stack overflow, a phone
 * whose logcat ignores -t) once crashed the Copy button and lost the report it was copying.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"
    private const val TRACE_LINES = 120
    private const val APP_LOG_LINES = 80
    private const val TAIL_LOG_LINES = 200
    private const val MAX_LINE_CHARS = 240
    internal const val MAX_REPORT_CHARS = 60_000

    /** Tags whose lines are worth seeing even when the camera has flooded the log. */
    private val APP_TAGS = listOf("DopplerGate", "AndroidRuntime", "MTBAnalyzer")

    fun install(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(context, thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Shows the saved crash, if there is one. It is forgotten only once copied, shared or dismissed. */
    fun offerLastCrash(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return
        val text = runCatching { file.readText().take(MAX_REPORT_CHARS) }.getOrNull()
        if (text.isNullOrBlank()) { file.delete(); return }

        AlertDialog.Builder(context)
            .setTitle("The app crashed last time")
            .setMessage(text.substringBefore("\n--- ").take(1500))
            .setPositiveButton("Share") { _, _ ->
                attempt(context, "share", file) {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "MTB Analyzer crash")
                        .putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Share crash report"))
                }
            }
            .setNeutralButton("Copy") { _, _ ->
                attempt(context, "copy", file) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("MTB Analyzer crash", text))
                    Toast.makeText(context, "Crash report copied", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Dismiss") { _, _ -> file.delete() }
            .show()
    }

    /** Runs [action]; the report survives a failure so it can be tried again next launch. */
    private fun attempt(context: Context, what: String, file: File, action: () -> Unit) {
        try {
            action()
            file.delete()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't $what the report: $e", Toast.LENGTH_LONG).show()
        }
    }

    private fun report(context: Context, thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        // Only this process: the one that is dying. An app may read its own lines unprivileged.
        val pid = Process.myPid().toString()
        val appLog = logcat(APP_LOG_LINES, "--pid=$pid", "-s", *APP_TAGS.map { "$it:V" }.toTypedArray())
        val tailLog = logcat(TAIL_LOG_LINES, "--pid=$pid")
        return buildString {
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("App: $version")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine(capLines(trace, TRACE_LINES))
            appendLine("--- app log (${APP_TAGS.joinToString()}) ---")
            appendLine(appLog)
            appendLine("--- logcat (last $TAIL_LOG_LINES lines of this process) ---")
            append(tailLog)
        }.take(MAX_REPORT_CHARS)
    }

    private fun logcat(lines: Int, vararg filter: String): String = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", *filter))
        proc.inputStream.bufferedReader().use { tailLines(it, lines, MAX_LINE_CHARS) }
    }.getOrElse { "(logcat unavailable: $it)" }

    /** The last [count] lines of [reader], each cut to [maxChars], without holding the rest. */
    internal fun tailLines(reader: BufferedReader, count: Int, maxChars: Int): String {
        val kept = ArrayDeque<String>(count)
        while (true) {
            val line = reader.readLine() ?: break
            if (kept.size == count) kept.removeFirst()
            kept.addLast(if (line.length > maxChars) line.take(maxChars) + "…" else line)
        }
        return kept.joinToString("\n")
    }

    /** The first [max] lines of [text], noting how many were dropped. */
    internal fun capLines(text: String, max: Int): String {
        val lines = text.trimEnd().lines()
        if (lines.size <= max) return lines.joinToString("\n")
        return (lines.take(max) + "… (${lines.size - max} more lines)").joinToString("\n")
    }
}
