# Standalone On-Device GrabReel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the WebView/server client with a fully self-contained app that downloads videos on-device via `yt-dlp` + FFmpeg (`youtubedl-android`).

**Architecture:** `Application` initializes the engine + self-updates yt-dlp; a foreground `DownloadService` runs each download on a background thread, reports progress into a `StateFlow` registry, and publishes the finished file to `Downloads/` via MediaStore; a Views-based `MainActivity` (paste field + RecyclerView) and the existing share-target drive it.

**Tech Stack:** Kotlin, `io.github.junkfood02.youtubedl-android:{library,ffmpeg}:0.18.1` (package `com.yausername.youtubedl_android` / `com.yausername.ffmpeg`), kotlinx-coroutines, AndroidX lifecycle + RecyclerView, JUnit 4.

**API notes (verified from source):**
- `YoutubeDL.getInstance().init(ctx)` / `FFmpeg.getInstance().init(ctx)` throw `YoutubeDLException`.
- `execute(request, processId, redirectErrorStream=false, callback)` → `YoutubeDLResponse`; callback is `(Float, Long, String)->Unit`. Use the 4-arg form to dodge overload ambiguity.
- `updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE): UpdateStatus?`.
- `destroyProcessById(id): Boolean`.
- Native libs must be **extracted** (`jniLibs.useLegacyPackaging = true`) or the Python runtime won't load.

---

### Task 1: Dependencies, ABI filter, native-lib packaging

**Files:** Modify `app/build.gradle.kts`

- [ ] **Step 1: Edit `app/build.gradle.kts`**

Set `defaultConfig` to add the ABI filter, add `packaging`, and add dependencies:

```kotlin
    defaultConfig {
        applicationId = "io.github.zachisparanoid.grabreel"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        ndk { abiFilters += "arm64-v8a" }
    }
```

Add after `kotlinOptions { ... }` (inside `android { }`):

```kotlin
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
```

Replace `dependencies { }`:

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: Verify dependencies resolve and compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL (downloads the library on first run). If it fails on `minSdk`, bump to the version the error names and note it.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: add youtubedl-android engine, coroutines, ABI filter"
```

---

### Task 2: `FileNames` pure helpers (TDD)

**Files:** Create `app/src/main/java/io/github/zachisparanoid/grabreel/FileNames.kt`, Test `app/src/test/java/io/github/zachisparanoid/grabreel/FileNamesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {
    @Test fun sanitizeStripsIllegalChars() {
        assertEquals("a_b_c", FileNames.sanitize("a/b:c"))
    }
    @Test fun sanitizeBlankBecomesVideo() {
        assertEquals("video", FileNames.sanitize("   "))
    }
    @Test fun mimeKnownExtensions() {
        assertEquals("video/mp4", FileNames.mimeForExtension("mp4"))
        assertEquals("video/webm", FileNames.mimeForExtension(".webm"))
        assertEquals("audio/mpeg", FileNames.mimeForExtension("MP3"))
    }
    @Test fun mimeUnknownDefaultsMp4() {
        assertEquals("video/mp4", FileNames.mimeForExtension("xyz"))
    }
    @Test fun uniqueAddsSuffixOnCollision() {
        assertEquals("v (1).mp4", FileNames.unique("v.mp4", setOf("v.mp4")))
        assertEquals("v (2).mp4", FileNames.unique("v.mp4", setOf("v.mp4", "v (1).mp4")))
    }
    @Test fun uniqueUnchangedWhenFree() {
        assertEquals("v.mp4", FileNames.unique("v.mp4", emptySet()))
    }
}
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :app:testDebugUnitTest --tests "*FileNamesTest" --console=plain` → unresolved `FileNames`.

- [ ] **Step 3: Implement**

```kotlin
package io.github.zachisparanoid.grabreel

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
```

- [ ] **Step 4: Run, expect PASS.** Then commit:

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/FileNames.kt app/src/test/java/io/github/zachisparanoid/grabreel/FileNamesTest.kt
git commit -m "feat: FileNames pure helpers for safe Downloads filenames"
```

---

### Task 3: `Download` model + `DownloadReducer`/`DownloadStore` (TDD reducer)

**Files:** Create `Download.kt`, `DownloadStore.kt`; Test `DownloadStoreTest.kt` (all in the package dir / test dir)

- [ ] **Step 1: Failing test** (`app/src/test/java/io/github/zachisparanoid/grabreel/DownloadStoreTest.kt`)

```kotlin
package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStoreTest {
    private fun d(id: String, s: DownloadStatus = DownloadStatus.QUEUED) =
        Download(id = id, url = "http://x/$id", status = s)

    @Test fun upsertPrependsNew() {
        val r = DownloadReducer.upsert(listOf(d("a")), d("b"))
        assertEquals(listOf("b", "a"), r.map { it.id })
    }
    @Test fun upsertReplacesInPlace() {
        val r = DownloadReducer.upsert(listOf(d("a"), d("b")), d("a", DownloadStatus.RUNNING))
        assertEquals(DownloadStatus.RUNNING, r.first { it.id == "a" }.status)
        assertEquals(2, r.size)
    }
    @Test fun updateMapsMatchingId() {
        val r = DownloadReducer.update(listOf(d("a")), "a") { it.copy(progress = 50) }
        assertEquals(50, r.first().progress)
    }
    @Test fun removeFilters() {
        assertEquals(listOf("b"), DownloadReducer.remove(listOf(d("a"), d("b")), "a").map { it.id })
    }
    @Test fun clearFinishedKeepsActive() {
        val list = listOf(d("a", DownloadStatus.RUNNING), d("b", DownloadStatus.COMPLETED),
                          d("c", DownloadStatus.QUEUED), d("e", DownloadStatus.FAILED))
        assertEquals(setOf("a", "c"), DownloadReducer.clearFinished(list).map { it.id }.toSet())
    }
}
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement `Download.kt`**

```kotlin
package io.github.zachisparanoid.grabreel

enum class DownloadStatus { QUEUED, RUNNING, COMPLETED, FAILED }

data class Download(
    val id: String,
    val url: String,
    val title: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Int = 0,
    val etaSeconds: Long = -1,
    val filePath: String? = null,
    val error: String? = null,
)
```

- [ ] **Step 4: Implement `DownloadStore.kt`**

```kotlin
package io.github.zachisparanoid.grabreel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadReducer {
    fun upsert(list: List<Download>, d: Download): List<Download> {
        val i = list.indexOfFirst { it.id == d.id }
        return if (i >= 0) list.toMutableList().also { it[i] = d } else listOf(d) + list
    }
    fun update(list: List<Download>, id: String, f: (Download) -> Download): List<Download> =
        list.map { if (it.id == id) f(it) else it }
    fun remove(list: List<Download>, id: String): List<Download> = list.filterNot { it.id == id }
    fun clearFinished(list: List<Download>): List<Download> =
        list.filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
}

object DownloadStore {
    private val _items = MutableStateFlow<List<Download>>(emptyList())
    val items: StateFlow<List<Download>> = _items.asStateFlow()

    @Synchronized fun add(d: Download) { _items.value = DownloadReducer.upsert(_items.value, d) }
    @Synchronized fun update(id: String, f: (Download) -> Download) {
        _items.value = DownloadReducer.update(_items.value, id, f)
    }
    @Synchronized fun remove(id: String) { _items.value = DownloadReducer.remove(_items.value, id) }
    @Synchronized fun clearFinished() { _items.value = DownloadReducer.clearFinished(_items.value) }
}
```

- [ ] **Step 5: Run, expect PASS. Commit.**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/Download.kt app/src/main/java/io/github/zachisparanoid/grabreel/DownloadStore.kt app/src/test/java/io/github/zachisparanoid/grabreel/DownloadStoreTest.kt
git commit -m "feat: Download model + StateFlow registry with tested reducer"
```

---

### Task 4: `Downloader` engine wrapper (+ TDD `cleanMessage`)

**Files:** Create `Downloader.kt`; add to a new `DownloaderTest.kt`

- [ ] **Step 1: Failing test** (`DownloaderTest.kt`) — only the pure helper is unit-tested:

```kotlin
package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloaderTest {
    @Test fun cleanMessageTakesLastErrorLine() {
        val raw = "[debug] blah\nWARNING: x\nERROR: Unsupported URL: foo"
        assertEquals("Unsupported URL: foo", Downloader.cleanMessage(raw))
    }
    @Test fun cleanMessageNullFallback() {
        assertEquals("Download failed.", Downloader.cleanMessage(null))
    }
}
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement `Downloader.kt`**

```kotlin
package io.github.zachisparanoid.grabreel

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

object Downloader {
    sealed class Result {
        data class Success(val file: File) : Result()
        data class Failure(val message: String) : Result()
    }

    fun download(
        url: String,
        parentDir: File,
        processId: String,
        onProgress: (Int, Long) -> Unit,
    ): Result {
        val outDir = File(parentDir, processId).apply { mkdirs() }
        val request = YoutubeDLRequest(url).apply {
            addOption("-f", "bv*+ba/b")
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("-o", File(outDir, "%(title)s.%(ext)s").absolutePath)
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

    fun cleanMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return "Download failed."
        val line = raw.trim().lines().lastOrNull { it.isNotBlank() } ?: return "Download failed."
        return line.removePrefix("ERROR: ").trim().ifBlank { "Download failed." }
    }
}
```

- [ ] **Step 4: Run, expect PASS. Commit.**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/Downloader.kt app/src/test/java/io/github/zachisparanoid/grabreel/DownloaderTest.kt
git commit -m "feat: Downloader wrapper around yt-dlp best-quality download"
```

---

### Task 5: `MediaStorePublisher`

**Files:** Create `MediaStorePublisher.kt` (device-only; relies on tested `FileNames`)

- [ ] **Step 1: Implement**

```kotlin
package io.github.zachisparanoid.grabreel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object MediaStorePublisher {
    data class Published(val uri: Uri?, val displayName: String)

    fun publish(context: Context, src: File): Published {
        val name = FileNames.sanitize(src.name)
        val mime = FileNames.mimeForExtension(src.extension)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) publishQ(context, src, name, mime)
               else publishLegacy(src, name)
    }

    private fun publishQ(context: Context, src: File, name: String, mime: String): Published {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        src.delete()
        return Published(uri, name)
    }

    private fun publishLegacy(src: File, name: String): Published {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, FileNames.unique(name, dir.list()?.toSet() ?: emptySet()))
        src.copyTo(dest, overwrite = false)
        src.delete()
        return Published(Uri.fromFile(dest), dest.name)
    }
}
```

- [ ] **Step 2: Compile + commit**

Run: `./gradlew :app:compileDebugKotlin --console=plain` (expect SUCCESS)
```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/MediaStorePublisher.kt
git commit -m "feat: publish finished downloads into public Downloads/ via MediaStore"
```

---

### Task 6: `GrabReelApp` Application (engine init + self-update)

**Files:** Create `GrabReelApp.kt`

- [ ] **Step 1: Implement**

```kotlin
package io.github.zachisparanoid.grabreel

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GrabReelApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var engineReady = false
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

    companion object { private const val TAG = "GrabReelApp" }
}
```

- [ ] **Step 2: Compile + commit**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/GrabReelApp.kt
git commit -m "feat: Application initializes yt-dlp/FFmpeg and self-updates yt-dlp"
```

---

### Task 7: `DownloadService` foreground service

**Files:** Create `DownloadService.kt`

- [ ] **Step 1: Implement**

```kotlin
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

class DownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val active = AtomicInteger(0)
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) { stopIfIdle(); return START_NOT_STICKY }
        val id = UUID.randomUUID().toString()
        DownloadStore.add(Download(id = id, url = url, status = DownloadStatus.QUEUED))
        active.incrementAndGet()
        startForegroundCompat(ongoing("Starting download…", 0, true))
        executor.execute { runJob(id, url) }
        return START_NOT_STICKY
    }

    private fun runJob(id: String, url: String) {
        waitForEngine()
        DownloadStore.update(id) { it.copy(status = DownloadStatus.RUNNING) }
        val parent = File(cacheDir, "downloads")
        val result = Downloader.download(url, parent, id) { pct, eta ->
            DownloadStore.update(id) { it.copy(progress = pct, etaSeconds = eta) }
            notifications.notify(ONGOING_ID, ongoing("Downloading… $pct%", pct, false))
        }
        when (result) {
            is Downloader.Result.Success -> {
                try {
                    val pub = MediaStorePublisher.publish(this, result.file)
                    DownloadStore.update(id) {
                        it.copy(status = DownloadStatus.COMPLETED, progress = 100,
                                title = pub.displayName, filePath = pub.uri?.toString())
                    }
                    notifyDone(pub.displayName)
                } catch (e: Exception) {
                    DownloadStore.update(id) {
                        it.copy(status = DownloadStatus.FAILED, error = "Couldn't save to Downloads.")
                    }
                }
            }
            is Downloader.Result.Failure ->
                DownloadStore.update(id) { it.copy(status = DownloadStatus.FAILED, error = result.message) }
        }
        File(parent, id).deleteRecursively()
        active.decrementAndGet()
        stopIfIdle()
    }

    private fun waitForEngine() {
        val app = application as? GrabReelApp ?: return
        var waited = 0
        while (!app.engineReady && waited < 120_000) { Thread.sleep(250); waited += 250 }
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
                NotificationChannel(CH_PROGRESS, "Downloads", NotificationManager.IMPORTANCE_LOW))
            notifications.createNotificationChannel(
                NotificationChannel(CH_DONE, "Completed", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ongoing(text: String, pct: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("GrabReel").setContentText(text)
            .setOngoing(true).setProgress(100, pct, indeterminate)
            .setContentIntent(openAppIntent()).build()

    private fun notifyDone(name: String) {
        notifications.notify(name.hashCode(),
            NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Saved to Downloads").setContentText(name)
                .setAutoCancel(true).setContentIntent(openAppIntent()).build())
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val CH_PROGRESS = "downloads"
        private const val CH_DONE = "downloads_done"
        private const val ONGOING_ID = 1

        fun start(context: Context, url: String) {
            val i = Intent(context, DownloadService::class.java).putExtra(EXTRA_URL, url)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/DownloadService.kt
git commit -m "feat: foreground DownloadService runs yt-dlp and publishes results"
```

---

### Task 8: UI — layouts, adapter, MainActivity rewrite

**Files:** Rewrite `res/layout/activity_main.xml`; create `res/layout/item_download.xml`; create `DownloadsAdapter.kt`; rewrite `MainActivity.kt`

- [ ] **Step 1: `res/layout/activity_main.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="12dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <EditText
            android:id="@+id/urlInput"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="@string/paste_hint"
            android:inputType="textUri"
            android:maxLines="1" />

        <Button
            android:id="@+id/downloadButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/action_download" />
    </LinearLayout>

    <TextView
        android:id="@+id/empty"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="24dp"
        android:gravity="center"
        android:text="@string/empty_hint"
        android:textColor="#888888" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/list"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:clipToPadding="false"
        android:paddingBottom="12dp" />
</LinearLayout>
```

- [ ] **Step 2: `res/layout/item_download.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="12dp">

    <TextView
        android:id="@+id/title"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:maxLines="2"
        android:ellipsize="end"
        android:textStyle="bold"
        android:textSize="15sp" />

    <TextView
        android:id="@+id/status"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="13sp"
        android:textColor="#888888" />

    <ProgressBar
        android:id="@+id/progress"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:max="100" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="end">

        <Button
            android:id="@+id/action"
            style="?android:attr/buttonBarButtonStyle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content" />

        <Button
            android:id="@+id/remove"
            style="?android:attr/buttonBarButtonStyle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/action_remove" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 3: `DownloadsAdapter.kt`**

```kotlin
package io.github.zachisparanoid.grabreel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DownloadsAdapter(
    private val onOpen: (Download) -> Unit,
    private val onRetry: (Download) -> Unit,
    private val onRemove: (Download) -> Unit,
) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    private var items: List<Download> = emptyList()

    fun submit(list: List<Download>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val status: TextView = v.findViewById(R.id.status)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val action: Button = v.findViewById(R.id.action)
        val remove: Button = v.findViewById(R.id.remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val d = items[position]
        h.title.text = d.title ?: d.url
        h.status.text = when (d.status) {
            DownloadStatus.QUEUED -> "Queued"
            DownloadStatus.RUNNING -> if (d.etaSeconds >= 0) "Downloading… ${d.progress}% (ETA ${d.etaSeconds}s)" else "Downloading… ${d.progress}%"
            DownloadStatus.COMPLETED -> "Saved to Downloads"
            DownloadStatus.FAILED -> d.error ?: "Failed"
        }
        h.progress.visibility = if (d.status == DownloadStatus.RUNNING || d.status == DownloadStatus.QUEUED) View.VISIBLE else View.GONE
        h.progress.isIndeterminate = d.status == DownloadStatus.QUEUED
        if (d.status == DownloadStatus.RUNNING) h.progress.progress = d.progress

        when (d.status) {
            DownloadStatus.COMPLETED -> { h.action.text = "Open"; h.action.visibility = View.VISIBLE; h.action.setOnClickListener { onOpen(d) } }
            DownloadStatus.FAILED -> { h.action.text = "Retry"; h.action.visibility = View.VISIBLE; h.action.setOnClickListener { onRetry(d) } }
            else -> { h.action.visibility = View.GONE; h.action.setOnClickListener(null) }
        }
        h.remove.setOnClickListener { onRemove(d) }
    }
}
```

- [ ] **Step 4: Rewrite `MainActivity.kt`**

```kotlin
package io.github.zachisparanoid.grabreel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var input: EditText
    private lateinit var empty: TextView
    private lateinit var adapter: DownloadsAdapter

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.urlInput)
        empty = findViewById(R.id.empty)
        findViewById<Button>(R.id.downloadButton).setOnClickListener { submitFromInput() }

        adapter = DownloadsAdapter(
            onOpen = ::openDownload,
            onRetry = { DownloadStore.remove(it.id); DownloadService.start(this, it.url) },
            onRemove = { DownloadStore.remove(it.id) },
        )
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadStore.items.collect {
                    adapter.submit(it)
                    empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        requestNotifPermission()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val url = extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            if (url != null) startDownload(url) else toast(getString(R.string.share_no_link))
        }
    }

    private fun submitFromInput() {
        val text = input.text?.toString().orEmpty()
        val url = extractUrl(text) ?: text.trim()
        if (url.isBlank()) { toast(getString(R.string.share_no_link)); return }
        startDownload(url)
        input.text?.clear()
    }

    private fun startDownload(url: String) {
        DownloadService.start(this, url)
        toast(getString(R.string.share_queued))
    }

    private fun openDownload(d: Download) {
        val uri = d.filePath?.let { Uri.parse(it) } ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) { toast(getString(R.string.no_player)) }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_UPDATE, 0, R.string.menu_update)
        menu.add(0, MENU_CLEAR, 1, R.string.menu_clear_finished)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_UPDATE -> { updateExtractor(); true }
        MENU_CLEAR -> { DownloadStore.clearFinished(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun updateExtractor() {
        toast(getString(R.string.updating_extractor))
        (application as GrabReelApp).scope.launch {
            val msg = try {
                YoutubeDL.getInstance().updateYoutubeDL(this@MainActivity, YoutubeDL.UpdateChannel.STABLE)
                getString(R.string.update_done)
            } catch (e: Exception) { getString(R.string.update_failed, e.message ?: "") }
            runOnUiThread { toast(msg) }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    companion object { private const val MENU_UPDATE = 1; private const val MENU_CLEAR = 2 }
}
```

- [ ] **Step 5: Delete the old WebView download bridge file? No — `MainActivity.kt` fully replaced above.** Compile:

Run: `./gradlew :app:compileDebugKotlin --console=plain` (expect SUCCESS)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/MainActivity.kt app/src/main/java/io/github/zachisparanoid/grabreel/DownloadsAdapter.kt app/src/main/res/layout/activity_main.xml app/src/main/res/layout/item_download.xml
git commit -m "feat: native paste+list UI driving on-device downloads"
```

---

### Task 9: Manifest, strings, remove server-era files

**Files:** Modify `AndroidManifest.xml`, `res/values/strings.xml`; delete `res/xml/network_security_config.xml`

- [ ] **Step 1: Rewrite `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".GrabReelApp"
        android:allowBackup="true"
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:extractNativeLibs="true"
        android:theme="@style/Theme.GrabReel">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>

        <service
            android:name=".DownloadService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
    </application>
</manifest>
```

- [ ] **Step 2: Rewrite `res/values/strings.xml`**

```xml
<resources>
    <string name="app_name">GrabReel</string>

    <string name="paste_hint">Paste a video link…</string>
    <string name="action_download">Download</string>
    <string name="action_remove">Remove</string>
    <string name="empty_hint">Share a video to GrabReel, or paste a link above.</string>
    <string name="no_player">No app can open this file.</string>

    <string name="share_queued">Added to GrabReel</string>
    <string name="share_no_link">No video link found.</string>

    <string name="menu_update">Update extractor</string>
    <string name="menu_clear_finished">Clear finished</string>
    <string name="updating_extractor">Updating extractor…</string>
    <string name="update_done">yt-dlp is up to date.</string>
    <string name="update_failed">Update failed: %1$s</string>
</resources>
```

- [ ] **Step 3: Delete obsolete network config**

```bash
git rm app/src/main/res/xml/network_security_config.xml
```

- [ ] **Step 4: Full build + unit tests + lint**

Run: `./gradlew :app:testDebugUnitTest lintDebug assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL; APK at `app/build/outputs/apk/debug/app-debug.apk`. Fix any lint error (e.g., add `tools:targetApi` or missing strings) before proceeding.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
git commit -m "feat: manifest service+permissions, native strings, drop server config"
```

---

### Task 10: APK sanity + on-device install

- [ ] **Step 1: Confirm phone ABI matches the filter**

Run: `$ANDROID_HOME/platform-tools/adb -s 192.0.2.10:5555 shell getprop ro.product.cpu.abi`
Expected: `arm64-v8a`. If different, change `abiFilters` in Task 1 to match and rebuild.

- [ ] **Step 2: Inspect APK size + manifest**

Run: `ls -lh app/build/outputs/apk/debug/app-debug.apk` and
`$ANDROID_HOME/build-tools/35.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep -iE "native-code|sdkVersion|uses-permission"`
Expected: `native-code: 'arm64-v8a'`, the three new permissions present.

- [ ] **Step 3: Install**

Run: `./push.sh 192.0.2.10:5555`
Expected: `Success`.

---

### Task 11: On-device verification

- [ ] **Step 1: Launch + let the engine init / yt-dlp update (first run)**

Run: `adb -s 192.0.2.10:5555 shell am start -n io.github.zachisparanoid.grabreel/.MainActivity`
Then wait ~20–40 s and check logcat for engine init:
`adb -s 192.0.2.10:5555 logcat -d -t 200 | grep -iE "grabreel|youtubedl|ffmpeg"`

- [ ] **Step 2: Trigger a real download via share intent**

Run:
```bash
adb -s 192.0.2.10:5555 shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://www.youtube.com/watch?v=aqz-KE-bpKQ" \
  io.github.zachisparanoid.grabreel/.MainActivity
```
(Big Buck Bunny — public, license-free, safe to download as a test.)

- [ ] **Step 3: Confirm the file landed in Downloads**

Poll (downloads take time): 
`adb -s 192.0.2.10:5555 shell ls -la /sdcard/Download/ | grep -iE "buck|bunny|.mp4"`
Expected: an mp4 appears. Also check `adb logcat` for completion / errors. Capture a screenshot for the record:
`adb -s 192.0.2.10:5555 exec-out screencap -p > /tmp/grabreel_done.png`

- [ ] **Step 4: Document the result** in the completion report (size, filename, any issues).

---

### Task 12: Finish (no merge — stays on branch)

Per the user's instruction, keep all work on `feat/standalone-ondevice`; do **not** merge to master. Verify the tree is clean and summarize the final state, commits, APK size, and the on-device verification outcome.
