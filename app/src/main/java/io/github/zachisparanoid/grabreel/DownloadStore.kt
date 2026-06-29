package io.github.zachisparanoid.grabreel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pure list transforms — unit-tested without Android. */
object DownloadReducer {
    fun upsert(list: List<Download>, d: Download): List<Download> {
        val i = list.indexOfFirst { it.id == d.id }
        return if (i >= 0) list.toMutableList().also { it[i] = d } else listOf(d) + list
    }

    fun update(list: List<Download>, id: String, f: (Download) -> Download): List<Download> =
        list.map { if (it.id == id) f(it) else it }

    fun remove(list: List<Download>, id: String): List<Download> = list.filterNot { it.id == id }

    fun clearFinished(list: List<Download>): List<Download> =
        list.filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
}

/** App-wide registry of downloads the UI observes. */
object DownloadStore {
    private val _items = MutableStateFlow<List<Download>>(emptyList())
    val items: StateFlow<List<Download>> = _items.asStateFlow()

    @Synchronized fun add(d: Download) { _items.value = DownloadReducer.upsert(_items.value, d) }

    @Synchronized fun update(id: String, f: (Download) -> Download) {
        _items.value = DownloadReducer.update(_items.value, id, f)
    }

    @Synchronized fun remove(id: String) { _items.value = DownloadReducer.remove(_items.value, id) }

    @Synchronized fun clearFinished() { _items.value = DownloadReducer.clearFinished(_items.value) }
}
