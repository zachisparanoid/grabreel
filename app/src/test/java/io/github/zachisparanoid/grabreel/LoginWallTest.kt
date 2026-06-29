package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginWallTest {
    @Test fun emptyMediaResponseIsWall() {
        assertTrue(looksLikeInstagramLoginWall(
            "Instagram sent an empty media response. Check if this post is accessible..."))
    }

    @Test fun loginRequiredIsWall() {
        assertTrue(looksLikeInstagramLoginWall(
            "Requested content is not available, rate-limit reached or login required"))
    }

    @Test fun checkpointIsWall() {
        assertTrue(looksLikeInstagramLoginWall("checkpoint_required"))
    }

    @Test fun unrelatedErrorIsNotWall() {
        assertFalse(looksLikeInstagramLoginWall("Unsupported URL: https://example.com"))
    }

    @Test fun nullOrBlankIsNotWall() {
        assertFalse(looksLikeInstagramLoginWall(null))
        assertFalse(looksLikeInstagramLoginWall("   "))
    }
}
