package io.github.wzehirx.bpdf.core.pdf

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
class ProtectUnlockOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())

    private fun out(name: String = "out.pdf") = File(tmp.root, name)

    @Test
    fun protect_withoutPassword_throwsPasswordRequired() {
        val input = TestPdfs.create(tmp.root, 2)
        ProtectOperation.run(input, "open-sesame", null, PdfPermissions(), out())
        try {
            PdfLoader.load(out())
            fail("expected PasswordRequired")
        } catch (_: PdfError.PasswordRequired) {
        }
    }

    @Test
    fun protect_wrongPassword_throwsWrongPassword() {
        val input = TestPdfs.create(tmp.root, 1)
        ProtectOperation.run(input, "open-sesame", null, PdfPermissions(), out())
        try {
            PdfLoader.load(out(), "nope")
            fail("expected WrongPassword")
        } catch (_: PdfError.WrongPassword) {
        }
    }

    @Test
    fun protect_withPassword_loadsAndKeepsContent() {
        val input = TestPdfs.create(tmp.root, 3)
        ProtectOperation.run(input, "open-sesame", null, PdfPermissions(), out())
        assertEquals(listOf("Page1", "Page2", "Page3"), TestPdfs.text(out(), "open-sesame"))
        PdfLoader.load(out(), "open-sesame").use { assertTrue(it.isEncrypted) }
    }

    @Test
    fun protect_permissionsMatchWhatWasChosen() {
        val input = TestPdfs.create(tmp.root, 1)
        ProtectOperation.run(input, "u", "o", PdfPermissions(print = false, copy = true, modify = false), out())
        PdfLoader.load(out(), "u").use { doc ->
            val access = doc.currentAccessPermission
            assertFalse(access.canPrint())
            assertTrue(access.canExtractContent())
            assertFalse(access.canModify())
        }
        ProtectOperation.run(input, "u", "o", PdfPermissions(print = true, copy = false, modify = true), out("b.pdf"))
        PdfLoader.load(out("b.pdf"), "u").use { doc ->
            val access = doc.currentAccessPermission
            assertTrue(access.canPrint())
            assertFalse(access.canExtractContent())
            assertTrue(access.canModify())
        }
    }

    @Test
    fun protect_ownerPassword_opensWithFullRights() {
        val input = TestPdfs.create(tmp.root, 1)
        ProtectOperation.run(input, "u", "o", PdfPermissions(print = false, copy = false, modify = false), out())
        PdfLoader.load(out(), "o").use { doc ->
            assertTrue(doc.currentAccessPermission.isOwnerPermission)
        }
    }

    @Test
    fun protect_noOwnerPassword_userCannotLiftRestrictions() {
        val input = TestPdfs.create(tmp.root, 1)
        ProtectOperation.run(input, "u", null, PdfPermissions(print = false), out())
        PdfLoader.load(out(), "u").use { assertFalse(it.currentAccessPermission.isOwnerPermission) }
    }

    @Test
    fun protect_blankUserPassword_isRejected() {
        val input = TestPdfs.create(tmp.root, 1)
        try {
            ProtectOperation.run(input, "", null, PdfPermissions(), out())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun protect_alreadyEncryptedInput_usesInputPassword() {
        val input = TestPdfs.create(tmp.root, 2, password = "old")
        ProtectOperation.run(input, "new", null, PdfPermissions(), out(), inputPassword = "old")
        assertEquals(listOf("Page1", "Page2"), TestPdfs.text(out(), "new"))
    }

    @Test
    fun protect_cancelled_leavesNoOutput() {
        val input = TestPdfs.create(tmp.root, 1)
        try {
            ProtectOperation.run(input, "u", null, PdfPermissions(), out(), isCancelled = { true })
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun unlock_producesFileThatLoadsWithoutPassword() {
        val input = TestPdfs.create(tmp.root, 3, password = "secret")
        UnlockOperation.run(input, "secret", out())
        assertEquals(listOf("Page1", "Page2", "Page3"), TestPdfs.text(out()))
        PdfLoader.load(out()).use { assertFalse(it.isEncrypted) }
    }

    @Test
    fun unlock_wrongPassword_throwsAndLeavesNoOutput() {
        val input = TestPdfs.create(tmp.root, 1, password = "secret")
        try {
            UnlockOperation.run(input, "bad", out())
            fail("expected WrongPassword")
        } catch (_: PdfError.WrongPassword) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun unlock_cancelled_leavesNoOutput() {
        val input = TestPdfs.create(tmp.root, 1, password = "secret")
        try {
            UnlockOperation.run(input, "secret", out(), isCancelled = { true })
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertFalse(out().exists())
    }
}
