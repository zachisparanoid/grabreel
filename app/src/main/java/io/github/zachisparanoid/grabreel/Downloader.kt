package io.github.zachisparanoid.grabreel

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** Runs a single best-quality yt-dlp download into a fresh dir. Blocking; call off-main. */
object Downloader {
    sealed class Result {
        data class Success(val file: File) : Result()
        data class Failure(val message: String) : Result()
    }

    fun download(
        url: String,
        parentDir: File,
        processId: String,
        cookiesFile: File? = null,
        userAgent: String? = null,
        onProgress: (Int, Long) -> Unit,
    ): Result {
        val outDir = File(parentDir, processId).apply { mkdirs() }
        val request = YoutubeDLRequest(url).apply {
            addOption("-f", "bv*+ba/b")
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("-o", File(outDir, "%(title)s.%(ext)s").absolutePath)
            if (cookiesFile != null) addOption("--cookies", cookiesFile.absolutePath)
            if (userAgent != null) addOption("--add-header", "User-Agent:$userAgent")
        }
        return try {
            YoutubeDL.getInstance().execute(request, processId, false) { progress, eta, _ ->
                onProgress(progress.toInt().coerceIn(0, 100), eta)
            }
            val file = outDir.listFiles()?.firstOrNull { it.isFile }
                ?: return Result.Failure("Download finished but produced no file.")
            Result.Success(file)
        } catch (e: YoutubeDLException) {
            Result.Failure(cleanMessage(e.message))
        } catch (e: InterruptedException) {
            Result.Failure("Canceled.")
        } catch (e: Exception) {
            Result.Failure(cleanMessage(e.message))
        }
    }

    /** yt-dlp errors are multi-line and noisy; keep the last meaningful line. */
    fun cleanMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return "Download failed."
        val line = raw.trim().lines().lastOrNull { it.isNotBlank() } ?: return "Download failed."
        return line.removePrefix("ERROR: ").trim().ifBlank { "Download failed." }
    }
}
