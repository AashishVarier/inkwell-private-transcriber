package com.variableduck.inkwell.transcriber.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.variableduck.inkwell.transcriber.data.Note
import com.variableduck.inkwell.transcriber.data.NoteEntry
import com.variableduck.inkwell.transcriber.data.NoteNames
import com.variableduck.inkwell.transcriber.data.NoteRepository
import com.variableduck.inkwell.transcriber.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val extension: String, val mimeType: String) {
    TXT("txt", "text/plain"),
    MD("md", "text/markdown"),
}

/**
 * Getting transcripts off the phone. Shared and exported files use the note's timestamp name,
 * e.g. `11-11-2026-14-30-05.txt`.
 */
object ShareExport {

    fun fileName(note: Note, format: ExportFormat) = "${note.id}.${format.extension}"

    fun zipName(now: Long = System.currentTimeMillis()) = "inkwell-${NoteNames.baseName(now)}.zip"

    fun render(note: Note, text: String, format: ExportFormat): String = when (format) {
        ExportFormat.TXT -> text
        ExportFormat.MD -> buildString {
            append("# ").append(note.title).append("\n\n")
            append("_").append(NoteNames.displayTimestamp(note.createdAt))
            if (note.durationMs > 0) append(" · ").append(Format.duration(note.durationMs))
            note.modelName?.let { append(" · ").append(it) }
            append("_\n\n")
            append(text.trim()).append('\n')
        }
    }

    /** Share sheet with the transcript as plain text. */
    fun shareText(context: Context, note: Note, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Share sheet with the transcript attached as a `.txt` file (via FileProvider). */
    fun shareFile(context: Context, note: Note, text: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // Only the file being shared is kept in the share cache.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(note, ExportFormat.TXT))
        file.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Share ${file.name}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** Writes one transcript to a document the user created with the system file picker. */
    suspend fun exportTo(context: Context, uri: Uri, content: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Couldn't open the destination file")
    }

    /** Writes every transcript into one zip. Returns the number of transcripts exported. */
    suspend fun exportAll(
        context: Context,
        uri: Uri,
        entries: List<NoteEntry>,
        notes: NoteRepository,
        format: ExportFormat,
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        context.contentResolver.openOutputStream(uri, "wt")?.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                for (entry in entries.sortedBy { it.note.createdAt }) {
                    if (!entry.hasText) continue
                    val note = entry.note
                    zip.putNextEntry(ZipEntry(fileName(note, format)).apply { time = note.createdAt })
                    zip.write(render(note, notes.readText(note.id), format).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    count++
                }
            }
        } ?: throw IOException("Couldn't open the destination file")
        count
    }
}
