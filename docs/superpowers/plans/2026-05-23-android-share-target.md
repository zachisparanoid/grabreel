# Share to GrabReel (Android share target) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make GrabReel appear in Android's share sheet so sharing a video link from any app queues a "best" download on the server and opens the Downloads list.

**Architecture:** Add an `ACTION_SEND`/`text/plain` intent-filter to `MainActivity`. On a share, extract the URL from the shared text, `POST /api/jobs` natively via `HttpURLConnection` on a background thread, then load the SPA's Downloads page (queue-before-load so live polling starts). No server/SPA change.

**Tech Stack:** Kotlin, Android (minSdk 24 / target 34), JDK `HttpURLConnection`, `org.json` (bundled), JUnit 4 for the pure URL-extraction unit.

---

### Task 1: `extractUrl` pure helper (TDD)

**Files:**
- Create: `app/src/main/java/io/github/zachisparanoid/grabreel/ShareUrl.kt`
- Test: `app/src/test/java/io/github/zachisparanoid/grabreel/ShareUrlTest.kt`
- Modify: `app/build.gradle.kts` (add JUnit test dependency)

- [ ] **Step 1: Add the JUnit test dependency**

In `app/build.gradle.kts`, change the `dependencies { }` block to:

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/io/github/zachisparanoid/grabreel/ShareUrlTest.kt`:

```kotlin
package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareUrlTest {
    @Test fun bareUrl() {
        assertEquals(
            "https://www.instagram.com/reel/abc/",
            extractUrl("https://www.instagram.com/reel/abc/"),
        )
    }

    @Test fun urlInCaption() {
        assertEquals(
            "https://vt.tiktok.com/ZSxyz/",
            extractUrl("Check out this one 😍 https://vt.tiktok.com/ZSxyz/"),
        )
    }

    @Test fun stripsTrailingPunctuation() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            extractUrl("watch this: https://youtu.be/dQw4w9WgXcQ."),
        )
    }

    @Test fun firstUrlWins() {
        assertEquals(
            "https://a.com/1",
            extractUrl("https://a.com/1 and https://b.com/2"),
        )
    }

    @Test fun keepsQueryString() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            extractUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
    }

    @Test fun noUrlReturnsNull() {
        assertNull(extractUrl("just some text, no link"))
    }

    @Test fun nullReturnsNull() {
        assertNull(extractUrl(null))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.zachisparanoid.grabreel.ShareUrlTest"`
Expected: FAIL — unresolved reference `extractUrl`.

- [ ] **Step 4: Write the minimal implementation**

Create `app/src/main/java/io/github/zachisparanoid/grabreel/ShareUrl.kt`:

```kotlin
package io.github.zachisparanoid.grabreel

/** First http/https URL embedded anywhere in a string. */
private val URL_REGEX = Regex("""https?://\S+""")

/** Trailing chars that are almost always punctuation around a URL, not part of it. */
private const val TRAILING_JUNK = ".,;:!?\"')]}"

/**
 * Pulls the first http(s) URL out of arbitrary shared text. Apps commonly share
 * "caption text… https://link" rather than a bare URL, so we find the URL inside
 * the string and strip trailing punctuation. Returns null when none is found.
 */
fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val raw = URL_REGEX.find(text)?.value ?: return null
    return raw.trimEnd { it in TRAILING_JUNK }.ifBlank { null }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.zachisparanoid.grabreel.ShareUrlTest"`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/io/github/zachisparanoid/grabreel/ShareUrl.kt app/src/test/java/io/github/zachisparanoid/grabreel/ShareUrlTest.kt
git commit -m "feat: extractUrl helper for parsing shared text"
```

---

### Task 2: Toast strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add three strings**

Add inside `<resources>` in `app/src/main/res/values/strings.xml`:

```xml
    <string name="share_queued">Added to GrabReel</string>
    <string name="share_no_link">No link found in the shared text.</string>
    <string name="share_failed">Couldn\'t reach GrabReel to start the download.</string>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat: strings for share-to-GrabReel toasts"
```

---

### Task 3: Manifest — register the share target

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add launchMode and the SEND intent-filter**

In `app/src/main/AndroidManifest.xml`, replace the `<activity>` element with:

```xml
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
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: declare GrabReel as a text/plain share target"
```

---

### Task 4: MainActivity — handle the share intent

**Files:**
- Modify: `app/src/main/java/io/github/zachisparanoid/grabreel/MainActivity.kt`

- [ ] **Step 1: Add imports**

Add to the import block:

```kotlin
import android.content.Intent
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
```

- [ ] **Step 2: Route the launch intent through `handleIntent`**

In `onCreate`, replace the final `loadHome()` call with:

```kotlin
        handleIntent(intent)
```

- [ ] **Step 3: Add `onNewIntent` + share handling**

Add these methods to `MainActivity` (e.g., just after `onCreate`):

```kotlin
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Decide what to load based on how we were launched. */
    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val url = extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            if (url != null) {
                queueShare(url)
            } else {
                toast(getString(R.string.share_no_link))
                loadHome()
            }
        } else {
            loadHome()
        }
    }

    private sealed class JobResult {
        object Success : JobResult()
        data class Rejected(val message: String) : JobResult()
        object Failed : JobResult()
    }

    /**
     * Queue a "best" download on the server for [url], then open the Downloads
     * list. We POST *before* loading the page so the SPA's initial job fetch
     * already includes the new active job and its progress polling starts.
     */
    private fun queueShare(url: String) {
        webView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        progress.visibility = View.VISIBLE
        val endpoint = baseUrl() + "/api/jobs"
        Thread {
            val result = postJob(endpoint, url)
            runOnUiThread {
                when (result) {
                    is JobResult.Success -> toast(getString(R.string.share_queued))
                    is JobResult.Rejected -> toast(result.message)
                    is JobResult.Failed -> toast(getString(R.string.share_failed))
                }
                loadHome()
            }
        }.start()
    }

    private fun postJob(endpoint: String, url: String): JobResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            val payload = JSONObject().put("url", url).put("format", "best").toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                JobResult.Success
            } else {
                val body = (conn.errorStream ?: conn.inputStream)
                    ?.bufferedReader()?.use { it.readText() }
                JobResult.Rejected(
                    parseServerMessage(body) ?: getString(R.string.share_failed),
                )
            }
        } catch (e: Exception) {
            JobResult.Failed
        } finally {
            conn?.disconnect()
        }
    }

    /** FastAPI raises HTTPException(status, {error, message}); surface message. */
    private fun parseServerMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            when (val detail = JSONObject(body).opt("detail")) {
                is JSONObject -> detail.optString("message").ifBlank { null }
                is String -> detail.ifBlank { null }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/zachisparanoid/grabreel/MainActivity.kt
git commit -m "feat: handle ACTION_SEND shares by queuing a job and opening Downloads"
```

---

### Task 5: Build, static checks, and manifest verification

- [ ] **Step 1: Unit tests + lint + assemble**

Run: `./gradlew :app:testDebugUnitTest lintDebug assembleDebug`
Expected: BUILD SUCCESSFUL; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Confirm the SEND filter is in the packaged manifest**

Run:
```bash
AAPT="$($HOME/Android/Sdk/build-tools/* -maxdepth 0 -type d 2>/dev/null | sort | tail -1)/aapt"
"$AAPT" dump xmltree app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | grep -i "android.intent.action.SEND"
```
Expected: a line referencing `android.intent.action.SEND`.

- [ ] **Step 3 (optional, needs a connected device): simulate a share**

```bash
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://www.instagram.com/reel/XXXX/" \
  io.github.zachisparanoid.grabreel/.MainActivity
```
Expected: GrabReel opens; a toast appears; the job shows in Downloads (server reachable).

---

### Task 6: Push to the phone

- [ ] **Step 1: Install over ADB-on-TCP**

Run: `./push.sh 192.0.2.10:5555`
Expected: `adb connect` succeeds, `install -r -d` reports `Success`. If `adb connect`
fails, the host is not on the Tailscale tailnet — run `tailscale up` (interactive)
and retry; this is the one step that may require the user.

- [ ] **Step 2: Real-world check**

On the phone: open Instagram/YouTube → Share → GrabReel → confirm the download
queues and the Downloads list shows progress.
