package io.github.zachisparanoid.grabreel

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStoreTest {
    private fun d(id: String, s: DownloadStatus = DownloadStatus.QUEUED) =
        Download(id = id, url = "http://x/$id", status = s)

    @Test fun upsertPrependsNew() {
        val r = DownloadReducer.upsert(listOf(d("a")), d("b"))
        assertEquals(listOf("b", "a"), r.map { it.id })
    }

    @Test fun upsertReplacesInPlace() {
        val r = DownloadReducer.upsert(listOf(d("a"), d("b")), d("a", DownloadStatus.RUNNING))
        assertEquals(DownloadStatus.RUNNING, r.first { it.id == "a" }.status)
        assertEquals(2, r.size)
    }

    @Test fun updateMapsMatchingId() {
        val r = DownloadReducer.update(listOf(d("a")), "a") { it.copy(progress = 50) }
        assertEquals(50, r.first().progress)
    }

    @Test fun removeFilters() {
        assertEquals(listOf("b"), DownloadReducer.remove(listOf(d("a"), d("b")), "a").map { it.id })
    }

    @Test fun clearFinishedKeepsActive() {
        val list = listOf(
            d("a", DownloadStatus.RUNNING), d("b", DownloadStatus.COMPLETED),
            d("c", DownloadStatus.QUEUED), d("e", DownloadStatus.FAILED),
        )
        assertEquals(setOf("a", "c"), DownloadReducer.clearFinished(list).map { it.id }.toSet())
    }
}
