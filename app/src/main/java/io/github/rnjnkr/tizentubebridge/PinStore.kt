package io.github.rnjnkr.tizentubebridge

import android.content.Context
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

        internal val DEFAULTS = """
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

        /** Scheme and host of an http(s) URL (skipping any user info and port). */
        private val HTTP_HOST = Regex("^(https?)://(?:[^/?#@]*@)?([^/?#:]+)", RegexOption.IGNORE_CASE)

        fun isYouTubeUrl(url: String): Boolean {
            val host = HTTP_HOST.find(url)?.groupValues?.get(2)?.lowercase(Locale.ROOT) ?: return false
            return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
        }

        /** Pins in file order (a phrase listed twice keeps the later URL), plus one message per problem line. */
        class Parsed(val pins: Map<String, String>, val issues: List<String>)

        fun parse(text: String): Parsed {
            val result = LinkedHashMap<String, String>()
            val issues = mutableListOf<String>()
            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val colon = line.indexOf(':')
                val phrase = if (colon > 0) normalize(line.substring(0, colon)) else ""
                val url = if (colon > 0) line.substring(colon + 1).trim() else ""
                when {
                    phrase.isEmpty() || url.isEmpty() ->
                        issues += "$FILE_NAME line ${index + 1} ignored: expected \"phrase: url\""
                    !isYouTubeUrl(url) ->
                        issues += "$FILE_NAME line ${index + 1} ignored: not a YouTube URL: $url"
                    else -> {
                        if (phrase in result) {
                            issues += "$FILE_NAME line ${index + 1}: \"$phrase\" listed again; using this one"
                        }
                        result[phrase] = url
                    }
                }
            }
            return Parsed(result, issues)
        }
    }

    private var pins: Map<String, String> = parse(DEFAULTS).pins
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
            val parsed = parse(file.readText())
            parsed.issues.forEach { EventLog.w(it) }
            pins = parsed.pins
            loadedStamp = stamp
            EventLog.i("Loaded ${pins.size} pin(s) from $FILE_NAME")
        } catch (e: Exception) {
            EventLog.e("Could not read $FILE_NAME; keeping ${pins.size} current pin(s)", e)
        }
    }
}
