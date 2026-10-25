package ai.storyteller.lightcraft.engine

import ai.storyteller.lightcraft.core.ExportedFile
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Getting photos in (picked documents → files the engine imports) and out (exports → Pictures). */
object PhotoFiles {
    private val unsafeChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    /**
     * Copy the documents at [uris] into [dir] under their own names (made unique), for an import
     * that then moves them into the library. Documents that can't be read are skipped.
     */
    fun stage(context: Context, uris: List<Uri>, dir: File): List<File> {
        dir.mkdirs()
        return uris.mapNotNull { uri ->
            runCatching {
                val target = uniqueFile(dir, fileName(context, uri))
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("can't read $uri")
                input.use { src -> target.outputStream().use { src.copyTo(it, 1 shl 16) } }
                target
            }.getOrNull()
        }
    }

    /** The document's name, with an extension (the engine tells formats apart by both). */
    private fun fileName(context: Context, uri: Uri): String {
        val shown = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        var name = (shown ?: uri.lastPathSegment ?: "photo").replace(unsafeChars, "_").trim().ifEmpty { "photo" }
        if (!name.contains('.')) {
            val ext = context.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (ext != null) name += ".$ext"
        }
        return name
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (file.exists()) {
            file = File(dir, if (ext.isEmpty()) "$stem-$n" else "$stem-$n.$ext")
            n++
        }
        return file
    }

    /** Save an export into Pictures/LumaWorks (the system picks another name if it is taken). */
    fun saveToPictures(context: Context, file: ExportedFile): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.fileName)
            put(MediaStore.Images.Media.MIME_TYPE, file.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LumaWorks")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("can't create ${file.fileName} in Pictures")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("can't write ${file.fileName}")
            out.use { it.write(file.bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /**
     * An export for the share sheet only: in the app's cache, so sharing leaves no copy in the
     * phone's photos (saving does that). Shares older than a day go (the app shared with may read
     * one later, an email sent afterwards say).
     */
    fun shareable(context: Context, file: ExportedFile): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val old = System.currentTimeMillis() - 24 * 3_600_000L
        dir.listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
        val target = uniqueFile(dir, file.fileName.replace(unsafeChars, "_").ifBlank { "photo" })
        target.writeBytes(file.bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }

    /** Several files at once (select mode's Share): one chooser for all of them. */
    fun shareMany(context: Context, uris: List<Uri>, mimeType: String) {
        val first = uris.firstOrNull() ?: return
        if (uris.size == 1) return share(context, first, mimeType)
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newRawUri(null, first).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun share(context: Context, uri: Uri, mimeType: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            // the grant travels with the clip, through the chooser to the app picked
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
