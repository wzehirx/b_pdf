package io.github.wzehirx.bpdf.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Turkish screen readers read "/" aloud ("bölü" or "eğik çizgi"), so Turkish strings that can be spoken
 * must not contain it. The few that show a visual format keep it and have a separate spoken string.
 */
class TurkishSlashStringsTest {
    // Visual-only formats (a spoken variant exists for each: page_numbers_*_spoken).
    private val visualOnly = setOf("page_numbers_fmt_number_total", "page_numbers_fmt_localized", "page_numbers_format_localized")

    @Test
    fun spokenTurkishStrings_haveNoSlash() {
        val file = listOf("src/main/res/values-tr/strings.xml", "app/src/main/res/values-tr/strings.xml").map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = doc.getElementsByTagName("string")
        val offenders = (0 until strings.length).map { strings.item(it) }
            .filter { n -> n.attributes.getNamedItem("name").nodeValue !in visualOnly }
            .filter { n -> n.textContent.replace(Regex("https?://\\S+"), "").contains('/') }
            .map { it.attributes.getNamedItem("name").nodeValue }
        assertTrue("Turkish strings with a slash: $offenders", offenders.isEmpty())
    }
}
