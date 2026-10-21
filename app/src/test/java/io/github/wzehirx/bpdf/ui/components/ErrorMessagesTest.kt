package io.github.wzehirx.bpdf.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.RangeErrorReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class ErrorMessagesTest {
    private fun errors(): List<PdfError> = listOf(
        PdfError.PasswordRequired(),
        PdfError.WrongPassword(),
        PdfError.Corrupt(null),
        PdfError.Io(null),
    ) + RangeErrorReason.entries.map { PdfError.InvalidRange(it, "x7") }

    private fun resources(locale: Locale) = ApplicationProvider.getApplicationContext<Context>()
        .createConfigurationContext(Configuration().apply { setLocale(locale) }).resources

    private fun check(locale: Locale) {
        val res = resources(locale)
        val messages = errors().map { it.toMessage(res) }
        messages.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(messages.size, messages.toSet().size)
    }

    @Test fun english_everyErrorHasDistinctMessage() = check(Locale.ENGLISH)

    @Test fun turkish_everyErrorHasDistinctMessage() = check(Locale("tr"))

    @Test fun rangeMessages_includeOffendingToken() {
        val res = resources(Locale.ENGLISH)
        RangeErrorReason.entries.filter { it != RangeErrorReason.EMPTY }.forEach {
            assertTrue(PdfError.InvalidRange(it, "x7").toMessage(res).contains("x7"))
        }
    }

    @Test fun ocrFailure_hasItsOwnMessage_inBothLanguages() {
        val error = io.github.wzehirx.bpdf.core.ocr.OcrFailed("no data")
        assertEquals("Text recognition could not start. Check the available storage and try again.", error.toUserMessage(resources(Locale.ENGLISH)))
        assertEquals("Metin tanıma başlatılamadı. Boş depolama alanını kontrol edip yeniden deneyin.", error.toUserMessage(resources(Locale("tr"))))
    }

    @Test fun turkishMessage_isActuallyTurkish() {
        val tr = PdfError.WrongPassword().toMessage(resources(Locale("tr")))
        assertEquals("Parola yanlış. Yeniden deneyin.", tr)
    }
}
