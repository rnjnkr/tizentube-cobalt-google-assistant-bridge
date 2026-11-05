package com.example.youtubetvinterceptor

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Locale

/**
 * Voice phrases pinned to fixed URLs, read from `pins.txt` in the app's external files dir
 * (/sdcard/Android/data/<pkg>/files/pins.txt, writable with `adb push`). Pinned because the
 * Assistant's pick for a phrase can change from day to day.
 *
 * Format: one `phrase: url` per line, split at the first colon; blank lines and `#` comments
 * are ignored. Phrases match the URL's `vq` parameter (what the Assistant heard) after
 * [normalize], so a leading "play" and trailing "on YouTube" don't matter.
 *
 * The file is re-read only when its timestamp or size changes. If it is missing it is
 * recreated from [DEFAULTS]; if it can't be read, [DEFAULTS] are used.
 */
class PinStore(private val context: Context) {

    companion object {
        const val FILE_NAME = "pins.txt"

        private val DEFAULTS = """
            |# Voice phrase -> URL that TizenTube should open instead of the Assistant's pick.
            |# One "phrase: url" per line, split at the first colon. Case-insensitive; a leading
            |# "play" and trailing "on YouTube" are ignored. Blank lines and # comments are skipped.
            |#
            |# Examples (remove the leading "# " to use them):
            |# "Hey Google, play lofi hip hop on YouTube" -> Lofi Girl's live stream.
            |# lofi hip hop: https://www.youtube.com/watch?v=jfKfPfyJRdk
            |# A video with its auto-generated mix (radio) queued after it.
            |# synthwave radio: https://www.youtube.com/watch?v=4xDzrJKXOOY&list=RD4xDzrJKXOOY&start_radio=1
            |""".trimMargin()

        /** Lowercase, collapse whitespace, drop a leading "play" and a trailing "on youtube". */
        fun normalize(phrase: String): String =
            phrase.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
                .removePrefix("play ").removeSuffix(" on youtube").trim()

        private fun isYouTubeUrl(url: String): Boolean {
            val uri = try {
                Uri.parse(url)
            } catch (e: Exception) {
                return false
            }
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            return (uri.scheme == "https" || uri.scheme == "http") &&
                (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be")
        }
    }

    private var pins: Map<String, String> = parse(DEFAULTS, logIssues = false)
    private var loadedStamp: Pair<Long, Long>? = null

    /** The pinned URL for a heard query, or null. Called from lookup threads. */
    @Synchronized
    fun lookup(heard: String): String? {
        reloadIfChanged()
        return pins[normalize(heard)]
    }

    private fun reloadIfChanged() {
        val file = try {
            context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
        } catch (e: Exception) {
            null
        } ?: return
        try {
            if (!file.exists()) {
                file.writeText(DEFAULTS)
                EventLog.i("Created ${file.path} with the default pins")
            }
            val stamp = file.lastModified() to file.length()
            if (stamp == loadedStamp) return
            pins = parse(file.readText(), logIssues = true)
            loadedStamp = stamp
            EventLog.i("Loaded ${pins.size} pin(s) from $FILE_NAME")
        } catch (e: Exception) {
            EventLog.e("Could not read $FILE_NAME; keeping ${pins.size} current pin(s)", e)
        }
    }

    private fun parse(text: String, logIssues: Boolean): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val colon = line.indexOf(':')
            val phrase = if (colon > 0) normalize(line.substring(0, colon)) else ""
            val url = if (colon > 0) line.substring(colon + 1).trim() else ""
            when {
                phrase.isEmpty() || url.isEmpty() ->
                    if (logIssues) EventLog.w("$FILE_NAME line ${index + 1} ignored: expected \"phrase: url\"")
                !isYouTubeUrl(url) ->
                    if (logIssues) EventLog.w("$FILE_NAME line ${index + 1} ignored: not a YouTube URL: $url")
                else -> {
                    if (logIssues && phrase in result) {
                        EventLog.w("$FILE_NAME line ${index + 1}: \"$phrase\" listed again; using this one")
                    }
                    result[phrase] = url
                }
            }
        }
        return result
    }
}
