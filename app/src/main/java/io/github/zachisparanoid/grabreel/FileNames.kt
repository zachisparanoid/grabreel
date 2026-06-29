package io.github.zachisparanoid.grabreel

/** Pure helpers for turning yt-dlp output names into safe public Downloads names. */
object FileNames {
    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")

    fun sanitize(name: String): String =
        ILLEGAL.replace(name, "_").trim().trim('.').ifBlank { "video" }

    fun mimeForExtension(ext: String): String = when (ext.lowercase().removePrefix(".")) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus" -> "audio/opus"
        else -> "video/mp4"
    }

    fun unique(name: String, existing: Set<String>): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while ("$base ($i)$ext" in existing) i++
        return "$base ($i)$ext"
    }
}
