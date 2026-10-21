package io.github.wzehirx.bpdf.feature.about

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.ui.theme.BPdfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AboutScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<String>()

    private val licenses = parseLicenses(
        ApplicationProvider.getApplicationContext<android.content.Context>().resources
            .openRawResource(R.raw.licenses).bufferedReader().use { it.readText() },
    )

    private fun show() = rule.setContent {
        BPdfTheme {
            AboutScreenContent("1.0.0", 10, licenses, onBack = {}, onOpenUrl = { opened += it })
        }
    }

    @Test
    fun bundledLicenseList_hasEveryRequiredComponent_withLicenseAndUrl() {
        val names = licenses.map { it.name }
        listOf("PdfBox-Android", "Tesseract4Android", "tessdata_fast", "Noto Sans", "AndroidX", "Compose", "coroutines", "ExifInterface", "DataStore")
            .forEach { key -> assertTrue("missing $key in $names", names.any { it.contains(key) }) }
        assertTrue(licenses.all { it.license.isNotBlank() && it.url.startsWith("https://") })
        assertEquals("OFL-1.1", licenses.first { it.name == "Noto Sans" }.license)
    }

    @Test
    fun parse_skipsEntriesWithoutName() {
        assertEquals(listOf(LicenseEntry("A", "MIT", "https://a")), parseLicenses("""[{"license":"x"},{"name":"A","license":"MIT","url":"https://a"}]"""))
    }

    @Test
    fun showsVersion_license_andHeadings() {
        show()
        rule.onNodeWithText("Version 1.0.0 (build 10)").assertExists()
        rule.onNodeWithText("b PDF is free software under the Apache License 2.0.").assertExists()
        listOf("About", "License", "Source code", "Open-source licenses").forEach {
            rule.onNode(hasText(it) and isHeading()).assertExists()
        }
    }

    @Test
    fun sourceLink_andLicenseLink_open() {
        show()
        rule.onNodeWithText("Open the source code on GitHub").performScrollTo().performClick()
        rule.onNodeWithText("Read the Apache License 2.0").performScrollTo().performClick()
        assertEquals(listOf("https://github.com/wzehirx/b_pdf", LICENSE_URL), opened)
    }

    @Test
    fun eachLicenseRow_isClickable_andOpensItsPage() {
        show()
        val row = rule.onNode(hasText("PdfBox-Android") and hasClickAction()).performScrollTo()
        row.performClick()
        assertEquals(listOf("https://github.com/TomRoush/PdfBox-Android"), opened)
        rule.onAllNodes(hasClickAction() and hasText("Apache-2.0", substring = true)).assertCountEquals(licenses.count { it.license == "Apache-2.0" })
    }
}
