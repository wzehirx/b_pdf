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
}
