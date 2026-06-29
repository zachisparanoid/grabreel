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

/**
 * Single screen: paste a link or receive a share, then watch on-device downloads
 * progress. All extraction/muxing happens on the phone via the download service.
 */
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
        if (url.isBlank()) {
            toast(getString(R.string.share_no_link))
            return
        }
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
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        } catch (e: Exception) {
            toast(getString(R.string.no_player))
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_UPDATE, 0, R.string.menu_update)
        menu.add(0, MENU_CLEAR, 1, R.string.menu_clear_finished)
        menu.add(0, MENU_ACCOUNTS, 2, R.string.menu_accounts)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_UPDATE -> { updateExtractor(); true }
        MENU_CLEAR -> { DownloadStore.clearFinished(); true }
        MENU_ACCOUNTS -> { startActivity(Intent(this, AccountsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun updateExtractor() {
        toast(getString(R.string.updating_extractor))
        (application as GrabReelApp).scope.launch {
            val msg = try {
                YoutubeDL.getInstance()
                    .updateYoutubeDL(this@MainActivity, YoutubeDL.UpdateChannel.STABLE)
                getString(R.string.update_done)
            } catch (e: Exception) {
                getString(R.string.update_failed, e.message ?: "")
            }
            runOnUiThread { toast(msg) }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    companion object {
        private const val MENU_UPDATE = 1
        private const val MENU_CLEAR = 2
        private const val MENU_ACCOUNTS = 3
    }
}
