package io.github.zachisparanoid.grabreel

enum class DownloadStatus { QUEUED, RUNNING, COMPLETED, FAILED }

data class Download(
    val id: String,
    val url: String,
    val title: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Int = 0,
    val etaSeconds: Long = -1,
    val filePath: String? = null,
    val error: String? = null,
)
