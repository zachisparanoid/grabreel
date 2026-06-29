# Instagram Cookie Authentication — Design

**Date:** 2026-06-28
**Branch:** feat/standalone-ondevice
**Status:** Implemented on branch `feat/instagram-cookie-auth` (2026-06-28); pending on-device verification

## Background

Instagram began enforcing a login wall on most Reels/posts: anonymous (logged-out)
requests now receive no media. In GrabReel this surfaces as yt-dlp failing with
*"Instagram sent an empty media response. Check if this post is accessible in your
browser without being logged-in."* The same posts are also unviewable in a
logged-out browser, confirming the cause is Instagram's wall, not the device,
network, or a GrabReel bug. **Only Instagram is affected** — TikTok, X, YouTube,
and Facebook still download fine, so this is Instagram-specific, not an
IP/rate-limit problem.

yt-dlp's documented remedy is to supply authenticated account cookies via
`--cookies <file>` (yt-dlp issues #14241, #11151; the Instagram extractor gates its
reliable API path on a `sessionid` cookie). The prior standalone design
(`2026-05-24-standalone-grabreel-design.md`) explicitly scoped "cookies/login for
private content" **out**; this design deliberately reverses that decision for
Instagram.

### Why we can't reuse the installed apps' logins

The original request was to reuse the session from the installed Instagram/Facebook/
X/TikTok/YouTube apps. This is **not possible on a non-rooted Android device**:
Android isolates every app under its own UID and private data directory, and WebView
keeps a cookie store separate from the Chrome app and from native apps. There is no
supported API for one app to hand its session to another for scraping. The realistic
equivalent — and what this design delivers — is for GrabReel to host its **own**
in-app login, capture the resulting session, and feed it to yt-dlp. This mirrors the
established Android downloader *ytdlnis*.

## Goals

- Restore Instagram reel downloads by authenticating yt-dlp with the user's logged-in
  Instagram session.
- Let the user log in **inside GrabReel** via a WebView; capture cookies automatically.
- Persist the session so it is reused on every download without re-login.
- Detect Instagram auth failures (wall / checkpoint) and prompt a one-tap re-login.
- Keep the plumbing platform-general so other platforms can opt into cookies later,
  but only wire up **Instagram** now.

## Non-goals (YAGNI)

- No login screens for TikTok/X/YouTube/Facebook (they work unauthenticated today).
- No burner-account automation, account pooling, proxies, or server-side extraction.
- No `--cookies-from-browser` (it reads a desktop browser profile that does not exist
  on-device).
- No download of private/followers-only content beyond what the logged-in account can
  already see.

## Key decisions (confirmed with the user)

1. **Account:** the user will log in with their **real** Instagram account.
   Accepted tradeoff: download activity (vs. browsing) can get an account temporarily
   locked/challenged. Mitigations below make re-auth a one-tap recovery, and switching
   to a throwaway account later requires no code change (just log in as that account).
2. **Acquisition:** **in-app WebView login** (not desktop cookies.txt import).
   Cookies minted on the same device/IP that downloads them last longer before
   Instagram challenges them.
3. **Scope:** Instagram-first; general plumbing.

## Architecture

### Data flow

```
paste / share a URL
  └─ DownloadService.runJob(url)
       ├─ platform   = platformFor(url)                  // NEW
       ├─ auth       = CookieStore.authFor(platform)     // NEW → (cookiesFile, userAgent) or null
       └─ Downloader.download(url, …, cookiesFile, userAgent)
              └─ yt-dlp  --cookies <file>  --add-header "User-Agent:<ua>"   // NEW when present

login (user-initiated, ⋮ → Accounts → Instagram → Login)
  └─ LoginActivity(WebView → instagram.com/accounts/login/)
       └─ on detecting sessionid cookie:
            ├─ CookieManager.flush()
            ├─ cookies = CookieManager.getCookie("https://www.instagram.com/")   // full https URL!
            ├─ ua      = webView.settings.userAgentString
            └─ CookieStore.save(INSTAGRAM, cookies, ua)  → writes Netscape cookies.txt + UA
```

### Components

**New files (4):**

1. **`Platform` enum + `platformFor(url)`** — added to `ShareUrl.kt`.
   `enum class Platform { INSTAGRAM, TIKTOK, X, YOUTUBE, FACEBOOK, OTHER }` and a
   host-based classifier parsing `Uri.parse(url).host`:
   `instagram.com → INSTAGRAM`, `tiktok.com → TIKTOK`,
   `x.com`/`twitter.com → X`, `youtube.com`/`youtu.be → YOUTUBE`,
   `facebook.com`/`fb.watch`/`m.facebook.com → FACEBOOK`, else `OTHER`.
   Pure and unit-tested (mirrors `ShareUrlTest`). The enum covers all platforms for
   future use, but only `INSTAGRAM` will have cookies stored now, so `authFor` returns
   `null` for every other platform and their downloads stay unauthenticated/unchanged.

2. **`CookieStore.kt`** — owns persistence and the Netscape serializer.
   - `save(platform, rawCookieHeader, userAgent)` — writes
     `noBackupFilesDir/cookies/<platform>.txt` and records the UA + last-login time in
     a small `SharedPreferences` (`"grabreel_accounts"`).
   - `authFor(platform): Auth?` — returns `Auth(cookiesFile, userAgent)` only when a
     non-empty cookies file exists; else `null`.
   - `isLoggedIn(platform): Boolean`, `clear(platform)` (deletes file + prefs).
   - **`NetscapeCookies.serialize(rawCookieHeader, domain): String`** — a pure,
     unit-tested function converting CookieManager's `"name=value; name2=value2"`
     string into a Netscape cookies.txt (details below).

3. **`LoginActivity.kt`** — a WebView login screen. Receives target `Platform` via
   Intent extra; loads that platform's login URL
   (`https://www.instagram.com/accounts/login/`). On each `onPageFinished`, checks
   whether `CookieManager.getCookie("https://www.instagram.com/")` contains
   `sessionid`; once present, flushes, captures cookies + UA, calls
   `CookieStore.save(...)`, and finishes with `RESULT_OK`. `exported=false`.

4. **`AccountsActivity.kt`** — for now shows a single **Instagram** row with a
   logged-in/out indicator and a Login/Logout button (other platforms are added here
   only if/when they need login). Login launches `LoginActivity`; Logout calls
   `CookieStore.clear(...)`. `exported=false`. Row styling mirrors `item_download.xml`.

**Edited files (4):**

5. **`Downloader.kt`** — `download(...)` gains `cookiesFile: File? = null` and
   `userAgent: String? = null`. Inside the existing `YoutubeDLRequest.apply { }`:
   ```kotlin
   if (cookiesFile != null) addOption("--cookies", cookiesFile.absolutePath)
   if (userAgent != null)   addOption("--add-header", "User-Agent:$userAgent")
   ```
   Optional args keep public (TikTok/YouTube/…) downloads byte-for-byte unchanged.

6. **`DownloadService.kt`** — in `runJob`, before `Downloader.download(...)`:
   resolve `platformFor(url)` and `CookieStore.authFor(platform)` on the existing
   background executor thread, and pass `cookiesFile`/`userAgent` through. Also
   classify auth failures (below) and replace the raw yt-dlp message with a friendly
   *"Sign in to Instagram under ⋮ → Accounts, then retry."*

7. **`MainActivity.kt`** — add `MENU_ACCOUNTS` (id `3`, order `2`) in
   `onCreateOptionsMenu`; handler does `startActivity(Intent(this, AccountsActivity::class.java))`.
   (Note: existing `MENU_UPDATE=1`, `MENU_CLEAR=2` constants — keep ids unique.)

8. **`AndroidManifest.xml`** — register `AccountsActivity` and `LoginActivity`
   (`exported=false`). No new permissions: `INTERNET` already covers the WebView; cookie
   files in app-private `noBackupFilesDir` need no storage permission.

## Critical implementation details (verified against primary sources)

These are load-bearing; getting any one wrong causes a silent "logged in but downloads
still fail" outcome.

1. **Capture with a full `https://` URL.** Call
   `CookieManager.getInstance().getCookie("https://www.instagram.com/")` — **never a
   bare domain.** WebView's `getCookie` includes Secure + **HttpOnly** cookies
   (Instagram's `sessionid` is HttpOnly) only for an `https://` URL; a bare domain
   silently omits them. Confirmed from the Chromium WebView source
   (`cookie_manager.cc` uses `CookieOptions::MakeAllInclusive()`).

2. **Dump all cookies; don't cherry-pick `sessionid`.** Write every pair `getCookie`
   returns. yt-dlp needs `sessionid` (gates the authed API path) **and** `csrftoken`
   (sent as `X-CSRFToken`); `ds_user_id`/`mid`/`ig_did` ride along and reduce
   checkpoint/anti-bot rejections.

3. **Synthesize the 5 missing columns.** `getCookie` returns only `name=value` pairs
   (no attributes). For each, emit: domain `.instagram.com`, include_subdomains `TRUE`,
   path `/`, secure `TRUE`, expiry `0` (yt-dlp accepts `0` as a session cookie and
   keeps it).

4. **Exact Netscape format.** First line **must** be `# Netscape HTTP Cookie File`.
   Then one line per cookie, **7 TAB-separated columns** in order:
   `domain⇥include_subdomains⇥path⇥secure⇥expiry⇥name⇥value`. Use **LF** newlines.
   The `#HttpOnly_` domain prefix is optional (yt-dlp strips it if present) — omit it.
   A wrong header is a hard load error; a malformed line is silently dropped.

5. **Cookie acceptance + flush.** Before loading: `CookieManager.setAcceptCookie(true)`
   and `CookieManager.setAcceptThirdPartyCookies(webView, true)`. After login:
   `CookieManager.getInstance().flush()` so cookies persist to disk. All required APIs
   exist since API 21 (minSdk is 24).

6. **User-Agent binding.** Persist `webView.settings.userAgentString` at capture time
   and re-send it to yt-dlp via `--add-header "User-Agent:<ua>"`. Instagram binds the
   session to the UA; a mismatch between the WebView that minted the session and
   yt-dlp's default UA can invalidate it.

## Auth-failure detection

In `DownloadService.runJob`, when `platform == INSTAGRAM` and the result is a failure
whose message matches any of (case-insensitive): `login required`,
`rate-limit reached or login required`, `empty media response`,
`requested content is not available`, `checkpoint`, `challenge_required` — replace the
displayed error with the re-login hint. This turns Instagram locking the session into
an obvious, recoverable action rather than a cryptic failure.

## Safety nets for real-account use

- Downloads already run serialized on a single-thread executor (no parallel bursts).
- Add a small randomized inter-download delay (e.g. 3–8 s) before consecutive
  Instagram jobs to avoid burst patterns that trigger locks.
- Re-auth is one tap (the detection above + the Accounts screen).
- Session is reused (never re-logs in per download), matching community guidance that
  steady reuse is safer than repeated logins.

## Security & privacy

- Cookie files live in **`noBackupFilesDir/cookies/`**, not `filesDir`, because
  `android:allowBackup="true"` would otherwise sweep session credentials into
  cloud/adb backups.
- Cookies are device-local; nothing is sent anywhere except to Instagram via yt-dlp.
- Logout deletes the cookies file and clears the stored UA/metadata; optionally clear
  the WebView cookie store for the domain.

## Testing strategy

**Unit tests (JVM, no device):**
- `platformFor()` host classification (incl. `youtu.be`, `m.facebook.com`,
  `twitter.com → X`, query strings, unknown → `OTHER`).
- `NetscapeCookies.serialize()` — correct header, exact tab columns, LF newlines,
  multi-cookie input, empty input → header-only, value containing `=`.
- Auth-failure message classifier — each known phrase maps to the hint; unrelated
  errors pass through unchanged.

**On-device manual test (the real proof — Instagram can only be verified live):**
1. Install the update on the phone.
2. Reproduce the failure first: download a currently-failing reel → expect the friendly
   "sign in to Instagram" message.
3. ⋮ → Accounts → Instagram → Login; complete login (incl. any 2FA) in the WebView.
4. Retry the **same** failing reel → expect success, saved to Downloads.
5. Confirm a second reel works without re-login (session reuse).
6. Confirm TikTok/YouTube still download (no regression for non-Instagram).
7. Logout → the previously-working reel fails again with the hint (state cleared).

User to provide: 1–2 of the exact reel links currently erroring, for step 2/4.

## Risks & open questions

- **Real-account lock/ban risk** — accepted by the user; mitigated as above.
- **Login-success detection** — relying on `sessionid` presence after `onPageFinished`
  is robust, but 2FA/checkpoint flows may need the user to finish before the cookie
  appears; the check runs on every page load so it catches the post-2FA state. A manual
  "I'm done" affordance can be added if needed.
- **getCookie sufficiency on exotic WebView builds** — if a device ever returns
  incomplete cookies despite the `https://` URL, the documented fallback is ytdlnis's
  approach (read WebView's SQLite cookie DB directly). Out of scope unless observed.
- **Instagram changing the wall again** — possible; this design follows yt-dlp's
  supported auth path, so updates would come via the existing "Update extractor" action.

## References

- yt-dlp Instagram login wall: github.com/yt-dlp/yt-dlp/issues/14241, /issues/11151
- yt-dlp cookies format & session-cookie handling: yt-dlp `yt_dlp/cookies.py`;
  Instagram extractor `sessionid`/`csrftoken` use: `yt_dlp/extractor/instagram.py`
- Android `CookieManager` (getCookie = Cookie-header format; HttpOnly inclusion via
  Chromium `cookie_manager.cc` `MakeAllInclusive`): developer.android.com/reference/android/webkit/CookieManager
- Reference implementation (WebView login → cookies.txt, UA re-send):
  ytdlnis.org/docs/guides/cookies; github.com/deniscerri/ytdlnis
