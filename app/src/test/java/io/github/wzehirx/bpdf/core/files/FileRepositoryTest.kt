package io.github.wzehirx.bpdf.core.files

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FileRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = FileRepository(context)

    @org.junit.Before
    fun resetFileProviderCache() {
        // FileProvider caches its root map per authority in a static; each Robolectric test has
        // a fresh cache dir, so drop the stale entry.
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    @Test
    fun suggestName_stripsExtensionAndAppendsSuffix() {
        assertEquals("report_merged.pdf", suggestName("report.pdf", "merged", "pdf"))
    }

    @Test
    fun suggestName_nullFallsBackToApp() {
        assertEquals("bpdf_merged.pdf", suggestName(null, "merged", "pdf"))
    }

    @Test
    fun suggestName_noExtension() {
        assertEquals("scan_x.pdf", suggestName("scan", "x", "pdf"))
    }

    @Test
    fun suggestName_sanitizesInvalidCharsAndKeepsTurkish() {
        assertEquals("a_b_c_ğüşı_x.png", suggestName("a/b:c ğüşı.pdf".replace(' ', '_'), "x", "png"))
        assertEquals("a_b_x.pdf", suggestName("a*b.pdf", "x", "pdf"))
    }

    @Test
    fun suggestName_blankFallsBack() {
        assertEquals("bpdf_x.pdf", suggestName("  ", "x", "pdf"))
        assertEquals("bpdf_x.pdf", suggestName(".pdf", "x", "pdf"))
    }

    @Test
    fun importToCache_copiesBytesAndNames() = runBlocking {
        val src = File(context.cacheDir, "src.pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val imported = repo.importToCache(Uri.fromFile(src))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), imported.file.readBytes())
        assertEquals(4L, imported.sizeBytes)
        assertEquals("src.pdf", imported.displayName)
        assertEquals("imports", imported.file.parentFile!!.name)
        val again = repo.importToCache(Uri.fromFile(src))
        assertTrue(imported.file != again.file)
    }

    @Test
    fun importToCache_usesGivenExtension() = runBlocking {
        val src = File(context.cacheDir, "pic.png").apply { writeBytes(byteArrayOf(1)) }
        val imported = repo.importToCache(Uri.fromFile(src), extension = "png")
        assertTrue(imported.file.name.endsWith(".png"))
    }

    @Test
    fun importToCache_failedCopyLeavesNoPartialFile() {
        val uri = Uri.parse("content://test.broken/broken.pdf")
        val broken = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 10) 1 else throw java.io.IOException("disk gone")
        }
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri, broken)
        try {
            runBlocking { repo.importToCache(uri) }
            org.junit.Assert.fail("expected IOException")
        } catch (e: java.io.IOException) {
            // expected
        }
        val leftovers = File(context.cacheDir, "imports").listFiles().orEmpty()
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun shareUri_copyKeepsCleanName() {
        val outside = File(context.cacheDir, "tmp-result.pdf").apply { writeBytes(byteArrayOf(1, 2)) }
        val uri = repo.shareUri(outside, displayName = "report_merged.pdf")
        assertEquals("report_merged.pdf", uri.lastPathSegment)
        val uri2 = repo.shareUri(outside)
        assertEquals("tmp-result.pdf", uri2.lastPathSegment)
        assertTrue(uri != uri2)
    }

    @Test
    fun shareUri_displayNameCannotEscapeDirectory() {
        val f = repo.newOutputFile("pdf").apply { writeBytes(byteArrayOf(1)) }
        val uri = repo.shareUri(f, displayName = "../../evil.pdf")
        assertEquals("evil.pdf", uri.lastPathSegment)
    }

    @Test
    fun displayName_fallsBackToLastSegmentThenNull() {
        assertEquals("x.pdf", repo.displayName(Uri.parse("content://nope/doc/x.pdf")))
        assertNull(repo.displayName(Uri.parse("content://nope")))
    }

    @Test
    fun export_writesToTarget() = runBlocking {
        val out = repo.newOutputFile("pdf").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val target = File(context.cacheDir, "target.bin")
        repo.export(out, Uri.fromFile(target))
        assertArrayEquals(byteArrayOf(9, 8, 7), target.readBytes())
    }

    @Test
    fun newOutputFile_isInSharedWithExtension() {
        val f = repo.newOutputFile("pdf")
        assertEquals("shared", f.parentFile!!.name)
        assertTrue(f.name.endsWith(".pdf"))
        assertTrue(repo.newOutputFile("pdf") != f)
    }

    @Test
    fun shareUri_usesFileProviderAuthority() {
        val f = repo.newOutputFile("pdf").apply { writeBytes(byteArrayOf(1)) }
        val uri = repo.shareUri(f)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
    }

    @Test
    fun clearCache_removesImportsAndShared() = runBlocking {
        val src = File(context.cacheDir, "s.pdf").apply { writeBytes(byteArrayOf(1)) }
        val imp = repo.importToCache(Uri.fromFile(src))
        val out = repo.newOutputFile("pdf").apply { writeBytes(byteArrayOf(1)) }
        repo.clearCache()
        assertFalse(imp.file.exists())
        assertFalse(out.exists())
    }

    @Test
    fun purgeStale_deletesOnlyOldEntriesInImportsAndShared() {
        // Real timestamps: the Application's own background purge (24h) must not touch these.
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val imports = File(context.cacheDir, "imports").apply { mkdirs() }
        val oldImport = File(imports, "old.pdf").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 2 * hour) }
        val newImport = File(imports, "new.pdf").apply { writeBytes(byteArrayOf(1)); setLastModified(now - hour / 2) }
        val oldDir = File(File(context.cacheDir, "shared"), "uuid").apply { mkdirs() }
        File(oldDir, "x.pdf").writeBytes(byteArrayOf(1))
        oldDir.setLastModified(now - 2 * hour)
        val keep = File(context.cacheDir, "other.txt").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 2 * hour) }
        repo.purgeStale(maxAgeMillis = hour, now = now)
        assertFalse(oldImport.exists())
        assertTrue(newImport.exists())
        assertFalse(oldDir.exists())
        assertTrue(keep.exists())
    }

    @Test
    fun tryPersistReadPermission_neverThrows_andRefusesWhatCannotBeKept() {
        // A file: Uri and an intent without the persistable flag are refused without touching the system.
        assertFalse(repo.tryPersistReadPermission(Uri.fromFile(File("/tmp/x.pdf"))))
        assertFalse(
            repo.tryPersistReadPermission(Uri.parse("content://com.example/doc/1"), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        // A picker Uri (no flags to check): whatever the system answers, it must not throw.
        repo.tryPersistReadPermission(Uri.parse("content://com.example/doc/1"))
        repo.releasePersistedPermission(Uri.parse("content://com.example/doc/1")) // also quiet
    }
}
