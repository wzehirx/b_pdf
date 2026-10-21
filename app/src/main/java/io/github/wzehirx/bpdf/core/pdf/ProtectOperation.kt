package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CancellationException

/** What a reader who only knows the user password may do with a protected document. */
data class PdfPermissions(val print: Boolean = true, val copy: Boolean = true, val modify: Boolean = true)

/** Encrypts a PDF with AES-256 so it asks for a password before opening. */
object ProtectOperation {
    /**
     * Writes an AES-256 encrypted copy of [input] to [out]. [userPassword] (required, not blank) opens
     * the document; [permissions] limit what that reader may do. [ownerPassword] lifts the limits; when it
     * is null or blank a random one nobody knows is used, so the limits cannot be bypassed.
     * [inputPassword] opens an input that is already encrypted. [isCancelled] is polled before loading and
     * after saving; any failure or cancellation leaves no [out].
     */
    fun run(
        input: File,
        userPassword: String,
        ownerPassword: String?,
        permissions: PdfPermissions,
        out: File,
        inputPassword: String? = null,
        isCancelled: () -> Boolean = { false },
    ) {
        require(userPassword.isNotEmpty()) { "The user password must not be empty" }
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Protect cancelled")
            PdfLoader.load(input, inputPassword).use { doc ->
                val access = AccessPermission().apply {
                    setCanPrint(permissions.print)
                    setCanExtractContent(permissions.copy)
                    setCanExtractForAccessibility(true)
                    setCanModify(permissions.modify)
                    setCanModifyAnnotations(permissions.modify)
                    setCanAssembleDocument(permissions.modify)
                    setCanFillInForm(permissions.modify)
                }
                val owner = ownerPassword?.takeIf { it.isNotEmpty() } ?: randomPassword()
                val policy = StandardProtectionPolicy(owner, userPassword, access)
                policy.encryptionKeyLength = 256
                try {
                    doc.protect(policy)
                    doc.save(out)
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                }
            }
            if (isCancelled()) throw CancellationException("Protect cancelled")
            success = true
        } finally {
            if (!success) out.delete()
        }
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
