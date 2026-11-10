package io.github.rnjnkr.tizentubebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses lines captured from a real TV (Sony BRAVIA, Android 12) where possible. */
class LaunchParsingTest {

    private val yt = "com.google.android.youtube.tv"
    private val shell = "$yt/com.google.android.apps.youtube.tv.activity.ShellActivity"
    private val main = "$yt/com.google.android.apps.youtube.tv.activity.MainActivity"

    // ActivityTaskManager START lines (URL redacted by the system).
    private val assistantStart = "I/ActivityTaskManager(  778): START u0 {act=android.intent.action.VIEW " +
        "dat=http://www.youtube.com/... flg=0x14000000 pkg=$yt cmp=$shell (has extras)} from uid 10059 from pid 8956"
    private val hopStart = "I/ActivityTaskManager(  778): START u0 {act=android.intent.action.VIEW " +
        "dat=http://www.youtube.com/... flg=0x5000000 pkg=$yt cmp=$main (has extras)} from uid 10067 from pid 7788"
    private val launcherStart = "I/ActivityTaskManager(  778): START u0 {act=android.intent.action.MAIN " +
        "cat=[android.intent.category.LAUNCHER] flg=0x10000000 pkg=$yt cmp=$shell (has extras)} from uid 10054 from pid 2085"
    private val tizenStart = "I/ActivityTaskManager(  778): START u0 {act=android.intent.action.VIEW " +
        "dat=https://www.youtube.com/... flg=0x14000000 cmp=io.gh.reisxd.tizentube.cobalt/dev.cobalt.app.MainActivity} " +
        "from uid 10161 from pid 27432"

    // Unredacted dumpsys Intent lines.
    private val playUrl = "http://www.youtube.com/watch?v=jfKfPfyJRdk&launch=voice&vs=1&vq=play%20lofi%20hip%20hop"
    private val playIntent = "      Intent { act=android.intent.action.VIEW dat=$playUrl flg=0x15000000 " +
        "pkg=$yt cmp=$main (has extras) }"

    private val searchUrl = """https://www.youtube.com/tv?voice={"youtubeAssistantRequest":{"query":"search for Minecraft videos","queryIntent":"CgxTZWFyY2hJbnRlbnQSHQoFcXVlcnkSFBoSChBNaW5lY3JhZnQgdmlkZW9zEiYKCGRvY190eXBlEhoaGAoWWU9VVFVCRV9ET0NfVFlQRV9WSURFTw==","isPrefetchQuery":false,"youtubeAssistantParams":{"personalDataParams":{"showPersonalData":false}}},"updateYoutubeSettings":{"enableSafetyMode":false,"enablePersonalResults":false,"enableAutoplay":true},"hasEntityBar":false}&command_id=3J3sapeUEI2eseMPhb7piQQ&launch=voice&vs=1&vq=search%20for%20Minecraft%20videos"""
    private val searchIntent = "      Intent { act=android.intent.action.VIEW dat=$searchUrl flg=0x14800000 " +
        "pkg=$yt cmp=$shell (has extras) }"

    private val tizenIntent = "      Intent { act=android.intent.action.VIEW " +
        "dat=https://www.youtube.com/watch?v=abc flg=0x14000000 " +
        "cmp=io.gh.reisxd.tizentube.cobalt/dev.cobalt.app.MainActivity }"

    // --- START lines ---

    @Test fun recognisesYouTubeStarts() {
        assertTrue(LaunchParsing.isYouTubeStart(assistantStart))
        assertTrue(LaunchParsing.isYouTubeStart(hopStart))
        assertTrue(LaunchParsing.isYouTubeStart(launcherStart))
        assertFalse(LaunchParsing.isYouTubeStart(tizenStart))
    }

    @Test fun readsFlagsComponentAndCallerUid() {
        assertEquals("0x14000000", LaunchParsing.startFlags(assistantStart))
        assertEquals(shell, LaunchParsing.startComponent(assistantStart))
        assertEquals(10059, LaunchParsing.startUid(assistantStart))
        assertEquals(10054, LaunchParsing.startUid(launcherStart))
        assertNull(LaunchParsing.startUid("START u0 {cmp=$shell}"))
    }

    // --- Intent lines ---

    @Test fun readsPlayUrl() {
        assertEquals(playUrl, LaunchParsing.youTubeIntentUrl(playIntent))
    }

    @Test fun readsSearchUrlWithJsonBracesAndSpaces() {
        // The bug that sent searches to official YouTube: the URL used to be cut at the first "}".
        assertEquals(searchUrl, LaunchParsing.youTubeIntentUrl(searchIntent))
    }

    @Test fun urlEndsAtWhicheverIntentFieldFollows() {
        val u = "https://www.youtube.com/watch?v=abc"
        assertEquals(u, LaunchParsing.youTubeIntentUrl("Intent { dat=$u typ=text/plain cmp=$main }"))
        assertEquals(u, LaunchParsing.youTubeIntentUrl("Intent { dat=$u pkg=$yt }"))
        assertEquals(u, LaunchParsing.youTubeIntentUrl("Intent { dat=$u cmp=$main (has extras) }"))
        assertEquals(u, LaunchParsing.youTubeIntentUrl("Intent { cmp=$main dat=$u }"))
    }

    @Test fun ignoresIntentsForOtherAppsOrWithoutUrl() {
        assertNull(LaunchParsing.youTubeIntentUrl(tizenIntent))
        assertNull(LaunchParsing.youTubeIntentUrl("Intent { act=android.intent.action.MAIN flg=0x10000000 cmp=$shell }"))
        assertNull(LaunchParsing.youTubeIntentUrl("Intent { dat=vnd.youtube:abc cmp=$main }"))
    }

    // --- dumpsys activity starter ---

    private fun starterDump(intentLine: String) = """
        ACTIVITY MANAGER STARTER (dumpsys activity starter)
        mLastHomeActivityStartRecord:
          Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] flg=0x10800100 cmp=com.google.android.apps.tv.launcherx/.home.HomeActivity (has extras) }
            mStartActivity:
              packageName=$yt processName=$yt
        $intentLine
              rootOfTask=false task=null
    """.trimIndent()

    @Test fun starterGivesLastStartsUrl() {
        assertEquals(searchUrl, LaunchParsing.starterUrl(starterDump(searchIntent)))
    }

    @Test fun starterIgnoresOtherAppsRedactedUrlsAndMissingRecord() {
        assertNull(LaunchParsing.starterUrl(starterDump(tizenIntent)))
        assertNull(LaunchParsing.starterUrl(starterDump(
            "      Intent { act=android.intent.action.VIEW dat=http://www.youtube.com/... flg=0x14000000 cmp=$shell }"
        )))
        // The home record above mStartActivity must never be used.
        assertNull(LaunchParsing.starterUrl("mLastHomeActivityStartRecord:\n  $playIntent"))
    }

    // --- dumpsys activity activities ---

    @Test fun activityRecordMatchesExactFlagsAndComponent() {
        val stale = "      Intent { act=android.intent.action.VIEW dat=http://www.youtube.com/watch?v=OLD " +
            "flg=0x10000000 pkg=$yt cmp=$main }"
        val dump = "Task #1\n  intent={...}\n$stale\n$playIntent\n"
        assertEquals(playUrl, LaunchParsing.activityRecordUrl(dump, "0x15000000", main))
        assertNull(LaunchParsing.activityRecordUrl(dump, "0x15000000", shell))
        assertNull(LaunchParsing.activityRecordUrl(dump, "0x1", main))
    }

    // --- URL query parameters ---

    @Test fun decodesQueryParameters() {
        assertEquals("play lofi hip hop", LaunchParsing.queryParameter(playUrl, "vq"))
        assertEquals("1", LaunchParsing.queryParameter(playUrl, "vs"))
        assertEquals("a b", LaunchParsing.queryParameter("https://x/?q=a+b", "q"))
        assertEquals("", LaunchParsing.queryParameter("https://x/?flag&q=1", "flag"))
        assertNull(LaunchParsing.queryParameter(playUrl, "missing"))
        assertNull(LaunchParsing.queryParameter("https://www.youtube.com/", "vq"))
        assertNull(LaunchParsing.queryParameter("https://x/?a=1#vq=2", "vq"))
    }

    @Test fun queryParametersSurviveRawJsonAndBadEscapes() {
        assertEquals("search for Minecraft videos", LaunchParsing.queryParameter(searchUrl, "vq"))
        assertTrue(LaunchParsing.queryParameter(searchUrl, "voice")!!.startsWith("{\"youtubeAssistantRequest\""))
        assertEquals("100%", LaunchParsing.queryParameter("https://x/?q=100%", "q"))
    }

    // --- Overlay title ---

    @Test fun spokenTitleDropsCommandWords() {
        assertEquals("lofi hip hop", LaunchParsing.spokenTitle(playUrl))
        assertEquals("Minecraft videos", LaunchParsing.spokenTitle(searchUrl))
        assertEquals("Minecraft", LaunchParsing.spokenTitle("https://x/?vq=Search%20Minecraft"))
        assertEquals("lofi music", LaunchParsing.spokenTitle("https://x/?vq=Play%20%20lofi%20music%20on%20YouTube"))
    }

    @Test fun spokenTitleIsNullWithoutAQuery() {
        assertNull(LaunchParsing.spokenTitle("https://www.youtube.com/?command_id=abc&launch=voice&vs=1"))
        assertNull(LaunchParsing.spokenTitle("https://x/?vq=%20%20"))
    }
}
