# GrabReel for Android

A standalone, **on-device** video downloader. Paste or share a link — Instagram,
TikTok, X, YouTube, Facebook, and anything else [`yt-dlp`](https://github.com/yt-dlp/yt-dlp)
supports — and GrabReel downloads it entirely on the phone: `yt-dlp` + `ffmpeg`
run locally and the finished video lands in your **Downloads** folder via
MediaStore. No server required.

> Earlier versions were a thin WebView client for a separate GrabReel server.
> That server dependency has been removed — extraction now happens on-device.
> See [`docs/superpowers/specs/2026-05-24-standalone-grabreel-design.md`](docs/superpowers/specs/2026-05-24-standalone-grabreel-design.md).

## How it works

- On first launch the app initializes the bundled `yt-dlp` + `ffmpeg` (and
  self-updates `yt-dlp`; you can force this from **⋮ → Update extractor**).
- Paste a link in the app, or **Share → GrabReel** from any app (it registers
  as a share target for `text/plain`).
- A foreground service runs the download on-device, shows progress in a
  notification and the in-app list, and saves the result to public
  **Downloads/** via MediaStore.

## Instagram sign-in

Instagram now blocks logged-out access to most reels, so `yt-dlp` can't fetch
them anonymously — you'll see *"Instagram needs you to sign in…"*. To fix it,
sign in once inside the app:

**⋮ → Accounts → Instagram → Log in** — an in-app browser opens Instagram's
login page; after you log in (2FA included), GrabReel captures the session and
reuses it for every Instagram download. **Log out** clears it.

The session is stored privately on-device (in no-backup storage) and is only
ever sent to Instagram via `yt-dlp`. Using an account to download can
occasionally trip Instagram's rate limits or a security checkpoint; if a
download starts failing with the sign-in hint again, log out and back in to
refresh the session. See
[`docs/superpowers/specs/2026-06-28-instagram-cookie-auth-design.md`](docs/superpowers/specs/2026-06-28-instagram-cookie-auth-design.md).

Other platforms (TikTok, X, YouTube, Facebook) download without any sign-in.

## Build

Requires JDK 17 and the Android SDK (`platform-tools`, `platforms;android-34`,
`build-tools;34.0.0`). `local.properties` points `sdk.dir` at the SDK.

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

./gradlew testDebugUnitTest   # unit tests (URL/platform parsing, cookies, store)
```

## Pushing to your phone

`./push.sh <host:port> [apk]` runs `adb connect` + `adb install -r` against your
phone's ADB-over-TCP endpoint.

1. On the phone: **Settings → Developer options → Wireless debugging = ON**, and
   note the **IP address & Port** shown there. A first connection from a new
   machine also needs a one-time `adb pair <host>:<pairPort> <code>`.
2. Make sure this machine can reach the phone over the network (same LAN, or a
   VPN/overlay such as Tailscale). The wireless-debug port rotates, so if
   `push.sh` reports "Connection refused", re-check the port.
3. Then, for example:
   ```bash
   ./push.sh 192.168.1.50:37000
   ```

## Scope

Personal sideloaded tool. On-device extraction (no server). Cookie-based
sign-in is wired up for **Instagram only** (the other platforms don't need it
yet, though the plumbing is platform-general). No release signing, push
notifications, or offline queue.

## License

[MIT](LICENSE) © 2026 zachisparanoid
