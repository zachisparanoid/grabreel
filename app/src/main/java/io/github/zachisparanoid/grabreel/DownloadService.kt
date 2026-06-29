package io.github.zachisparanoid.grabreel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Foreground service: runs each shared/pasted URL through yt-dlp and publishes the result. */
class DownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val active = AtomicInteger(0)
    private val lastInstagramAt = AtomicLong(0)
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        val id = UUID.randomUUID().toString()
        DownloadStore.add(Download(id = id, url = url, status = DownloadStatus.QUEUED))
        active.incrementAndGet()
        startForegroundCompat(ongoing("Starting download…", 0, true))
        executor.execute { runJob(id, url) }
        return START_NOT_STICKY
    }

    private fun runJob(id: String, url: String) {
        waitForEngine()
        val platform = platformFor(url)
        throttleInstagram(platform)
        DownloadStore.update(id) { it.copy(status = DownloadStatus.RUNNING) }
        val parent = File(cacheDir, "downloads")
        val auth = CookieStore.forContext(this).authFor(platform)
        val result = Downloader.download(url, parent, id, auth?.cookiesFile, auth?.userAgent) { pct, eta ->
            DownloadStore.update(id) { it.copy(progress = pct, etaSeconds = eta) }
            notifications.notify(ONGOING_ID, ongoing("Downloading… $pct%", pct, false))
        }
        when (result) {
            is Downloader.Result.Success -> {
                try {
                    val pub = MediaStorePublisher.publish(this, result.file)
                    DownloadStore.update(id) {
                        it.copy(
                            status = DownloadStatus.COMPLETED, progress = 100,
                            title = pub.displayName, filePath = pub.uri?.toString(),
                        )
                    }
                    notifyDone(pub.displayName)
                } catch (e: Exception) {
                    DownloadStore.update(id) {
                        it.copy(status = DownloadStatus.FAILED, error = "Couldn't save to Downloads.")
                    }
                }
            }
            is Downloader.Result.Failure -> {
                // looksLikeInstagramLoginWall inspects Downloader.cleanMessage's output, which keeps only
                // the last non-blank line of yt-dlp's error. If yt-dlp moves the marker phrase off that
                // final line, the friendly hint would be missed (the on-device test is the real guard).
                val msg = if (platform == Platform.INSTAGRAM && looksLikeInstagramLoginWall(result.message)) {
                    getString(R.string.ig_login_required)
                } else {
                    result.message
                }
                DownloadStore.update(id) { it.copy(status = DownloadStatus.FAILED, error = msg) }
            }
        }
        File(parent, id).deleteRecursively()
        active.decrementAndGet()
        stopIfIdle()
    }

    /** Space out back-to-back Instagram downloads so the account isn't flagged for bursts. */
    private fun throttleInstagram(platform: Platform) {
        if (platform != Platform.INSTAGRAM) return
        val gap = Random.nextLong(3000L, 6000L)
        val since = System.currentTimeMillis() - lastInstagramAt.get()
        if (since in 0L until gap) Thread.sleep(gap - since)
        lastInstagramAt.set(System.currentTimeMillis())
    }

    private fun waitForEngine() {
        val app = application as? GrabReelApp ?: return
        var waited = 0
        while (!app.engineReady && waited < 120_000) {
            Thread.sleep(250)
            waited += 250
        }
    }

    private fun stopIfIdle() {
        if (active.get() <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_ID, n)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Downloads", NotificationManager.IMPORTANCE_LOW),
            )
            notifications.createNotificationChannel(
                NotificationChannel(CH_DONE, "Completed", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ongoing(text: String, pct: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("GrabReel")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(openAppIntent())
            .build()

    private fun notifyDone(name: String) {
        notifications.notify(
            name.hashCode(),
            NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Saved to Downloads")
                .setContentText(name)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build(),
        )
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val CH_PROGRESS = "downloads"
        private const val CH_DONE = "downloads_done"
        private const val ONGOING_ID = 1

        fun start(context: Context, url: String) {
            val i = Intent(context, DownloadService::class.java).putExtra(EXTRA_URL, url)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
