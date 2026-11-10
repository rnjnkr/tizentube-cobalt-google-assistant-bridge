package io.github.rnjnkr.tizentubebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PinStoreTest {

    @Test fun normalizeIgnoresCaseSpacingAndCommandWords() {
        assertEquals("lofi hip hop", PinStore.normalize("lofi hip hop"))
        assertEquals("lofi hip hop", PinStore.normalize("  Play   Lofi Hip  Hop on YouTube "))
        assertEquals("lofi hip hop", PinStore.normalize("PLAY lofi hip hop"))
        // Only a leading "play" goes; one inside the phrase stays.
        assertEquals("role play music", PinStore.normalize("role play music"))
    }

    @Test fun acceptsOnlyYouTubeUrls() {
        assertTrue(PinStore.isYouTubeUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(PinStore.isYouTubeUrl("http://youtube.com/playlist?list=PL1"))
        assertTrue(PinStore.isYouTubeUrl("https://music.youtube.com/watch?v=abc"))
        assertTrue(PinStore.isYouTubeUrl("https://youtu.be/abc"))
        assertTrue(PinStore.isYouTubeUrl("HTTPS://WWW.YOUTUBE.COM:443/watch?v=abc"))

        assertFalse(PinStore.isYouTubeUrl("ftp://www.youtube.com/watch?v=abc"))
        assertFalse(PinStore.isYouTubeUrl("https://evil-youtube.com/watch?v=abc"))
        assertFalse(PinStore.isYouTubeUrl("https://youtube.com.example.org/"))
        assertFalse(PinStore.isYouTubeUrl("https://example.org/?u=https://youtube.com"))
        assertFalse(PinStore.isYouTubeUrl("https://www.youtube.com@example.org/"))
        assertFalse(PinStore.isYouTubeUrl("youtube.com/watch?v=abc"))
    }

    @Test fun parsesPinsSkippingCommentsAndBlankLines() {
        val parsed = PinStore.parse(
            """
            # comment

            Lofi Hip Hop: https://www.youtube.com/watch?v=jfKfPfyJRdk
            play synthwave radio on YouTube:https://youtu.be/4xDzrJKXOOY
            """.trimIndent()
        )
        assertEquals(
            mapOf(
                "lofi hip hop" to "https://www.youtube.com/watch?v=jfKfPfyJRdk",
                "synthwave radio" to "https://youtu.be/4xDzrJKXOOY",
            ),
            parsed.pins,
        )
        assertTrue(parsed.issues.isEmpty())
    }

    @Test fun splitsAtTheFirstColonOnly() {
        val parsed = PinStore.parse("news: https://www.youtube.com/watch?v=abc&t=1:30")
        assertEquals("https://www.youtube.com/watch?v=abc&t=1:30", parsed.pins["news"])
    }

    @Test fun reportsBadLinesWithTheirLineNumbers() {
        val parsed = PinStore.parse(
            """
            no colon here
            : https://www.youtube.com/watch?v=abc
            empty url:
            not youtube: https://example.org/video
            ok: https://www.youtube.com/watch?v=abc
            """.trimIndent()
        )
        assertEquals(mapOf("ok" to "https://www.youtube.com/watch?v=abc"), parsed.pins)
        assertEquals(4, parsed.issues.size)
        assertTrue(parsed.issues[0].startsWith("pins.txt line 1 ignored: expected"))
        assertTrue(parsed.issues[1].startsWith("pins.txt line 2 ignored: expected"))
        assertTrue(parsed.issues[2].startsWith("pins.txt line 3 ignored: expected"))
        assertTrue(parsed.issues[3].startsWith("pins.txt line 4 ignored: not a YouTube URL"))
    }

    @Test fun duplicatePhraseKeepsTheLaterUrl() {
        val parsed = PinStore.parse(
            """
            lofi: https://youtu.be/first
            Play Lofi: https://youtu.be/second
            """.trimIndent()
        )
        assertEquals(mapOf("lofi" to "https://youtu.be/second"), parsed.pins)
        assertEquals(listOf("pins.txt line 2: \"lofi\" listed again; using this one"), parsed.issues)
    }

    @Test fun builtInDefaultsHaveNoActivePins() {
        val parsed = PinStore.parse(PinStore.DEFAULTS)
        assertTrue(parsed.pins.isEmpty())
        assertTrue(parsed.issues.isEmpty())
    }

    @Test fun repoPinsFileMatchesBuiltInDefaults() {
        // The service recreates pins.txt from DEFAULTS, so the published copy should be identical.
        // Unit tests run from the app module folder.
        val repoCopy = File("../pins.txt")
        assertTrue("pins.txt not found from ${File(".").absolutePath}", repoCopy.exists())
        assertEquals(PinStore.DEFAULTS.trimEnd(), repoCopy.readText().trimEnd())
    }

    @Test fun examplesInDefaultsWorkOnceUncommented() {
        val uncommented = PinStore.DEFAULTS.lines()
            .filter { it.startsWith("# ") && it.contains("https://") }
            .joinToString("\n") { it.removePrefix("# ") }
        val parsed = PinStore.parse(uncommented)
        assertEquals(setOf("lofi hip hop", "synthwave radio"), parsed.pins.keys)
        assertTrue(parsed.issues.isEmpty())
    }
}
