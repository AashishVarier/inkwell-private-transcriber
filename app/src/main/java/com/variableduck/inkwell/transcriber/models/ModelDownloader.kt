package com.variableduck.inkwell.transcriber.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * The app's only network code. Downloads one model file, only when the user taps Download.
 *
 * Writes to `<name>.part`, resumes with an HTTP Range request, and renames to the final name
 * only after the size and SHA-256 match the catalog.
 */
class ModelDownloader {

    class VerificationException(message: String) : IOException(message)

    /** Lets the caller abort a blocking read immediately when the user taps Cancel. */
    @Volatile
    var activeConnection: HttpURLConnection? = null
        private set

    suspend fun download(
        model: CatalogModel,
        partFile: File,
        finalFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onVerifying: () -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (partFile.length() > model.sizeBytes) partFile.delete()

        if (partFile.length() < model.sizeBytes) {
            fetch(model, partFile, onProgress)
        }

        coroutineContext.ensureActive()
        val size = partFile.length()
        if (size != model.sizeBytes) {
            // Shorter: the connection dropped; keep the part file so the user can resume.
            throw IOException("Download incomplete (${size} of ${model.sizeBytes} bytes). Tap Resume.")
        }

        onVerifying()
        val actual = sha256(partFile)
        if (!actual.equals(model.sha256, ignoreCase = true)) {
            partFile.delete()
            throw VerificationException("Checksum mismatch, the download was corrupted. Please download again.")
        }

        finalFile.delete()
        if (!partFile.renameTo(finalFile)) throw IOException("Couldn't save ${finalFile.name}")
    }

    private suspend fun fetch(model: CatalogModel, partFile: File, onProgress: (Long, Long) -> Unit) {
        var url = URL(ModelCatalog.BASE_URL + model.fileName)
        var existing = partFile.length()
        var redirects = 0
        var conn: HttpURLConnection
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false // follow manually so the Range header survives
                connectTimeout = 20_000
                readTimeout = 30_000
                useCaches = false
                setRequestProperty("User-Agent", "Inkwell")
                setRequestProperty("Accept-Encoding", "identity")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            activeConnection = conn
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location == null || ++redirects > 10) throw IOException("Too many redirects")
                url = URL(url, location)
                if (url.protocol != "https") throw IOException("Refusing non-HTTPS redirect")
                continue
            }
            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> Unit // resume
                HttpURLConnection.HTTP_OK -> existing = 0 // server ignored Range: start over
                416 -> { // range not satisfiable: we already have the whole file
                    conn.disconnect()
                    activeConnection = null
                    return
                }
                else -> {
                    conn.disconnect()
                    activeConnection = null
                    throw IOException("Server returned HTTP $code")
                }
            }
            break
        }

        try {
            conn.inputStream.use { input ->
                FileOutputStream(partFile, existing > 0).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = existing
                    var lastReport = 0L
                    onProgress(downloaded, model.sizeBytes)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        downloaded += n
                        if (downloaded > model.sizeBytes) throw IOException("Server sent more data than expected")
                        val now = System.nanoTime()
                        if (now - lastReport > 200_000_000L) {
                            lastReport = now
                            onProgress(downloaded, model.sizeBytes)
                        }
                    }
                    out.fd.sync()
                    onProgress(downloaded, model.sizeBytes)
                }
            }
        } finally {
            conn.disconnect()
            activeConnection = null
        }
    }

    fun cancelActive() {
        activeConnection?.disconnect()
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
