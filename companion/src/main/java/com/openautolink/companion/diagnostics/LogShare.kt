package com.openautolink.companion.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.RandomAccessFile

/**
 * Makes the companion's file logs readable off-device without ADB or root.
 *
 * [CompanionFileLogger] writes to `<external files>/openautolink/logs/`, which
 * on Android 11+ the Files app and third-party file managers are BLOCKED from
 * browsing (the platform walls off `Android/data/`). So the logs existed but
 * were unreachable from the phone itself — the exact wall hit while debugging
 * the car-side reconnect issues.
 *
 * This shares the TAIL of the most recent logs as PLAIN TEXT through the system
 * share sheet, so it can be mailed to yourself, dropped into Keep/Drive, or
 * pasted into a bug report and actually read.
 *
 * Plain text (rather than a file or zip) is deliberate: it needs no FileProvider
 * and lands somewhere immediately readable. The tail is capped well below the
 * ~1 MB binder transaction ceiling so the share intent can never blow up on a
 * large log.
 */
object LogShare {

    private const val DIR_NAME = "openautolink/logs"

    /** Tail of our own CompanionLog output — the primary diagnostic. */
    private const val MAX_APP_TAIL_BYTES = 96L * 1024

    /**
     * Tail of the captured logcat. Smaller, but valuable for connection bugs:
     * [CompanionFileLogger] filters in WifiStateMachine / ConnectivityService /
     * NetworkAgent, so a WiFi drop behind a reconnect failure shows up here.
     */
    private const val MAX_LOGCAT_TAIL_BYTES = 32L * 1024

    /** Same resolution order as CompanionFileLogger.resolveLogDir(). */
    private fun logDir(context: Context): File? {
        context.getExternalFilesDir(null)?.let {
            val d = File(it, DIR_NAME)
            if (d.isDirectory) return d
        }
        val internal = File(context.filesDir, DIR_NAME)
        return if (internal.isDirectory) internal else null
    }

    private fun latest(context: Context, prefix: String): File? =
        logDir(context)?.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?.maxByOrNull { it.lastModified() }

    /**
     * Read the last [maxBytes] of [file], decoded as UTF-8. When the file is
     * larger than the cap the first (possibly partial) line is dropped so the
     * result always starts on a clean line boundary.
     */
    private fun tail(file: File, maxBytes: Long): String {
        val len = file.length()
        if (len == 0L) return ""
        val from = (len - maxBytes).coerceAtLeast(0L)
        val buf = ByteArray((len - from).toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            raf.readFully(buf)
        }
        val text = String(buf, Charsets.UTF_8)
        if (from == 0L) return text
        val nl = text.indexOf('\n')
        return if (nl >= 0) text.substring(nl + 1) else text
    }

    /**
     * Build the shareable log text, or null when there is nothing to share
     * (file logging has never been turned on, so no files exist yet).
     */
    fun buildLogText(context: Context, versionName: String): String? {
        val appLog = latest(context, "oal_companion_")
        val logcatLog = latest(context, "logcat_companion_")
        if (appLog == null && logcatLog == null) return null

        return buildString {
            append("=== OpenAutoLink companion log ===\n")
            append("App: $versionName\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n\n")

            appLog?.let { f ->
                val body = runCatching { tail(f, MAX_APP_TAIL_BYTES) }
                    .getOrElse { "<failed to read: ${it.message}>" }
                append("--- ${f.name} (${f.length() / 1024} KB")
                if (f.length() > MAX_APP_TAIL_BYTES) {
                    append(", showing last ${MAX_APP_TAIL_BYTES / 1024} KB")
                }
                append(") ---\n")
                append(body)
                append("\n")
            }

            logcatLog?.let { f ->
                val body = runCatching { tail(f, MAX_LOGCAT_TAIL_BYTES) }
                    .getOrElse { "<failed to read: ${it.message}>" }
                append("\n--- ${f.name} (${f.length() / 1024} KB")
                if (f.length() > MAX_LOGCAT_TAIL_BYTES) {
                    append(", showing last ${MAX_LOGCAT_TAIL_BYTES / 1024} KB")
                }
                append(") ---\n")
                append(body)
            }
        }
    }

    /**
     * Chooser intent that shares the log text. Returns null when no log exists
     * yet — the caller should prompt the user to enable File Logging first.
     */
    fun shareIntent(context: Context, versionName: String): Intent? {
        val text = buildLogText(context, versionName)
        if (text.isNullOrBlank()) return null
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OpenAutoLink companion log ($versionName)")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return Intent.createChooser(send, "Share companion log")
    }
}
