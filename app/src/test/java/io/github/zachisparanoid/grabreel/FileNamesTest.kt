package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {
    @Test fun sanitizeStripsIllegalChars() {
        assertEquals("a_b_c", FileNames.sanitize("a/b:c"))
    }

    @Test fun sanitizeBlankBecomesVideo() {
        assertEquals("video", FileNames.sanitize("   "))
    }

    @Test fun mimeKnownExtensions() {
        assertEquals("video/mp4", FileNames.mimeForExtension("mp4"))
        assertEquals("video/webm", FileNames.mimeForExtension(".webm"))
        assertEquals("audio/mpeg", FileNames.mimeForExtension("MP3"))
    }

    @Test fun mimeUnknownDefaultsMp4() {
        assertEquals("video/mp4", FileNames.mimeForExtension("xyz"))
    }

    @Test fun uniqueAddsSuffixOnCollision() {
        assertEquals("v (1).mp4", FileNames.unique("v.mp4", setOf("v.mp4")))
        assertEquals("v (2).mp4", FileNames.unique("v.mp4", setOf("v.mp4", "v (1).mp4")))
    }

    @Test fun uniqueUnchangedWhenFree() {
        assertEquals("v.mp4", FileNames.unique("v.mp4", emptySet()))
    }
}
