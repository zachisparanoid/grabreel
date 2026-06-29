package io.github.zachisparanoid.grabreel

import android.content.Context
import java.io.File

/**
 * Per-platform Netscape cookies.txt + captured User-Agent, stored privately.
 * Constructed with a plain [dir] so the file logic is unit-testable; use
 * [forContext] in app code to anchor it under noBackupFilesDir (keeps the
 * session out of cloud/adb backups even though allowBackup is true).
 */
class CookieStore(private val dir: File) {

    data class Auth(val cookiesFile: File, val userAgent: String?)

    fun save(platform: Platform, rawCookieHeader: String, userAgent: String?) {
        dir.mkdirs()
        cookiesFile(platform).writeText(NetscapeCookies.serialize(rawCookieHeader, domainFor(platform)))
        val ua = uaFile(platform)
        if (userAgent.isNullOrBlank()) ua.delete() else ua.writeText(userAgent)
    }

    fun authFor(platform: Platform): Auth? {
        val cookies = cookiesFile(platform)
        if (!cookies.exists() || cookies.length() == 0L) return null
        val ua = uaFile(platform).let { if (it.exists()) it.readText().ifBlank { null } else null }
        return Auth(cookies, ua)
    }

    fun isLoggedIn(platform: Platform): Boolean {
        val c = cookiesFile(platform)
        return c.exists() && c.length() > 0L
    }

    fun clear(platform: Platform) {
        cookiesFile(platform).delete()
        uaFile(platform).delete()
    }

    private fun cookiesFile(platform: Platform) = File(dir, "${platform.name.lowercase()}.txt")
    private fun uaFile(platform: Platform) = File(dir, "${platform.name.lowercase()}.ua")

    private fun domainFor(platform: Platform): String = when (platform) {
        Platform.INSTAGRAM -> ".instagram.com"
        Platform.TIKTOK -> ".tiktok.com"
        Platform.X -> ".x.com"
        Platform.YOUTUBE -> ".youtube.com"
        Platform.FACEBOOK -> ".facebook.com"
        Platform.OTHER -> ""
    }

    companion object {
        fun forContext(context: Context): CookieStore =
            CookieStore(File(context.noBackupFilesDir, "cookies"))
    }
}
