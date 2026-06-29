package io.github.zachisparanoid.grabreel

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
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
            CookieManager.getInstance().apply { removeAllCookies(null); flush() }
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
