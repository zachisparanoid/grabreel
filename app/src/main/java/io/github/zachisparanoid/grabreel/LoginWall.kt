package io.github.zachisparanoid.grabreel

/** Phrases in a yt-dlp failure that mean Instagram wants a logged-in session. */
private val LOGIN_WALL_MARKERS = listOf(
    "login required",
    "empty media response",
    "requested content is not available",
    "checkpoint",
    "challenge_required",
    "login page",
)

/** True when an Instagram download failure looks like Instagram's auth wall. */
fun looksLikeInstagramLoginWall(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val m = message.lowercase()
    return LOGIN_WALL_MARKERS.any { it in m }
}
