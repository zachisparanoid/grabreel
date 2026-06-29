package io.github.zachisparanoid.grabreel

/**
 * Builds a yt-dlp-compatible Netscape cookies.txt from CookieManager's
 * "name=value; name2=value2" header string. CookieManager returns no cookie
 * attributes, so we synthesize sane ones: every cookie is scoped to [domain]
 * (e.g. ".instagram.com"), include-subdomains TRUE, path "/", secure TRUE,
 * expiry 0 (yt-dlp treats 0 as a kept session cookie).
 */
object NetscapeCookies {
    private const val HEADER = "# Netscape HTTP Cookie File"

    fun serialize(rawCookieHeader: String, domain: String): String {
        val sb = StringBuilder(HEADER).append('\n')
        rawCookieHeader.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { pair ->
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=').trim()
                if (name.isNotEmpty()) {
                    sb.append(domain).append('\t')
                        .append("TRUE").append('\t')
                        .append('/').append('\t')
                        .append("TRUE").append('\t')
                        .append('0').append('\t')
                        .append(name).append('\t')
                        .append(value).append('\n')
                }
            }
        return sb.toString()
    }
}
