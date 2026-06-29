# GrabReel Android — "Share to GrabReel" Design Spec

**Date:** 2026-05-23
**Status:** Approved (user pre-authorized autonomous execution through to phone install)

## Goal

Let the user share a video link from any app (Instagram, YouTube, TikTok,
Facebook, X, LinkedIn…) **directly to GrabReel** via the Android system share
sheet. Selecting GrabReel queues a "best quality" download on the existing
server and brings the app to the foreground showing the Downloads list, so the
user watches it progress and can save the finished file.

This removes today's manual dance: copy link → open GrabReel → paste → fetch →
download.

## Decisions (from brainstorming)

1. **Share behavior:** *fully automatic* — queue the download immediately at
   `format: "best"`. No probe, no preview, no quality picker on the share path.
2. **Where the user lands:** *open GrabReel showing the Downloads list*, so the
   new job is visible and progresses live.
3. **Handoff mechanism:** *native API call* (Approach C). The Android app POSTs
   the link to the server's existing `/api/jobs` endpoint. **No server/SPA
   change, no Docker redeploy.**

## Context

GrabReel is a server-side service (React SPA + FastAPI running `yt-dlp` +
`ffmpeg`); this Android app is a thin WebView client (see
`2026-05-18-android-app-design.md`). Relevant facts that shape this feature:

- **Queuing is one self-contained call.** `POST /api/jobs` with
  `{"url": ..., "format": "best"}` returns `201` + the job; it does **not**
  require a prior `/api/probe` (the probe only feeds the preview card). The
  server validates the platform itself and returns `422 {detail:{error,message}}`
  for unsupported links (`backend/routes/api.py:54`, `backend/models.py:55`).
- **The server does the downloading, not the phone.** The backend pulls the
  video to its own `downloads/` folder; the phone's `DownloadManager` only
  fetches the finished file later if the user taps "save". So "share → start a
  download" needs nothing but the one POST.
- **Progress polling has an ordering constraint.** The SPA polls the Downloads
  list *only while a job is active* and derives "active" from the jobs fetched
  on mount (`frontend/src/App.tsx:66-71`). The job must therefore exist
  **before** the page loads, or the list shows nothing and polling never starts.
- **No networking deps in the app.** `app/build.gradle.kts` pulls only
  core-ktx, appcompat, material. Cleartext HTTP is already permitted app-wide by
  `res/xml/network_security_config.xml` (which also governs `HttpURLConnection`,
  not just the WebView).

## Approach

Add an `ACTION_SEND` (`text/plain`) intent-filter to `MainActivity` so GrabReel
appears in the share sheet. When launched (or re-launched) via share, extract
the URL from the shared text, `POST /api/jobs` natively on a background thread,
then load the Downloads page. Normal (launcher) starts are unchanged.

Rejected alternatives:
- **Through the web app (query param + React auto-submit):** keeps native a pure
  shell, but requires editing the SPA and rebuilding/redeploying the Docker
  image on every change, and the handoff depends on page-boot JS timing.
- **Simulating taps via injected JavaScript:** brittle for a full-auto flow —
  it must wait on async probe → preview → download UI transitions.

## Architecture & Components

### 1. Manifest — register the share target
`app/src/main/AndroidManifest.xml`: add a second `<intent-filter>` to
`.MainActivity`, and `android:launchMode="singleTask"` so a share while the app
is already open is delivered to the running instance via `onNewIntent` rather
than stacking a new one.

```xml
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/plain" />
</intent-filter>
```

### 2. `MainActivity` — route by launch intent
- `onCreate` calls `handleIntent(intent)` instead of calling `loadHome()`
  directly.
- Override `onNewIntent(intent)` → `setIntent(intent)` then `handleIntent(intent)`
  (covers "app already running").
- `handleIntent(intent)`: if `action == ACTION_SEND && type == "text/plain"`,
  read `EXTRA_TEXT`, `extractUrl(...)`; if a URL is found → `queueShare(url)`,
  else toast "No link found in shared text" + `loadHome()`. Any other intent →
  `loadHome()` (unchanged behavior).

### 3. `extractUrl(sharedText): String?` — pull the link out
Shared text is frequently `"caption text… https://real.link/xyz"`, so locate the
first `http(s)` URL within the string rather than assuming the whole string is a
bare URL. Trim trailing punctuation/whitespace. Return `null` when none is
found. Pure function → unit-tested first (TDD).

### 4. `queueShare(url)` — the native API call
Show the progress indicator; on a background `Thread`, open an
`HttpURLConnection` to `${baseUrl()}/api/jobs` and POST
`{"url": url, "format": "best"}` with `Content-Type: application/json`. Read the
status code and body, then resolve on the UI thread via `runOnUiThread`:

- **201** → toast "Added to GrabReel", `loadHome()`.
- **4xx** with a JSON `detail.message` → toast that message, `loadHome()`.
- **network failure / exception** → toast "Can't reach GrabReel", `loadHome()`
  (the existing error screen shows if the server is truly down).

No new dependency: JDK `HttpURLConnection` + `Thread` + `runOnUiThread`.

### 5. Ordering guarantee
POST completes **before** `loadHome()`, so the page's first `listJobs()` already
contains the new active job → `hasActive` is true → 1 s polling starts → live
progress. (Directly addresses the `App.tsx:66-71` constraint above.)

## Data Flow

```
Instagram/YouTube → Share → "GrabReel"
  → Android delivers ACTION_SEND text/plain (EXTRA_TEXT)
  → MainActivity.handleIntent → extractUrl
  → queueShare: POST ${baseUrl}/api/jobs {url, format:"best"}
      → server detect_platform → insert job → manager.submit → 201
  → runOnUiThread: toast "Added to GrabReel" → loadHome()
  → WebView GET ${baseUrl}/ → SPA lists jobs (includes new active job)
      → polling starts → user watches progress
  → (later) user taps save → /api/files/{id} → DownloadManager → Downloads/
```

## Edge Cases

- **App already open** → handled by `singleTask` + `onNewIntent`.
- **No URL in shared text** → toast, then normal home load.
- **Unsupported link** → server `422`; its `detail.message` shown in a toast.
- **Server unreachable** → toast + existing "Can't reach GrabReel" error screen.
- **Photo/video file shares** → only `text/plain` is registered, so GrabReel
  intentionally does not appear for non-text shares.

## Testing Strategy

1. **TDD `extractUrl`** — add a minimal JUnit `src/test/` source set (the app
   module has none yet) and cover: bare URL, URL embedded in caption text, no
   URL, trailing punctuation, multiple URLs (first wins), `http` vs `https`.
2. **Build** — `./gradlew assembleDebug` produces a valid APK; confirm the new
   `SEND` intent-filter with `aapt dump badging`.
3. **Static** — `compileDebugKotlin` and `lintDebug` clean.
4. **Simulated share (no phone needed)**:
   ```
   adb shell am start -a android.intent.action.SEND -t text/plain \
     --es android.intent.extra.TEXT "https://www.instagram.com/reel/XXXX/" \
     io.github.zachisparanoid.grabreel/.MainActivity
   ```
5. **Functional** — install on the phone, share a reel from Instagram, confirm
   the job appears in Downloads and the server downloads it.

## Deployment / Push

Approach C requires **no server redeploy**. Build the APK, then
`./push.sh 192.0.2.10:5555` (now the default in `push.sh`). The push needs
this host on the Tailscale tailnet (`tailscale up` — interactive, user-only);
if `adb connect` fails, that login is the single remaining manual step and the
APK + exact command are handed back.

## Out of Scope (YAGNI)

Quality selection on the share path, handling shared media **files** (links
only), batch/multi-URL shares, per-platform special-casing, share-sheet direct
share targets / shortcuts.
