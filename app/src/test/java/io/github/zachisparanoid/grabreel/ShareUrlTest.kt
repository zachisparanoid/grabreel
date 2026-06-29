package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareUrlTest {
    @Test fun bareUrl() {
        assertEquals(
            "https://www.instagram.com/reel/abc/",
            extractUrl("https://www.instagram.com/reel/abc/"),
        )
    }

    @Test fun urlInCaption() {
        assertEquals(
            "https://vt.tiktok.com/ZSxyz/",
            extractUrl("Check out this one 😍 https://vt.tiktok.com/ZSxyz/"),
        )
    }

    @Test fun stripsTrailingPunctuation() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            extractUrl("watch this: https://youtu.be/dQw4w9WgXcQ."),
        )
    }

    @Test fun firstUrlWins() {
        assertEquals(
            "https://a.com/1",
            extractUrl("https://a.com/1 and https://b.com/2"),
        )
    }

    @Test fun keepsQueryString() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            extractUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
    }

    @Test fun noUrlReturnsNull() {
        assertNull(extractUrl("just some text, no link"))
    }

    @Test fun nullReturnsNull() {
        assertNull(extractUrl(null))
    }

    @Test fun platformInstagram() {
        assertEquals(Platform.INSTAGRAM, platformFor("https://www.instagram.com/reel/abc/"))
    }

    @Test fun platformInstagramApexHost() {
        assertEquals(Platform.INSTAGRAM, platformFor("https://instagram.com/p/abc/"))
    }

    @Test fun platformTiktokSubdomain() {
        assertEquals(Platform.TIKTOK, platformFor("https://vt.tiktok.com/ZSxyz/"))
    }

    @Test fun platformXAndTwitter() {
        assertEquals(Platform.X, platformFor("https://x.com/u/status/1"))
        assertEquals(Platform.X, platformFor("https://twitter.com/u/status/1"))
    }

    @Test fun platformYoutubeShort() {
        assertEquals(Platform.YOUTUBE, platformFor("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test fun platformFacebookWatch() {
        assertEquals(Platform.FACEBOOK, platformFor("https://fb.watch/abc/"))
    }

    @Test fun platformUnknownIsOther() {
        assertEquals(Platform.OTHER, platformFor("https://example.com/x"))
    }

    @Test fun platformGarbageIsOther() {
        assertEquals(Platform.OTHER, platformFor("not a url"))
    }
}
