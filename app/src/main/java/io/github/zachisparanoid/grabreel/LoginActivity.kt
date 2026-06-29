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
    private lateinit var web: WebView
    private var captured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = runCatching {
            Platform.valueOf(intent.getStringExtra(EXTRA_PLATFORM) ?: Platform.INSTAGRAM.name)
        }.getOrDefault(Platform.INSTAGRAM)
        title = getString(R.string.login_title)

        web = WebView(this)
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
        web.loadUrl(LOGIN_URL)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun captureIfLoggedIn(view: WebView) {
        if (captured) return
        val cm = CookieManager.getInstance()
        val cookies = cm.getCookie(COOKIE_URL) ?: return
        if (!cookies.contains(AUTH_COOKIE)) return
        runCatching {
            cm.flush()
            CookieStore.forContext(this).save(platform, cookies, view.settings.userAgentString)
        }.onSuccess {
            captured = true
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    companion object {
        const val EXTRA_PLATFORM = "platform"

        private const val LOGIN_URL = "https://www.instagram.com/accounts/login/"
        private const val COOKIE_URL = "https://www.instagram.com/" // full https URL → includes HttpOnly sessionid
        private const val AUTH_COOKIE = "sessionid"
    }
}
