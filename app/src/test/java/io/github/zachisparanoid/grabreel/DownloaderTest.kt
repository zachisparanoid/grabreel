package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloaderTest {
    @Test fun cleanMessageTakesLastErrorLine() {
        val raw = "[debug] blah\nWARNING: x\nERROR: Unsupported URL: foo"
        assertEquals("Unsupported URL: foo", Downloader.cleanMessage(raw))
    }

    @Test fun cleanMessageNullFallback() {
        assertEquals("Download failed.", Downloader.cleanMessage(null))
    }
}
