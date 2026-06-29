# Instagram Cookie Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore Instagram reel downloads by letting the user log into Instagram inside GrabReel (a WebView), capturing the session cookies + User-Agent, and feeding them to yt-dlp via `--cookies` / `--add-header`.

**Architecture:** Four pure, unit-tested units (platform detection, Netscape cookies.txt serializer, login-wall detector, on-disk cookie store) plus thin Android glue (a WebView `LoginActivity`, an `AccountsActivity`, and small edits to `Downloader`/`DownloadService`/`MainActivity`/manifest). Cookies live in app-private `noBackupFilesDir`. Plumbing is platform-general; only Instagram is wired up.

**Tech Stack:** Kotlin, Android (minSdk 24 / target 34), `io.github.junkfood02.youtubedl-android` 0.18.1, JUnit 4.13.2. Built-in `android.webkit.WebView` / `CookieManager` (no new third-party dependency).

**Spec:** `docs/superpowers/specs/2026-06-28-instagram-cookie-auth-design.md`

## Global Constraints

- Package: `io.github.zachisparanoid.grabreel` (every file).
- No new third-party/Gradle dependencies; use the platform `WebView`/`CookieManager`.
- Pure logic (platform detection, serializer, login-wall detector, cookie store) MUST avoid Android-framework imports so it runs under JUnit on the JVM. Use `java.net.URI`, NOT `android.net.Uri`.
- Cookie/UA files MUST live under `context.noBackupFilesDir/cookies/` (because `android:allowBackup="true"`).
- Capturing cookies MUST call `CookieManager.getCookie("https://www.instagram.com/")` with the full `https://` URL (a bare domain silently drops the HttpOnly `sessionid`).
- Netscape cookies.txt: first line exactly `# Netscape HTTP Cookie File`; one cookie per line with 7 **TAB**-separated columns in order `domain`, `include_subdomains`, `path`, `secure`, `expiry`, `name`, `value`; `LF` (`\n`) newlines; expiry `0` is allowed; do NOT use the `#HttpOnly_` prefix.
- Re-send the captured WebView User-Agent to yt-dlp via `--add-header "User-Agent:<ua>"`.
- New activities registered with `android:exported="false"`.
- Android-framework code (Activities, the yt-dlp call) is verified by `assembleDebug` + the on-device test in Task 9; only pure logic gets JUnit tests.
- Unit-test run command: `./gradlew testDebugUnitTest --tests "<FQCN>"`. Compile check: `./gradlew assembleDebug`.

---

## File Structure

**New (main):**
- `app/src/main/java/io/github/zachisparanoid/grabreel/NetscapeCookies.kt` — pure cookies.txt serializer.
- `app/src/main/java/io/github/zachisparanoid/grabreel/LoginWall.kt` — pure auth-wall message detector.
- `app/src/main/java/io/github/zachisparanoid/grabreel/CookieStore.kt` — on-disk per-platform cookies + UA.
- `app/src/main/java/io/github/zachisparanoid/grabreel/LoginActivity.kt` — WebView login + capture.
- `app/src/main/java/io/github/zachisparanoid/grabreel/AccountsActivity.kt` — accounts screen.
- `app/src/main/res/layout/activity_accounts.xml` — accounts screen layout.

**New (test):**
- `app/src/test/java/io/github/zachisparanoid/grabreel/NetscapeCookiesTest.kt`
- `app/src/test/java/io/github/zachisparanoid/grabreel/LoginWallTest.kt`
- `app/src/test/java/io/github/zachisparanoid/grabreel/CookieStoreTest.kt`

**Modified:**
- `ShareUrl.kt` (+ `ShareUrlTest.kt`) — add `Platform` enum + `platformFor`.
- `Downloader.kt` — add `cookiesFile`/`userAgent` params.
- `DownloadService.kt` — cookie lookup, auth-wall message swap, burst throttle.
- `MainActivity.kt` — Accounts overflow menu item.
- `AndroidManifest.xml` — register the two activities.
- `app/src/main/res/values/strings.xml` — new strings.

---

### Task 1: Platform detection

**Files:**
- Modify: `app/src/main/java/io/github/zachisparanoid/grabreel/ShareUrl.kt`
- Test: `app/src/test/java/io/github/zachisparanoid/grabreel/ShareUrlTest.kt`

**Interfaces:**
- Produces: `enum class Platform { INSTAGRAM, TIKTOK, X, YOUTUBE, FACEBOOK, OTHER }` and `fun platformFor(url: String): Platform`.

- [ ] **Step 1: Write the failing tests** (append these methods inside the existing `ShareUrlTest` class in `ShareUrlTest.kt`, before the closing brace)

```kotlin
    @Test fun platformInstagram() {
        assertEquals(Platform.INSTAGRAM, platformFor("https://www.instagram.com/reel/abc/"))
    }

    @Test fun platformInstagramApexHost() {
        assertEquals(Platform.INSTAGRAM, platformFor("https://instagram.com/p/abc/"))
    }

    @Test fun platformTiktokSubdomain() {
        assertEquals(Platform.TIKTOK, platformFor("https://vt.tiktok.com/ZSxyz/"))
    }

    @Test fun platformXAndTwitter() {
        assertEquals(Platform.X, platformFor("https://x.com/u/status/1"))
        assertEquals(Platform.X, platformFor("https://twitter.com/u/status/1"))
    }

    @Test fun platformYoutubeShort() {
        assertEquals(Platform.YOUTUBE, platformFor("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test fun platformFacebookWatch() {
        assertEquals(Platform.FACEBOOK, platformFor("https://fb.watch/abc/"))
    }

    @Test fun platformUnknownIsOther() {
        assertEquals(Platform.OTHER, platformFor("https://example.com/x"))
    }

    @Test fun platformGarbageIsOther() {
        assertEquals(Platform.OTHER, platformFor("not a url"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "io.github.zachisparanoid.grabreel.ShareUrlTest"`
Expected: FAIL — `Platform` / `platformFor` unresolved (compile error).

- [ ] **Step 3: Implement `Platform` + `platformFor`** (append to `ShareUrl.kt`, after `extractUrl`)

```kotlin
/** Social platform a URL belongs to, used to pick the right login cookies. */
enum class Platform { INSTAGRAM, TIKTOK, X, YOUTUBE, FACEBOOK, OTHER }

/**
 * Classifies a URL by host. Uses java.net.URI (not android.net.Uri) so it stays
 * unit-testable on the JVM. Unknown or unparseable URLs are [Platform.OTHER].
 */
fun platformFor(url: String): Platform {
    val host = try {
        java.net.URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    } ?: return Platform.OTHER

    fun matches(vararg domains: String) =
        domains.any { host == it || host.endsWith(".$it") }

    return when {
        matches("instagram.com") -> Platform.INSTAGRAM
        matches("tiktok.com") -> Platform.TIKTOK
        matches("x.com", "twitter.com") -> Platform.X
        host == "youtu.be" || matches("youtube.com") -> Platform.YOUTUBE
        host == "fb.watch" || matches("facebook.com") -> Platform.FACEBOOK
        else -> Platform.OTHER
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.zachisparanoid.grabreel.ShareUrlTest"`
Expected: PASS (all original + new tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/ShareUrl.kt \
        app/src/test/java/io/github/zachisparanoid/grabreel/ShareUrlTest.kt
git commit -m "feat: classify a share URL by social platform"
```

---

### Task 2: Netscape cookies.txt serializer

**Files:**
- Create: `app/src/main/java/io/github/zachisparanoid/grabreel/NetscapeCookies.kt`
- Test: `app/src/test/java/io/github/zachisparanoid/grabreel/NetscapeCookiesTest.kt`

**Interfaces:**
- Produces: `object NetscapeCookies { fun serialize(rawCookieHeader: String, domain: String): String }`. Input `rawCookieHeader` is CookieManager's `"name=value; name2=value2"`; `domain` is e.g. `".instagram.com"`.

- [ ] **Step 1: Write the failing test** (create `NetscapeCookiesTest.kt`)

```kotlin
package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetscapeCookiesTest {
    @Test fun startsWithNetscapeHeader() {
        val out = NetscapeCookies.serialize("sessionid=abc", ".instagram.com")
        assertTrue(out.startsWith("# Netscape HTTP Cookie File\n"))
    }

    @Test fun singleCookieHasSevenTabColumns() {
        val out = NetscapeCookies.serialize("sessionid=abc123", ".instagram.com")
        val line = out.lines().first { it.startsWith(".instagram.com") }
        assertEquals(".instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tabc123", line)
    }

    @Test fun multipleCookiesKeepOrder() {
        val out = NetscapeCookies.serialize("sessionid=a; csrftoken=b", ".instagram.com")
        val lines = out.lines().filter { it.startsWith(".instagram.com") }
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("\tsessionid\ta"))
        assertTrue(lines[1].endsWith("\tcsrftoken\tb"))
    }

    @Test fun emptyInputIsHeaderOnly() {
        assertEquals("# Netscape HTTP Cookie File\n", NetscapeCookies.serialize("", ".instagram.com"))
    }

    @Test fun valueContainingEqualsIsPreserved() {
        val out = NetscapeCookies.serialize("k=a=b=c", ".instagram.com")
        val line = out.lines().first { it.startsWith(".instagram.com") }
        assertTrue(line.endsWith("\tk\ta=b=c"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "io.github.zachisparanoid.grabreel.NetscapeCookiesTest"`
Expected: FAIL — `NetscapeCookies` unresolved.

- [ ] **Step 3: Implement the serializer** (create `NetscapeCookies.kt`)

```kotlin
package io.github.zachisparanoid.grabreel

/**
 * Builds a yt-dlp-compatible Netscape cookies.txt from CookieManager's
 * "name=value; name2=value2" header string. CookieManager returns no cookie
 * attributes, so we synthesize sane ones: every cookie is scoped to [domain]
 * (e.g. ".instagram.com"), include-subdomains TRUE, path "/", secure TRUE,
 * expiry 0 (yt-dlp treats 0 as a kept session cookie).
 */
object NetscapeCookies {
    private const val HEADER = "# Netscape HTTP Cookie File"

    fun serialize(rawCookieHeader: String, domain: String): String {
        val sb = StringBuilder(HEADER).append('\n')
        rawCookieHeader.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { pair ->
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=').trim()
                if (name.isNotEmpty()) {
                    sb.append(domain).append('\t')
                        .append("TRUE").append('\t')
                        .append('/').append('\t')
                        .append("TRUE").append('\t')
                        .append('0').append('\t')
                        .append(name).append('\t')
                        .append(value).append('\n')
                }
            }
        return sb.toString()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "io.github.zachisparanoid.grabreel.NetscapeCookiesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/NetscapeCookies.kt \
        app/src/test/java/io/github/zachisparanoid/grabreel/NetscapeCookiesTest.kt
git commit -m "feat: serialize cookies to yt-dlp Netscape format"
```

---

### Task 3: Login-wall detector

**Files:**
- Create: `app/src/main/java/io/github/zachisparanoid/grabreel/LoginWall.kt`
- Test: `app/src/test/java/io/github/zachisparanoid/grabreel/LoginWallTest.kt`

**Interfaces:**
- Produces: `fun looksLikeInstagramLoginWall(message: String?): Boolean`.

- [ ] **Step 1: Write the failing test** (create `LoginWallTest.kt`)

```kotlin
package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginWallTest {
    @Test fun emptyMediaResponseIsWall() {
        assertTrue(looksLikeInstagramLoginWall(
            "Instagram sent an empty media response. Check if this post is accessible..."))
    }

    @Test fun loginRequiredIsWall() {
        assertTrue(looksLikeInstagramLoginWall(
            "Requested content is not available, rate-limit reached or login required"))
    }

    @Test fun checkpointIsWall() {
        assertTrue(looksLikeInstagramLoginWall("checkpoint_required"))
    }

    @Test fun unrelatedErrorIsNotWall() {
        assertFalse(looksLikeInstagramLoginWall("Unsupported URL: https://example.com"))
    }

    @Test fun nullOrBlankIsNotWall() {
        assertFalse(looksLikeInstagramLoginWall(null))
        assertFalse(looksLikeInstagramLoginWall("   "))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "io.github.zachisparanoid.grabreel.LoginWallTest"`
Expected: FAIL — `looksLikeInstagramLoginWall` unresolved.

- [ ] **Step 3: Implement the detector** (create `LoginWall.kt`)

```kotlin
package io.github.zachisparanoid.grabreel

/** Phrases in a yt-dlp failure that mean Instagram wants a logged-in session. */
private val LOGIN_WALL_MARKERS = listOf(
    "login required",
    "empty media response",
    "requested content is not available",
    "checkpoint",
    "challenge_required",
    "login page",
)

/** True when an Instagram download failure looks like Instagram's auth wall. */
fun looksLikeInstagramLoginWall(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val m = message.lowercase()
    return LOGIN_WALL_MARKERS.any { it in m }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "io.github.zachisparanoid.grabreel.LoginWallTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/LoginWall.kt \
        app/src/test/java/io/github/zachisparanoid/grabreel/LoginWallTest.kt
git commit -m "feat: detect Instagram login-wall failures"
```

---

### Task 4: On-disk cookie store

**Files:**
- Create: `app/src/main/java/io/github/zachisparanoid/grabreel/CookieStore.kt`
- Test: `app/src/test/java/io/github/zachisparanoid/grabreel/CookieStoreTest.kt`

**Interfaces:**
- Consumes: `Platform` (Task 1), `NetscapeCookies.serialize` (Task 2).
- Produces:
  - `class CookieStore(dir: File)` with `data class Auth(val cookiesFile: File, val userAgent: String?)`
  - `fun save(platform: Platform, rawCookieHeader: String, userAgent: String?)`
  - `fun authFor(platform: Platform): Auth?`
  - `fun isLoggedIn(platform: Platform): Boolean`
  - `fun clear(platform: Platform)`
  - `companion object { fun forContext(context: Context): CookieStore }`

- [ ] **Step 1: Write the failing test** (create `CookieStoreTest.kt`; uses a temp dir so no Android Context is needed)

```kotlin
package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CookieStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = CookieStore(File(tmp.root, "cookies"))

    @Test fun notLoggedInInitially() {
        assertFalse(store().isLoggedIn(Platform.INSTAGRAM))
        assertNull(store().authFor(Platform.INSTAGRAM))
    }

    @Test fun saveThenAuthReturnsCookiesAndUa() {
        val s = store()
        s.save(Platform.INSTAGRAM, "sessionid=abc; csrftoken=xyz", "TestUA/1.0")
        assertTrue(s.isLoggedIn(Platform.INSTAGRAM))
        val auth = s.authFor(Platform.INSTAGRAM)!!
        assertEquals("TestUA/1.0", auth.userAgent)
        val text = auth.cookiesFile.readText()
        assertTrue(text.startsWith("# Netscape HTTP Cookie File"))
        assertTrue(text.contains("\tsessionid\tabc"))
    }

    @Test fun saveWithNullUaHasNoUa() {
        val s = store()
        s.save(Platform.INSTAGRAM, "sessionid=abc", null)
        assertNull(s.authFor(Platform.INSTAGRAM)!!.userAgent)
    }

    @Test fun clearRemovesSession() {
        val s = store()
        s.save(Platform.INSTAGRAM, "sessionid=abc", "UA")
        s.clear(Platform.INSTAGRAM)
        assertFalse(s.isLoggedIn(Platform.INSTAGRAM))
        assertNull(s.authFor(Platform.INSTAGRAM))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "io.github.zachisparanoid.grabreel.CookieStoreTest"`
Expected: FAIL — `CookieStore` unresolved.

- [ ] **Step 3: Implement the store** (create `CookieStore.kt`)

```kotlin
package io.github.zachisparanoid.grabreel

import android.content.Context
import java.io.File

/**
 * Per-platform Netscape cookies.txt + captured User-Agent, stored privately.
 * Constructed with a plain [dir] so the file logic is unit-testable; use
 * [forContext] in app code to anchor it under noBackupFilesDir (keeps the
 * session out of cloud/adb backups even though allowBackup is true).
 */
class CookieStore(private val dir: File) {

    data class Auth(val cookiesFile: File, val userAgent: String?)

    fun save(platform: Platform, rawCookieHeader: String, userAgent: String?) {
        dir.mkdirs()
        cookiesFile(platform).writeText(NetscapeCookies.serialize(rawCookieHeader, domainFor(platform)))
        val ua = uaFile(platform)
        if (userAgent.isNullOrBlank()) ua.delete() else ua.writeText(userAgent)
    }

    fun authFor(platform: Platform): Auth? {
        val cookies = cookiesFile(platform)
        if (!cookies.exists() || cookies.length() == 0L) return null
        val ua = uaFile(platform).let { if (it.exists()) it.readText() else null }
        return Auth(cookies, ua)
    }

    fun isLoggedIn(platform: Platform): Boolean {
        val c = cookiesFile(platform)
        return c.exists() && c.length() > 0L
    }

    fun clear(platform: Platform) {
        cookiesFile(platform).delete()
        uaFile(platform).delete()
    }

    private fun cookiesFile(platform: Platform) = File(dir, "${platform.name.lowercase()}.txt")
    private fun uaFile(platform: Platform) = File(dir, "${platform.name.lowercase()}.ua")

    private fun domainFor(platform: Platform): String = when (platform) {
        Platform.INSTAGRAM -> ".instagram.com"
        Platform.TIKTOK -> ".tiktok.com"
        Platform.X -> ".x.com"
        Platform.YOUTUBE -> ".youtube.com"
        Platform.FACEBOOK -> ".facebook.com"
        Platform.OTHER -> ""
    }

    companion object {
        fun forContext(context: Context): CookieStore =
            CookieStore(File(context.noBackupFilesDir, "cookies"))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "io.github.zachisparanoid.grabreel.CookieStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/CookieStore.kt \
        app/src/test/java/io/github/zachisparanoid/grabreel/CookieStoreTest.kt
git commit -m "feat: persist per-platform cookies and user-agent on disk"
```

---

### Task 5: Thread cookies + User-Agent into the downloader

**Files:**
- Modify: `app/src/main/java/io/github/zachisparanoid/grabreel/Downloader.kt`

**Interfaces:**
- Produces: new signature `Downloader.download(url, parentDir, processId, cookiesFile: File? = null, userAgent: String? = null, onProgress)`. New params default to null and precede the trailing `onProgress` lambda, so the existing `DownloadService` call still compiles unchanged until Task 6.

This task is verified by compilation + the existing `DownloaderTest` (no new unit test — building the yt-dlp request needs the on-device engine; behavior is verified on-device in Task 9).

- [ ] **Step 1: Add the parameters** (in `Downloader.kt`, change the `download` signature)

Replace:
```kotlin
    fun download(
        url: String,
        parentDir: File,
        processId: String,
        onProgress: (Int, Long) -> Unit,
    ): Result {
```
with:
```kotlin
    fun download(
        url: String,
        parentDir: File,
        processId: String,
        cookiesFile: File? = null,
        userAgent: String? = null,
        onProgress: (Int, Long) -> Unit,
    ): Result {
```

- [ ] **Step 2: Add the yt-dlp options** (in the same `YoutubeDLRequest(url).apply { ... }` block, immediately after the existing `addOption("-o", ...)` line)

```kotlin
            if (cookiesFile != null) addOption("--cookies", cookiesFile.absolutePath)
            if (userAgent != null) addOption("--add-header", "User-Agent:$userAgent")
```

- [ ] **Step 3: Verify it still builds and existing tests pass**

Run: `./gradlew testDebugUnitTest --tests "io.github.zachisparanoid.grabreel.DownloaderTest"`
Expected: PASS.
Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (the unchanged `DownloadService` call uses the new defaults).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/Downloader.kt
git commit -m "feat: accept optional cookies file and user-agent in Downloader"
```

---

### Task 6: Wire cookie lookup, auth-wall message, and burst throttle into the service

**Files:**
- Modify: `app/src/main/java/io/github/zachisparanoid/grabreel/DownloadService.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `platformFor` (Task 1), `Platform` (Task 1), `CookieStore.forContext`/`authFor` (Task 4), `looksLikeInstagramLoginWall` (Task 3), `Downloader.download(...)` new params (Task 5).

Verified by compilation; full behavior is exercised in Task 9.

- [ ] **Step 1: Add the re-login string** (in `strings.xml`, before `</resources>`)

```xml
    <string name="ig_login_required">Instagram needs you to sign in. Open ⋮ → Accounts → Instagram, then retry.</string>
```

- [ ] **Step 2: Add the throttle import + field** (in `DownloadService.kt`)

Add to the imports near `java.util.concurrent.atomic.AtomicInteger`:
```kotlin
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
```
Add the field next to the existing `private val active = AtomicInteger(0)`:
```kotlin
    private val lastInstagramAt = AtomicLong(0)
```

- [ ] **Step 3: Replace `runJob` to look up cookies, throttle, and map auth failures**

Replace the entire `private fun runJob(id: String, url: String) { ... }` with:
```kotlin
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
```

- [ ] **Step 4: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/DownloadService.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat: use stored cookies for downloads and explain Instagram login wall"
```

---

### Task 7: WebView login activity (capture cookies + UA)

**Files:**
- Create: `app/src/main/java/io/github/zachisparanoid/grabreel/LoginActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `Platform` (Task 1), `CookieStore.forContext`/`save` (Task 4).
- Produces: `LoginActivity` with `companion object { const val EXTRA_PLATFORM = "platform" }`; started with a `Platform.name` string extra, returns `RESULT_OK` once the session is captured.

Verified by compilation; the actual login + capture is verified on-device in Task 9.

- [ ] **Step 1: Add the title string** (in `strings.xml`, before `</resources>`)

```xml
    <string name="login_title">Sign in</string>
```

- [ ] **Step 2: Create `LoginActivity.kt`**

```kotlin
package io.github.zachisparanoid.grabreel

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * In-app login for a platform. Loads the platform login page in a WebView; once
 * the platform's auth cookie appears, captures ALL cookies (via a full https URL
 * so HttpOnly cookies like Instagram's sessionid are included) plus the WebView
 * User-Agent into CookieStore, then finishes with RESULT_OK.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var platform: Platform

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = runCatching {
            Platform.valueOf(intent.getStringExtra(EXTRA_PLATFORM) ?: Platform.INSTAGRAM.name)
        }.getOrDefault(Platform.INSTAGRAM)
        title = getString(R.string.login_title)

        val web = WebView(this)
        setContentView(web)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                captureIfLoggedIn(view)
            }
        }
        web.loadUrl(loginUrl(platform))
    }

    private fun captureIfLoggedIn(view: WebView) {
        val cm = CookieManager.getInstance()
        val cookies = cm.getCookie(cookieUrl(platform)) ?: return
        if (!cookies.contains(authCookieName(platform))) return
        cm.flush()
        CookieStore.forContext(this).save(platform, cookies, view.settings.userAgentString)
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_PLATFORM = "platform"

        // Instagram is the only wired-up platform today; the when() is the extension seam.
        private fun loginUrl(p: Platform): String = when (p) {
            else -> "https://www.instagram.com/accounts/login/"
        }

        private fun cookieUrl(p: Platform): String = when (p) {
            else -> "https://www.instagram.com/" // full https URL → includes HttpOnly sessionid
        }

        private fun authCookieName(p: Platform): String = when (p) {
            else -> "sessionid"
        }
    }
}
```

- [ ] **Step 3: Register the activity** (in `AndroidManifest.xml`, inside `<application>`, after the `<service .../>` element)

```xml
        <activity
            android:name=".LoginActivity"
            android:exported="false"
            android:label="@string/login_title" />
```

- [ ] **Step 4: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/LoginActivity.kt \
        app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
git commit -m "feat: in-app WebView login captures session cookies and user-agent"
```

---

### Task 8: Accounts screen + menu entry

**Files:**
- Create: `app/src/main/java/io/github/zachisparanoid/grabreel/AccountsActivity.kt`
- Create: `app/src/main/res/layout/activity_accounts.xml`
- Modify: `app/src/main/java/io/github/zachisparanoid/grabreel/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `Platform` (Task 1), `CookieStore.forContext`/`isLoggedIn`/`clear` (Task 4), `LoginActivity.EXTRA_PLATFORM` (Task 7).

Verified by compilation; the full login→download flow is verified on-device in Task 9.

- [ ] **Step 1: Add strings** (in `strings.xml`, before `</resources>`)

```xml
    <string name="menu_accounts">Accounts</string>
    <string name="accounts_title">Accounts</string>
    <string name="ig_signed_in">Instagram — signed in</string>
    <string name="ig_signed_out">Instagram — not signed in</string>
    <string name="action_login">Log in</string>
    <string name="action_logout">Log out</string>
```

- [ ] **Step 2: Create the layout** (`app/src/main/res/layout/activity_accounts.xml`)

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/igStatus"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="16sp"
        android:paddingBottom="8dp" />

    <Button
        android:id="@+id/igButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content" />
</LinearLayout>
```

- [ ] **Step 3: Create `AccountsActivity.kt`**

```kotlin
package io.github.zachisparanoid.grabreel

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** Lists the Instagram login state and toggles login/logout. */
class AccountsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var button: Button

    private val login = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)
        title = getString(R.string.accounts_title)
        status = findViewById(R.id.igStatus)
        button = findViewById(R.id.igButton)
        button.setOnClickListener { onInstagramButton() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun onInstagramButton() {
        val store = CookieStore.forContext(this)
        if (store.isLoggedIn(Platform.INSTAGRAM)) {
            store.clear(Platform.INSTAGRAM)
            render()
        } else {
            login.launch(
                Intent(this, LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_PLATFORM, Platform.INSTAGRAM.name),
            )
        }
    }

    private fun render() {
        val loggedIn = CookieStore.forContext(this).isLoggedIn(Platform.INSTAGRAM)
        status.setText(if (loggedIn) R.string.ig_signed_in else R.string.ig_signed_out)
        button.setText(if (loggedIn) R.string.action_logout else R.string.action_login)
    }
}
```

- [ ] **Step 4: Register the activity** (in `AndroidManifest.xml`, after the `LoginActivity` element)

```xml
        <activity
            android:name=".AccountsActivity"
            android:exported="false"
            android:label="@string/accounts_title" />
```

- [ ] **Step 5: Add the overflow menu item** (in `MainActivity.kt`)

In the `companion object`, add the new id:
```kotlin
        private const val MENU_ACCOUNTS = 3
```
In `onCreateOptionsMenu`, add after the `MENU_CLEAR` line:
```kotlin
        menu.add(0, MENU_ACCOUNTS, 2, R.string.menu_accounts)
```
In `onOptionsItemSelected`, add a branch before the `else`:
```kotlin
        MENU_ACCOUNTS -> { startActivity(Intent(this, AccountsActivity::class.java)); true }
```

- [ ] **Step 6: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/AccountsActivity.kt \
        app/src/main/res/layout/activity_accounts.xml \
        app/src/main/java/io/github/zachisparanoid/grabreel/MainActivity.kt \
        app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
git commit -m "feat: Accounts screen to sign in/out of Instagram"
```

---

### Task 9: Full build + on-device verification + docs

**Files:**
- Modify: `README.md` (note the new auth capability)
- Modify: `docs/superpowers/specs/2026-06-28-instagram-cookie-auth-design.md` (status → Implemented)

**Interfaces:** none (integration + verification task).

- [ ] **Step 1: Full unit-test + build**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (ShareUrl, NetscapeCookies, LoginWall, CookieStore, Downloader, plus existing).
Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Install on the phone**

Use the project's deploy path (e.g. `./push.sh` or `adb install -r app/build/outputs/apk/debug/app-debug.apk`). If `adb` says "Connection refused", rediscover the wireless-debug port via `adb mdns services` (the phone's port rotates).

- [ ] **Step 3: Manual end-to-end test** (record pass/fail for each)

1. With a **currently-failing** Instagram reel link, paste/share it → it fails with *"Instagram needs you to sign in…"* (not a raw yt-dlp error).
2. ⋮ → **Accounts** → row shows *"Instagram — not signed in"*; tap **Log in** → Instagram login page loads in-app.
3. Complete login (including any 2FA). The screen returns automatically; the row now shows *"Instagram — signed in"*.
4. Retry the **same** reel → it downloads and saves to Downloads.
5. Download a **second** reel → succeeds without re-login (session reuse).
6. Download a **TikTok** and a **YouTube** link → still succeed (no regression for non-Instagram).
7. ⋮ → Accounts → **Log out** → the previously-working reel fails again with the sign-in hint (state cleared).

- [ ] **Step 4: Update docs**

In `README.md`, add a short line under the feature list: that Instagram downloads require a one-time in-app sign-in via ⋮ → Accounts, and the session is reused thereafter.
In the spec, change `**Status:** Proposed (awaiting review)` to `**Status:** Implemented`.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/specs/2026-06-28-instagram-cookie-auth-design.md
git commit -m "docs: record Instagram in-app sign-in for downloads"
```

---

## Notes for the implementer

- **Login-success detection** relies on the `sessionid` cookie appearing after a page finishes loading. For 2FA/checkpoint flows the user completes extra pages first; the check runs on every `onPageFinished`, so it fires as soon as the session cookie is set. If a device ever logs in without `captureIfLoggedIn` firing, add a manual "Done" button to `LoginActivity` that calls `captureIfLoggedIn(web)` directly.
- **Do not cherry-pick cookies.** `save()` writes everything `getCookie` returns; that intentionally includes `csrftoken`, `ds_user_id`, `mid`, `ig_did` alongside `sessionid`.
- If on-device downloads still hit the wall *after* a confirmed sign-in, the first things to check are: the cookies file actually contains a `sessionid` line, and the User-Agent header is being sent (`--add-header`). These are the two session-binding requirements.
