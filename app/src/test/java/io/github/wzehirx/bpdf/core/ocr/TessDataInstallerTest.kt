package io.github.wzehirx.bpdf.core.ocr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class TessDataInstallerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sizes = mapOf("eng" to 10L, "tur" to 12L)
    private val opened = mutableListOf<String>()

    private fun installer(
        asset: (String) -> InputStream = { name -> ByteArray(if ("eng" in name) 10 else 12) { 1 }.inputStream() },
    ) = TessDataInstaller(tmp.root, { opened += it; asset(it) }, sizes)

    private fun file(code: String) = File(tmp.root, "tessdata/$code.traineddata")

    @Test
    fun firstUse_copiesTheRequestedLanguages_andReturnsTheDataPath() {
        val path = installer().ensure("tur+eng")
        assertEquals(tmp.root, path)
        assertEquals(10L, file("eng").length())
        assertEquals(12L, file("tur").length())
        assertEquals(listOf("tessdata/tur.traineddata", "tessdata/eng.traineddata"), opened)
    }

    @Test
    fun onlyTheRequestedLanguage_isCopied() {
        installer().ensure("eng")
        assertTrue(file("eng").exists())
        assertFalse(file("tur").exists())
    }

    @Test
    fun goodFiles_areNotCopiedAgain() {
        installer().ensure("eng")
        opened.clear()
        installer().ensure("eng")
        assertTrue(opened.isEmpty())
    }

    @Test
    fun truncatedFile_isReplaced() {
        file("eng").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3)) }
        installer().ensure("eng")
        assertEquals(10L, file("eng").length())
    }

    @Test
    fun interruptedCopy_leavesNoFile_andNoTemp() {
        val broken = { _: String ->
            object : InputStream() {
                var n = 0
                override fun read(): Int = if (n++ < 4) 1 else throw IOException("disk full")
            }
        }
        try {
            installer(broken).ensure("eng")
            fail()
        } catch (_: OcrFailed) {
        }
        assertFalse(file("eng").exists())
        assertEquals(emptyList<String>(), File(tmp.root, "tessdata").list().orEmpty().toList())
    }

    @Test
    fun assetWithTheWrongSize_isRejected_andNotInstalled() {
        try {
            installer(asset = { ByteArray(5).inputStream() }).ensure("eng")
            fail()
        } catch (_: OcrFailed) {
        }
        assertFalse(file("eng").exists())
        assertEquals(emptyList<String>(), File(tmp.root, "tessdata").list().orEmpty().toList())
    }

    @Test
    fun goodExistingFile_survivesAFailedReinstallOfAnother() {
        installer().ensure("eng")
        try {
            installer(asset = { throw IOException("missing") }).ensure("eng+tur")
            fail()
        } catch (_: OcrFailed) {
        }
        assertEquals(10L, file("eng").length())
    }

    @Test
    fun unknownLanguage_isRejected() {
        try {
            installer().ensure("xyz")
            fail()
        } catch (_: OcrFailed) {
        }
    }

    @Test
    fun everyOcrLanguageChoice_isInstallable() {
        OcrLanguage.entries.forEach { choice ->
            choice.codes.split('+').forEach { assertTrue(it, it in TessDataInstaller.BUNDLED_SIZES) }
        }
    }

    /** The bundled language files are the ones recorded in README.md and the notice next to them. */
    @Test
    fun bundledTessdata_matchesTheRecordedSizeAndSha256() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val expected = mapOf(
            "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
            "tur" to "7393381111e1152420fc4092cb44eef4237580d21b92bf30d7d221aad192c6b7",
        )
        for ((code, sha) in expected) {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.assets.open("tessdata/$code.traineddata").use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    size += n
                }
            }
            assertEquals(code, TessDataInstaller.BUNDLED_SIZES.getValue(code), size)
            assertEquals(code, sha, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
