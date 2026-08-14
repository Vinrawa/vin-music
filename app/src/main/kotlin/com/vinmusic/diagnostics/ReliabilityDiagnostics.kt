package com.vinmusic.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Small, persistent, privacy-safe reliability log.  It deliberately records
 * URL hosts/statuses rather than complete YouTube URLs (which contain signed
 * tokens), and keeps only a bounded file so diagnostics cannot grow forever.
 */
object ReliabilityDiagnostics {
    private const val TAG = "VIN_RELIABILITY"
    private const val MAX_BYTES = 1_500_000L
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vin-reliability-log").apply { isDaemon = true }
    }
    private val sessionId = UUID.randomUUID().toString().take(8)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        if (logFile == null) {
            synchronized(this) {
                if (logFile == null) {
                    val dir = File(context.applicationContext.filesDir, "reliability")
                    if (!dir.exists()) dir.mkdirs()
                    logFile = File(dir, "reliability.log")
                }
            }
        }
        record("app", "session_start", status = "ok")
    }

    fun record(
        component: String,
        phase: String,
        videoId: String? = null,
        attempt: Int? = null,
        status: String? = null,
        httpCode: Int? = null,
        error: String? = null,
        details: String? = null
    ) {
        val safe = listOf(
            synchronized(timeFormat) { timeFormat.format(Date()) },
            sessionId,
            component,
            phase,
            videoId.orEmpty(),
            attempt?.toString().orEmpty(),
            status.orEmpty(),
            httpCode?.toString().orEmpty(),
            sanitize(error),
            sanitize(details)
        ).joinToString("\t")
        Log.i(TAG, safe)
        writer.execute {
            try {
                val file = logFile ?: return@execute
                file.parentFile?.mkdirs()
                if (file.length() > MAX_BYTES) {
                    val rotated = File(file.parentFile, "reliability.previous.log")
                    if (rotated.exists()) rotated.delete()
                    file.renameTo(rotated)
                }
                file.appendText(safe + "\n")
            } catch (t: Throwable) {
                Log.w(TAG, "Could not persist reliability event: ${t.message}")
            }
        }
    }

    private fun sanitize(value: String?): String = value.orEmpty()
        .replace('\t', ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(300)
}
