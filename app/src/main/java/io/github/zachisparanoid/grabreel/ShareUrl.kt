package io.github.zachisparanoid.grabreel

/** First http/https URL embedded anywhere in a string. */
private val URL_REGEX = Regex("""https?://\S+""")

/** Trailing chars that are almost always punctuation around a URL, not part of it. */
private const val TRAILING_JUNK = ".,;:!?\"')]}"

/**
 * Pulls the first http(s) URL out of arbitrary shared text. Apps commonly share
 * "caption text… https://link" rather than a bare URL, so we find the URL inside
 * the string and strip trailing punctuation. Returns null when none is found.
 */
fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val raw = URL_REGEX.find(text)?.value ?: return null
    return raw.trimEnd { it in TRAILING_JUNK }.ifBlank { null }
}

/** Social platform a URL belongs to, used to pick the right login cookies. */
enum class Platform { INSTAGRAM, TIKTOK, X, YOUTUBE, FACEBOOK, OTHER }

/**
 * Classifies a URL by host. Uses java.net.URI (not android.net.Uri) so it stays
 * unit-testable on the JVM. Unknown or unparseable URLs are [Platform.OTHER].
 */
fun platformFor(url: String): Platform {
    val host = try {
        java.net.URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    } ?: return Platform.OTHER

    fun matches(vararg domains: String) =
        domains.any { host == it || host.endsWith(".$it") }

    return when {
        matches("instagram.com") -> Platform.INSTAGRAM
        matches("tiktok.com") -> Platform.TIKTOK
        matches("x.com", "twitter.com") -> Platform.X
        host == "youtu.be" || matches("youtube.com") -> Platform.YOUTUBE
        host == "fb.watch" || matches("facebook.com") -> Platform.FACEBOOK
        else -> Platform.OTHER
    }
}
