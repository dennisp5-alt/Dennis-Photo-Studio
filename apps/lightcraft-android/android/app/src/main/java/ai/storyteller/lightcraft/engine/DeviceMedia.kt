package ai.storyteller.lightcraft.engine

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

/** A photo of the phone's own gallery (MediaStore), as the Device tab lists it. */
data class DevicePhoto(
    val id: Long,
    val uri: Uri,
    val name: String,
    /** When it was taken (or, without that, added), in ms since the epoch. */
    val taken: Long,
    /** Width over height as shown (its orientation applied). */
    val aspect: Double,
    /** Its folder in the gallery (Camera, Screenshots…). */
    val album: String,
    /** Its type (`image/jpeg`, `image/x-adobe-dng`…), empty when the gallery doesn't say. */
    val mime: String = "",
)

/** What the Device tab may see of the gallery: nothing, the photos the person picked (Android 14+), or all of it. */
enum class DeviceAccess { NONE, PARTIAL, FULL }

/** The phone's gallery: whether LightCraft may read it, its photos and their thumbnails. */
object DeviceMedia {
    /** What to ask for: the photos (on Android 14 and later the person may pick only some). */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(context: Context): DeviceAccess {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> DeviceAccess.FULL
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> DeviceAccess.PARTIAL
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> DeviceAccess.FULL
            else -> DeviceAccess.NONE
        }
    }

    /** The gallery's photos LightCraft may see, newest first. Blocks: call it off the main thread. */
    fun load(context: Context): List<DevicePhoto> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val order = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        val out = mutableListOf<DevicePhoto>()
        context.contentResolver.query(collection, columns, null, null, order)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val added = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val width = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val orientation = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            val bucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val mime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val rowId = c.getLong(id)
                val (w, h) = c.getInt(width) to c.getInt(height)
                val turned = c.getInt(orientation) % 180 != 0
                val aspect = if (w > 0 && h > 0) (if (turned) h.toDouble() / w else w.toDouble() / h) else 1.0
                out += DevicePhoto(
                    id = rowId,
                    uri = ContentUris.withAppendedId(collection, rowId),
                    name = c.getString(name).orEmpty(),
                    taken = c.getLong(taken).takeIf { it > 0 } ?: (c.getLong(added) * 1000),
                    aspect = aspect,
                    album = c.getString(bucket).orEmpty(),
                    mime = c.getString(mime).orEmpty(),
                )
            }
        }
        return out
    }

    /** A grid thumbnail of [photo], about [edge] px on its long side, or null. Blocks. */
    fun thumbnail(context: Context, photo: DevicePhoto, edge: Int): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(photo.uri, Size(edge, edge), null) }.getOrNull()
}
