package com.lb.asupplayer

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler private constructor(
    private val context: Context,
    private val previousHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val report = buildString {
                appendLine("=== JAVA CRASH ===")
                appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                try {
                    val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
                    appendLine("App: ${pkg.versionName} (${pkg.longVersionCode})")
                } catch (_: Exception) {}
                appendLine("Thread: ${thread.name}")
                appendLine()
                append(sw)
            }
            File(context.filesDir, CRASH_FILE).writeText(report)
        } catch (_: Exception) {}
        previousHandler?.uncaughtException(thread, throwable)
    }

    companion object {
        const val CRASH_FILE = "crash_log.txt"
        const val BREADCRUMB_FILE = "breadcrumb.txt"
        private const val PENDING_REPORT_FILE = "pending_crash_report.txt"

        fun install(context: Context) {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            if (prev is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(context.applicationContext, prev)
            )
        }

        fun writeBreadcrumb(context: Context, step: String) {
            try {
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                File(context.filesDir, BREADCRUMB_FILE).appendText("[$ts] $step\n")
            } catch (_: Exception) {}
        }

        fun clearBreadcrumbs(context: Context) {
            File(context.filesDir, BREADCRUMB_FILE).delete()
        }

        /**
         * Returns a pending combined report if anything was recorded.
         *
         * Raw crash data is moved into a pending report so it survives activity recreation
         * until the user explicitly sends or dismisses it.
         */
        fun collectPendingReport(context: Context): String? {
            val pendingFile = File(context.filesDir, PENDING_REPORT_FILE)
            val crashFile = File(context.filesDir, CRASH_FILE)
            val bcFile = File(context.filesDir, BREADCRUMB_FILE)

            val crash = if (crashFile.exists()) {
                try { crashFile.readText() } catch (_: Exception) { null }
            } else null

            val bc = if (bcFile.exists()) {
                try { bcFile.readText() } catch (_: Exception) { null }
            } else null

            if (crash == null && bc == null) {
                return if (pendingFile.exists()) {
                    try { pendingFile.readText() } catch (_: Exception) { null }
                } else null
            }

            val report = buildString {
                if (crash != null) append(crash)
                if (bc != null) {
                    if (isNotEmpty()) appendLine()
                    appendLine("=== INIT BREADCRUMBS (last run) ===")
                    append(bc)
                }
            }
            try {
                pendingFile.writeText(report)
                crashFile.delete()
                bcFile.delete()
            } catch (_: Exception) {}
            return report
        }

        fun clearPendingReport(context: Context) {
            File(context.filesDir, PENDING_REPORT_FILE).delete()
            File(context.filesDir, CRASH_FILE).delete()
        }
    }
}
