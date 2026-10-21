package io.github.wzehirx.bpdf.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PageSelectionTextTest {
    @Test
    fun selected_isEmptyForBlankOrInvalidText() {
        assertEquals(emptyList<Int>(), PageSelectionText.selected("", 5))
        assertEquals(emptyList<Int>(), PageSelectionText.selected("5-2", 5))
        assertEquals(emptyList<Int>(), PageSelectionText.selected("9", 5))
        assertEquals(listOf(0, 1, 2, 4), PageSelectionText.selected("1-3, 5", 5))
    }

    @Test
    fun toggle_addsAtTheEnd_andRemoves_keepingTypedOrder() {
        assertEquals("1", PageSelectionText.toggle("", 5, 0))
        assertEquals("3,1", PageSelectionText.toggle("3", 5, 0))
        assertEquals("1-2", PageSelectionText.toggle("1", 5, 1))
        assertEquals("1,3", PageSelectionText.toggle("1-3", 5, 1))
        assertEquals("", PageSelectionText.toggle("2", 5, 1))
    }

    @Test
    fun toggle_withInvalidText_startsOver() {
        assertEquals("4", PageSelectionText.toggle("abc", 5, 3))
    }

    @Test
    fun all_listsEveryPageAsOneRange() {
        assertEquals("1-4", PageSelectionText.all(4))
        assertEquals("1", PageSelectionText.all(1))
    }
}
