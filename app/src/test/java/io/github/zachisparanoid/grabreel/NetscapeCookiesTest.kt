package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetscapeCookiesTest {
    @Test fun startsWithNetscapeHeader() {
        val out = NetscapeCookies.serialize("sessionid=abc", ".instagram.com")
        assertTrue(out.startsWith("# Netscape HTTP Cookie File\n"))
    }

    @Test fun singleCookieHasSevenTabColumns() {
        val out = NetscapeCookies.serialize("sessionid=abc123", ".instagram.com")
        val line = out.lines().first { it.startsWith(".instagram.com") }
        assertEquals(".instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tabc123", line)
    }

    @Test fun multipleCookiesKeepOrder() {
        val out = NetscapeCookies.serialize("sessionid=a; csrftoken=b", ".instagram.com")
        val lines = out.lines().filter { it.startsWith(".instagram.com") }
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("\tsessionid\ta"))
        assertTrue(lines[1].endsWith("\tcsrftoken\tb"))
    }

    @Test fun emptyInputIsHeaderOnly() {
        assertEquals("# Netscape HTTP Cookie File\n", NetscapeCookies.serialize("", ".instagram.com"))
    }

    @Test fun valueContainingEqualsIsPreserved() {
        val out = NetscapeCookies.serialize("k=a=b=c", ".instagram.com")
        val line = out.lines().first { it.startsWith(".instagram.com") }
        assertTrue(line.endsWith("\tk\ta=b=c"))
    }
}
