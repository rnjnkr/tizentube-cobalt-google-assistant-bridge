package io.github.rnjnkr.tizentubebridge

import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * Text parsing for [YouTubeInterceptorService]: ActivityTaskManager START lines, `dumpsys
 * activity` output and YouTube URLs. Kept free of Android classes so plain JVM unit tests
 * (`./gradlew test`) can cover it.
 */
object LaunchParsing {
    const val OFFICIAL_YT = "com.google.android.youtube.tv"

    /** A START line in ActivityTaskManager that references the official YouTube component. */
    private val START_LINE: Pattern = Pattern.compile(
        "START u0 .*(?:pkg|cmp)=" + Pattern.quote(OFFICIAL_YT)
    )

    /** A one-line dumpsys Intent that targets official YouTube. */
    private val YT_TARGET: Pattern = Pattern.compile("\\b(?:pkg|cmp)=" + Pattern.quote(OFFICIAL_YT))

    /**
     * The data URL of a one-line dumpsys Intent (group 1). It runs up to the next Intent
     * field, because the Assistant's search URLs carry raw JSON, braces and spaces included:
     * `https://www.youtube.com/tv?voice={"youtubeAssistantRequest":{"query":"search for …"}}&…`
     */
    private val DAT_URL: Pattern = Pattern.compile(
        "\\bdat=(https?://.+?)(?: (?:typ|flg|pkg|cmp)=| \\(has | \\}\\s*$)"
    )

    /** Flags, component and caller uid of a START line, used to pick its intent out of dumpsys. */
    private val START_FLG: Pattern = Pattern.compile("\\bflg=(0x[0-9a-fA-F]+)")
    private val START_CMP: Pattern = Pattern.compile("\\bcmp=([^\\s}]+)")
    private val START_UID: Pattern = Pattern.compile("\\bfrom uid (\\d+)")

    fun isYouTubeStart(line: String): Boolean = START_LINE.matcher(line).find()

    fun startFlags(line: String): String? = group1(START_FLG, line)

    fun startComponent(line: String): String? = group1(START_CMP, line)

    fun startUid(line: String): Int? = group1(START_UID, line)?.toIntOrNull()

    /** The data URL of a one-line dumpsys Intent that targets official YouTube, or null. */
    fun youTubeIntentUrl(line: String): String? {
        if (!YT_TARGET.matcher(line).find()) return null
        return group1(DAT_URL, line)
    }

    /**
     * The URL of the activity starter's most recent start (`dumpsys activity starter`), if that
     * start targeted official YouTube and its URL isn't redacted.
     */
    fun starterUrl(starterDump: String): String? {
        val intentLine = starterDump.substringAfter("mStartActivity:", "")
            .lineSequence().firstOrNull { it.trimStart().startsWith("Intent {") } ?: return null
        return youTubeIntentUrl(intentLine)?.takeIf { !it.endsWith("/...") }
    }

    /**
     * The URL of an activity record in `dumpsys activity activities` whose Intent has exactly
     * these flags and component, or null.
     */
    fun activityRecordUrl(activitiesDump: String, flags: String, component: String): String? {
        for (line in activitiesDump.lineSequence()) {
            if (!line.trimStart().startsWith("Intent {")) continue
            if (!line.contains("cmp=$component") || !line.contains("flg=$flags ")) continue
            return youTubeIntentUrl(line) ?: continue
        }
        return null
    }

    /**
     * A decoded query parameter, or null if absent. Lenient like Android's Uri: the Assistant's
     * search URLs contain unencoded JSON, which java.net.URI rejects.
     */
    fun queryParameter(url: String, name: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            if (decode(pair.substringBefore('=')) == name) return decode(pair.substringAfter('=', ""))
        }
        return null
    }

    /**
     * What the user asked for, for the overlay: "play lofi hip hop on YouTube" ->
     * "lofi hip hop", "search for Minecraft videos" -> "Minecraft videos".
     */
    fun spokenTitle(url: String): String? {
        val vq = queryParameter(url, "vq") ?: return null
        return vq.trim().replace(Regex("\\s+"), " ")
            .replace(Regex("^(?:play|search(?:\\s+for)?)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+on youtube$", RegexOption.IGNORE_CASE), "")
            .takeIf { it.isNotBlank() }
    }

    private fun group1(pattern: Pattern, text: String): String? =
        pattern.matcher(text).let { if (it.find()) it.group(1) else null }

    /** Percent-decodes ("+" is a space); a malformed escape leaves the text as it was. */
    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }
}
