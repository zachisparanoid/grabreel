package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CookieStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = CookieStore(File(tmp.root, "cookies"))

    @Test fun notLoggedInInitially() {
        assertFalse(store().isLoggedIn(Platform.INSTAGRAM))
        assertNull(store().authFor(Platform.INSTAGRAM))
    }

    @Test fun saveThenAuthReturnsCookiesAndUa() {
        val s = store()
        s.save(Platform.INSTAGRAM, "sessionid=abc; csrftoken=xyz", "TestUA/1.0")
        assertTrue(s.isLoggedIn(Platform.INSTAGRAM))
        val auth = s.authFor(Platform.INSTAGRAM)!!
        assertEquals("TestUA/1.0", auth.userAgent)
        val text = auth.cookiesFile.readText()
        assertTrue(text.startsWith("# Netscape HTTP Cookie File"))
        assertTrue(text.contains("\tsessionid\tabc"))
    }

    @Test fun saveWithNullUaHasNoUa() {
        val s = store()
        s.save(Platform.INSTAGRAM, "sessionid=abc", null)
        assertNull(s.authFor(Platform.INSTAGRAM)!!.userAgent)
    }

    @Test fun blankUaTreatedAsNoUa() {
        val s = store()
        s.save(Platform.INSTAGRAM, "sessionid=abc", "")
        assertNull(s.authFor(Platform.INSTAGRAM)!!.userAgent)
    }

    @Test fun resaveWithNullUaRemovesStaleUa() {
        val s = store()
        s.save(Platform.INSTAGRAM, "sessionid=abc", "UA/1.0")
        s.save(Platform.INSTAGRAM, "sessionid=abc", null)
        assertNull(s.authFor(Platform.INSTAGRAM)!!.userAgent)
    }

    @Test fun clearRemovesSession() {
        val s = store()
        s.save(Platform.INSTAGRAM, "sessionid=abc", "UA")
        s.clear(Platform.INSTAGRAM)
        assertFalse(s.isLoggedIn(Platform.INSTAGRAM))
        assertNull(s.authFor(Platform.INSTAGRAM))
    }
}
