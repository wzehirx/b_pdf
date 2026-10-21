package io.github.wzehirx.bpdf.core.pdf

enum class RangeErrorReason { EMPTY, NOT_A_NUMBER, OUT_OF_BOUNDS, REVERSED, INCOMPLETE }

/** Errors surfaced by the PDF engine; the UI maps each to a localized message. */
sealed class PdfError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class PasswordRequired : PdfError("Password required")
    class WrongPassword : PdfError("Wrong password")
    class Corrupt(cause: Throwable?) : PdfError("Corrupt or unreadable PDF", cause)
    class InvalidRange(val reason: RangeErrorReason, val token: String) :
        PdfError("Invalid page range ($reason): '$token'")
    /** An input image that cannot be decoded (not an image, or damaged); [fileName] is what the user picked. */
    class UnreadableImage(val fileName: String) : PdfError("Cannot decode image '$fileName'")
    class Io(cause: Throwable?) : PdfError("I/O error", cause)
}
