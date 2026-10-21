package io.github.wzehirx.bpdf.ui.components

import android.content.res.Resources
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.files.ExportFailed
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.RangeErrorReason

/** Localized, user-facing message for this error. */
fun PdfError.toMessage(res: Resources): String = when (this) {
    is PdfError.PasswordRequired -> res.getString(R.string.err_password_required)
    is PdfError.WrongPassword -> res.getString(R.string.err_wrong_password)
    is PdfError.Corrupt -> res.getString(R.string.err_corrupt)
    is PdfError.Io -> res.getString(R.string.err_io)
    is PdfError.InvalidRange -> when (reason) {
        RangeErrorReason.EMPTY -> res.getString(R.string.err_range_empty)
        RangeErrorReason.NOT_A_NUMBER -> res.getString(R.string.err_range_not_number, token)
        RangeErrorReason.OUT_OF_BOUNDS -> res.getString(R.string.err_range_out_of_bounds, token)
        RangeErrorReason.REVERSED -> res.getString(R.string.err_range_reversed, token)
        RangeErrorReason.INCOMPLETE -> res.getString(R.string.err_range_incomplete, token)
    }
}

/** Message for any failure: PDF errors are specific, everything else is generic. */
fun Throwable.toUserMessage(res: Resources): String = when (this) {
    is PdfError -> toMessage(res)
    is ExportFailed -> res.getString(R.string.err_export_failed)
    else -> res.getString(R.string.error_generic)
}
