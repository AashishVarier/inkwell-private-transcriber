package com.variableduck.inkwell.transcriber

import com.variableduck.inkwell.transcriber.share.ShareExport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Copies made for "Share as .txt file" don't outlive the transcript they were made from. */
class ShareCacheTest {

    private fun cacheWithShareDir(): Pair<File, File> {
        val cache = Files.createTempDirectory("inkwell-cache").toFile()
        return cache to File(cache, "share").apply { mkdirs() }
    }

    @Test
    fun forgetDeletesOnlyTheGivenNotesCopies() {
        val (cache, share) = cacheWithShareDir()
        File(share, "a.txt").writeText("a")
        File(share, "b.txt").writeText("b")

        ShareExport.forget(cache, listOf("a", "not-shared"))

        assertFalse(File(share, "a.txt").exists())
        assertTrue(File(share, "b.txt").exists())
    }

    @Test
    fun purgeStaleRemovesOnlyOldCopies() {
        val (cache, share) = cacheWithShareDir()
        val now = System.currentTimeMillis()
        val old = File(share, "old.txt").apply { writeText("x"); setLastModified(now - 2 * 24 * 60 * 60 * 1000L) }
        val recent = File(share, "recent.txt").apply { writeText("x"); setLastModified(now - 60 * 60 * 1000L) }

        ShareExport.purgeStale(cache, now)

        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun nothingSharedYetIsFine() {
        val cache = Files.createTempDirectory("inkwell-empty-cache").toFile()
        ShareExport.forget(cache, listOf("a"))
        ShareExport.purgeStale(cache)
        assertFalse(File(cache, "share").exists())
    }
}
