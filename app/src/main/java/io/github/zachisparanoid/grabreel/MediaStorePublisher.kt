package io.github.zachisparanoid.grabreel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/** Moves a finished download out of app-private storage into the public Downloads/ folder. */
object MediaStorePublisher {
    data class Published(val uri: Uri?, val displayName: String)

    fun publish(context: Context, src: File): Published {
        val name = FileNames.sanitize(src.name)
        val mime = FileNames.mimeForExtension(src.extension)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishQ(context, src, name, mime)
        } else {
            publishLegacy(src, name)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishQ(context: Context, src: File, name: String, mime: String): Published {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        src.delete()
        return Published(uri, name)
    }

    private fun publishLegacy(src: File, name: String): Published {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, FileNames.unique(name, dir.list()?.toSet() ?: emptySet()))
        src.copyTo(dest, overwrite = false)
        src.delete()
        return Published(Uri.fromFile(dest), dest.name)
    }
}
