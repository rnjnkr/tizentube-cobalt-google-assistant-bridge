package io.github.rnjnkr.tizentubebridge

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling event log in the app's private storage. The TV's logcat `main` buffer is flooded by
 * the display driver (PqLink) and only holds ~15 minutes, so service events are also kept here.
 * Read it with:
 *   adb shell run-as io.github.rnjnkr.tizentubebridge cat files/events.log
 *
 * Each line starts with the epoch-millis time it was written. On every write, lines older than
 * [MAX_AGE_MS] are dropped and at most [MAX_LINES] are kept, so a burst of events (or a clock
 * jump that makes old lines look new) can't grow the file without bound.
 */
object EventLog {
    private const val TAG = "YTInterceptor"
    private const val FILE_NAME = "events.log"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private const val MAX_LINES = 1000

    /** Messages are clipped to this length; START lines copied from logcat can be long. */
    private const val MAX_MESSAGE_CHARS = 500

    @Volatile private var file: File? = null
    private val lock = Any()

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        append("I", msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        append("W", msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        append("E", if (t == null) msg else "$msg: $t")
    }

    private fun append(level: String, msg: String) {
        val f = file ?: return
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))
        val text = msg.take(MAX_MESSAGE_CHARS).replace('\n', ' ')
        val line = "$now\t$stamp\t$level\t$text"
        synchronized(lock) {
            try {
                val kept = if (f.exists()) f.readLines().filter { isFresh(it, now) } else emptyList()
                val lines = (kept + line).takeLast(MAX_LINES)
                // Write to a temp file and rename, so a crash mid-write can't truncate the log.
                val tmp = File(f.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(lines.joinToString("\n", postfix = "\n"))
                tmp.renameTo(f)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write $FILE_NAME", e)
            }
        }
    }

    private fun isFresh(line: String, now: Long): Boolean {
        val ts = line.substringBefore('\t').toLongOrNull() ?: return false
        return now - ts <= MAX_AGE_MS
    }
}
