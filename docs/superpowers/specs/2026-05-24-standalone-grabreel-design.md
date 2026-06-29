# Standalone GrabReel (on-device, server-free) — Design Spec

**Date:** 2026-05-24
**Status:** Approved (design approved in brainstorming; spec pending user review)

## Goal

Turn GrabReel from a thin WebView client of an external server into a **fully
self-contained Android app** that extracts and downloads videos **on-device**
using `yt-dlp` + FFmpeg. No server to run; works on any network; nothing leaves
the phone except traffic to the video platforms themselves.

This deliberately reverses the "On-device backend" alternative rejected in
`2026-05-18-android-app-design.md`. The motivation (all confirmed by the user):
stop running a server, work anywhere, privacy/on-device, single artifact.

## Scope (streamlined)

In: share→download at **best** quality · in-app paste field · a Downloads list
with live progress · completion notification.

Out (YAGNI): quality/format picker, audio-only, playlists, subtitles/metadata,
custom save folder (SAF), in-app player, and cookies/login for private content
(public videos work; private/age-gated are a known limitation).

## Engine: youtubedl-android (verified)

Maven Central, actively maintained (the fork powering the Seal app):

```kotlin
val ytdl = "0.18.1"
implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")
```

- Bundles a lazy-extractor `yt-dlp` that works immediately; refreshable at runtime.
- Init once: `YoutubeDL.getInstance().init(app)` + `FFmpeg.getInstance().init(app)`.
- Download: `YoutubeDLRequest(url).addOption("-f","bv*+ba/b").addOption("-o",template)`
  then `YoutubeDL.getInstance().execute(request, processId, callback)` — a
  **blocking** call returning a `YoutubeDLResponse`; the callback reports
  `(progress, etaInSeconds, line)`. Cancel via `destroyProcessById(processId)`.
- Self-update: `YoutubeDL.getInstance().updateYoutubeDL(app, UpdateChannel.STABLE)`.
- ABIs shipped: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.

Refs: https://github.com/yausername/youtubedl-android ·
https://github.com/JunkFood02/Seal

## Architecture & Components

Each unit has one responsibility and a clear interface.

### `GrabReelApp : Application`
Initializes `YoutubeDL` and `FFmpeg` at startup (in a background thread; init
unpacks the Python env on first launch). Kicks a throttled yt-dlp self-update.
Owns the **download registry**: a `StateFlow<List<Download>>` the UI observes.
`Download` is a small data class (id, url, title?, status, progress, filePath?,
error?). Status ∈ {QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED}.

### `Downloader`
Thin wrapper over youtubedl-android. Input: a URL + an output directory +
progress lambda. Builds the `YoutubeDLRequest` for true best quality —
`-f "bv*+ba/b"` (best video + best audio, falling back to best pre-muxed) with
`--merge-output-format mp4`, `-o "<dir>/%(title)s.%(ext)s"`, `--no-playlist` —
runs `execute`, maps the result/exception to success(file) | failure(message).
Knows nothing about notifications, UI, or MediaStore.

### `DownloadService` (foreground service, `foregroundServiceType="dataSync"`)
The heart. Started with an `EXTRA_URL`. For each URL: marks the registry entry
RUNNING, runs `Downloader` on a background executor, throttles progress into the
ongoing notification + registry, and on success calls `MediaStorePublisher`,
then COMPLETED + a tap-to-open completion notification; on failure FAILED with
the message. Processes a simple internal queue so concurrent shares serialize
(youtubedl-android runs one Python process per call; serializing avoids
contention). Stops foreground when the queue drains. Cancel action →
`destroyProcessById`.

### `MediaStorePublisher`
yt-dlp writes into the app's private external files dir (no permission needed).
This moves the finished file into public `Downloads/`:
- **API ≥ 29:** `MediaStore.Downloads` insert (`RELATIVE_PATH=Download/`,
  `IS_PENDING`), stream-copy, clear pending. No storage permission.
- **API ≤ 28:** copy to `Environment.DIRECTORY_DOWNLOADS` using the legacy
  `WRITE_EXTERNAL_STORAGE` (maxSdkVersion=28) already in the manifest, then
  `MediaScannerConnection.scanFile`.
Pure helpers (filename sanitization, collision suffixing, MIME from extension)
are unit-tested.

### `MainActivity` + `DownloadsAdapter`
One screen, plain AppCompat Views: a paste `EditText` + "Download" button on
top; a `RecyclerView` below bound to the registry `StateFlow`
(`repeatOnLifecycle`). Row states: RUNNING (determinate progress + cancel),
COMPLETED (tap → `ACTION_VIEW` the file via FileProvider/MediaStore uri), FAILED
(Retry), plus swipe/clear to remove from the list. Overflow menu: "Update
extractor", "Clear finished". Replaces the WebView and the server-URL dialog.

### Share target (carried over)
The `ACTION_SEND` / `text/plain` intent-filter and `extractUrl` (+ its 7 tests)
remain. `handleIntent` extracts the URL and `startForegroundService(DownloadService)`
with it, instead of POSTing to a server. The "fully automatic, then watch"
behavior is preserved: queue immediately, show the list.

## Data Flow

```
Share/paste URL → MainActivity → startForegroundService(DownloadService, url)
  → registry: add QUEUED → RUNNING ; ongoing "Downloading…" notification
  → Downloader: yt-dlp -f bv*+ba/b --merge-output-format mp4 -o <appExtDir>/...  (FFmpeg muxes)
      → progress(pct,eta,line) → throttle → update notification + row
  → success → MediaStorePublisher → Downloads/<name> → COMPLETED
      → "Saved <name>" notification (tap → open) ; row → open
  → failure → FAILED(message) ; row → Retry
  → queue empty → stopForeground / stopSelf
```

## Permissions / Manifest

- Keep: `INTERNET`; `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`).
- Add: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
  `POST_NOTIFICATIONS` (requested at runtime on API ≥ 33).
- Add `<service android:name=".DownloadService" android:foregroundServiceType="dataSync" android:exported="false"/>`.
- Remove `android:networkSecurityConfig` + `res/xml/network_security_config.xml`
  (no cleartext server anymore).
- `MainActivity` keeps `launchMode="singleTask"` and both intent-filters
  (LAUNCHER + SEND).

## Build

- `minSdk` 24 (unchanged), target/compile 34. Verify youtubedl-android 0.18.1
  accepts minSdk 24 at sync; bump only if it demands it.
- `defaultConfig { ndk { abiFilters += "arm64-v8a" } }` after confirming the
  phone reports `arm64-v8a` (`adb shell getprop ro.product.cpu.abi`). Halves the
  APK (~100 MB → ~45 MB). Document how to re-enable all ABIs.
- Add coroutines (`kotlinx-coroutines-android`) for the registry `StateFlow` and
  service work; add `androidx.lifecycle:lifecycle-runtime-ktx` for
  `repeatOnLifecycle`. (First real dependencies beyond the current three.)

## Error Handling

No/blocked network, unsupported/invalid URL, **stale extractor** (surface
yt-dlp's message + offer "Update extractor"), storage-full / publish failure,
and user cancel — all land on the relevant download row and a toast where
appropriate. No crashes; the service always releases foreground.

## Testing Strategy

1. **Unit (JVM):** keep `extractUrl` tests; add pure-helper tests for
   `MediaStorePublisher` (sanitize filename, dedupe collisions, MIME by ext) and
   any pure mapping in `Downloader`/registry.
2. **Build:** `./gradlew :app:testDebugUnitTest lintDebug assembleDebug` green;
   APK size sanity-checked.
3. **On-device (manual):** install; first launch performs yt-dlp update; share a
   public reel → progress shows → file appears in `Downloads/` → completion
   notification opens it; airplane-mode → graceful failure + Retry.

## Migration / Rollback

All work on `feat/standalone-ondevice`. `master` retains the working
server-based app (incl. the share-to-server feature) until the on-device build
is proven on the phone. The `video-downloader` server repo is untouched and
simply no longer required by the app.
