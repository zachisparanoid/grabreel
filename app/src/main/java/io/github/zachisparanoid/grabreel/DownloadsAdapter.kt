package io.github.zachisparanoid.grabreel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DownloadsAdapter(
    private val onOpen: (Download) -> Unit,
    private val onRetry: (Download) -> Unit,
    private val onRemove: (Download) -> Unit,
) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    private var items: List<Download> = emptyList()

    fun submit(list: List<Download>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val status: TextView = v.findViewById(R.id.status)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val action: Button = v.findViewById(R.id.action)
        val remove: Button = v.findViewById(R.id.remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val d = items[position]
        h.title.text = d.title ?: d.url
        h.status.text = when (d.status) {
            DownloadStatus.QUEUED -> "Queued"
            DownloadStatus.RUNNING ->
                if (d.etaSeconds >= 0) "Downloading… ${d.progress}% (ETA ${d.etaSeconds}s)"
                else "Downloading… ${d.progress}%"
            DownloadStatus.COMPLETED -> "Saved to Downloads"
            DownloadStatus.FAILED -> d.error ?: "Failed"
        }

        val active = d.status == DownloadStatus.RUNNING || d.status == DownloadStatus.QUEUED
        h.progress.visibility = if (active) View.VISIBLE else View.GONE
        h.progress.isIndeterminate = d.status == DownloadStatus.QUEUED
        if (d.status == DownloadStatus.RUNNING) h.progress.progress = d.progress

        when (d.status) {
            DownloadStatus.COMPLETED -> {
                h.action.text = "Open"
                h.action.visibility = View.VISIBLE
                h.action.setOnClickListener { onOpen(d) }
            }
            DownloadStatus.FAILED -> {
                h.action.text = "Retry"
                h.action.visibility = View.VISIBLE
                h.action.setOnClickListener { onRetry(d) }
            }
            else -> {
                h.action.visibility = View.GONE
                h.action.setOnClickListener(null)
            }
        }
        h.remove.setOnClickListener { onRemove(d) }
    }
}
