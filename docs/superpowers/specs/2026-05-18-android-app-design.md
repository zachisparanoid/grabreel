# GrabReel Android — Design Spec

**Date:** 2026-05-18
**Status:** Approved (user pre-authorized autonomous execution)

## Goal

Ship an Android application that lets the user drive the existing GrabReel
web service from their phone. The app must "just utilize the services offered
by the web application in the background" — no reimplementation of download
logic on-device.

## Context

GrabReel is a single-service web app: a React SPA served by a FastAPI backend
that runs `yt-dlp` + `ffmpeg` server-side. All SPA→server calls use **relative**
paths (`/api/probe`, `/api/jobs`, `/api/files/{id}`). The "save" action is an
`<a download href="/api/files/{id}">`; the backend returns that file as a
`StreamingResponse` with `Content-Disposition: attachment` and HTTP Range
support. The server currently runs at `http://192.0.2.20:8090` (LAN,
cleartext HTTP).

## Approach

A native **Kotlin single-Activity WebView shell** that loads the GrabReel SPA
directly from the running server. The app is a branded, full-screen client;
the server keeps doing all extraction/muxing.

Rejected alternatives:
- **Capacitor/Cordova:** adds a JS build toolchain but the API server is still
  mandatory — no benefit, more moving parts.
- **On-device backend (Chaquopy/Termux + yt-dlp/ffmpeg):** effectively a
  rewrite, large/fragile APK, contradicts the "background services" intent.

## Architecture & Components

- **`MainActivity`** — hosts a full-screen `WebView`. JavaScript + DOM storage
  enabled. Hardware back button navigates WebView history (falls back to
  default finish at history root).
- **Server URL setting** — persisted in `SharedPreferences`
  (`base_url`, default `http://192.0.2.20:8090`). Editable via an overflow
  menu dialog. The WebView loads `${baseUrl}/`; relative API calls resolve to
  that origin automatically.
- **Download bridge** — `WebView.setDownloadListener` builds a
  `DownloadManager.Request` for the resolved URL: forwards cookies and
  user-agent, derives the filename from the `Content-Disposition` /
  `URLUtil.guessFileName`, sets MIME, enables the visible completion
  notification, and writes to the public `Downloads/` directory.
- **`network_security_config.xml`** — permits cleartext traffic (the server is
  HTTP on a private/tailnet address).
- **Manifest** — `INTERNET`; `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion=28`
  for the legacy DownloadManager path (no extra permission on API 29+).
- **Error screen** — WebView `onReceivedError` / load timeout shows a
  friendly "Can't reach GrabReel at \<url\>" view with **Retry** and
  **Settings** actions, mirroring the SPA's own network-error wording.

## Data Flow

```
App launch → WebView GET ${baseUrl}/ → SPA boots in WebView
  → user pastes link → SPA POST ${baseUrl}/api/probe → preview
  → SPA POST ${baseUrl}/api/jobs → poll GET /api/jobs/{id}
  → status "ready" → user taps "Download to computer"
  → WebView hits /api/files/{id} (attachment) → DownloadListener
  → DownloadManager streams file (Range) → Downloads/ + notification
```

## Build & Tooling

- JDK 17 (present), Node (present, unused for this approach).
- Install Android SDK under `$HOME/Android/Sdk` (no sudo): `cmdline-tools`,
  `platform-tools` (adb), `platforms;android-34`, `build-tools;34.0.0`.
- Gradle via the project Gradle wrapper (AGP 8.x / Gradle 8.x).
- Target/compile SDK 34, minSdk 24 (covers the vast majority of devices and
  modern WebView).

## Testing Strategy

1. **Backend sanity** — existing pytest suite still green (unaffected).
2. **Build** — `./gradlew assembleDebug` yields a valid APK; verify with
   `aapt dump badging` (package id, launchable activity, permissions).
3. **Static** — `compileDebugKotlin` and `lintDebug` clean.
4. **Functional** — `adb install` to the phone, launch, confirm: SPA loads,
   a probe returns a preview, a download lands in `Downloads/`.

## Deployment / Push

Target `192.0.2.10:5555` is an ADB-over-TCP endpoint on a **Tailscale**
address. This server is not on the tailnet and cannot currently reach it.
Plan: install adb + Tailscale, attempt `adb connect` + `adb install`. Joining
the tailnet requires a one-time interactive `tailscale up` login that only the
user can complete — the single point where autonomous execution may pause,
with the APK + exact commands handed back.

## Out of Scope (YAGNI)

Push notifications, offline mode, on-device extraction, app store packaging,
signing for release, multi-server profiles, pull-to-refresh.
