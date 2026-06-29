package io.github.zachisparanoid.grabreel

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Initializes the on-device yt-dlp/FFmpeg engine once and keeps yt-dlp current. */
class GrabReelApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var engineReady = false
        private set

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            try {
                YoutubeDL.getInstance().init(this@GrabReelApp)
                FFmpeg.getInstance().init(this@GrabReelApp)
                engineReady = true
                try {
                    YoutubeDL.getInstance()
                        .updateYoutubeDL(this@GrabReelApp, YoutubeDL.UpdateChannel.STABLE)
                } catch (e: Exception) {
                    Log.w(TAG, "yt-dlp update skipped: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "engine init failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "GrabReelApp"
    }
}
