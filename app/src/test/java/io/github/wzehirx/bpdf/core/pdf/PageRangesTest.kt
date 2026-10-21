package io.github.wzehirx.bpdf.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PageRangesTest {
    private fun assertInvalid(spec: String, count: Int, reason: RangeErrorReason, token: String? = null) {
        try {
            PageRanges.parse(spec, count)
            fail("expected InvalidRange for '$spec'")
        } catch (e: PdfError.InvalidRange) {
            assertEquals(reason, e.reason)
            if (token != null) assertEquals(token, e.token)
        }
    }

    @Test fun parsesRangesAndSingles() = assertEquals(listOf(0, 1, 2, 4), PageRanges.parse("1-3,5", 10))

    @Test fun toleratesWhitespace() = assertEquals(listOf(1, 3, 4), PageRanges.parse(" 2 , 4-5 ", 10))

    @Test fun keepsWrittenOrderAndRemovesDuplicates() =
        assertEquals(listOf(4, 0, 1), PageRanges.parse("5,1-2,2", 10))

    @Test fun reversed() = assertInvalid("3-1", 10, RangeErrorReason.REVERSED, "3-1")

    @Test fun zeroOutOfBounds() = assertInvalid("0", 10, RangeErrorReason.OUT_OF_BOUNDS, "0")

    @Test fun beyondCountOutOfBounds() = assertInvalid("11", 10, RangeErrorReason.OUT_OF_BOUNDS, "11")

    @Test fun rangeEndBeyondCount() = assertInvalid("8-11", 10, RangeErrorReason.OUT_OF_BOUNDS)

    @Test fun notANumber() = assertInvalid("abc", 10, RangeErrorReason.NOT_A_NUMBER, "abc")

    @Test fun incomplete() = assertInvalid("1-", 10, RangeErrorReason.INCOMPLETE, "1-")

    @Test fun empty() = assertInvalid("", 10, RangeErrorReason.EMPTY)

    @Test fun blankIsEmpty() = assertInvalid("  ", 10, RangeErrorReason.EMPTY)

    @Test fun emptyTokenBetweenCommas() = assertInvalid("1,,2", 10, RangeErrorReason.EMPTY)

    @Test fun groupsPerToken() = assertEquals(listOf(listOf(0, 1), listOf(2)), PageRanges.parseGroups("1-2,3", 5))

    @Test fun formatCompact() = assertEquals("1-3,5", PageRanges.format(listOf(0, 1, 2, 4)))

    @Test fun formatSingleAndEmpty() {
        assertEquals("2", PageRanges.format(listOf(1)))
        assertEquals("", PageRanges.format(emptyList()))
    }
}
