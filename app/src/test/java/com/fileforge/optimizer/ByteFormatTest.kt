package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteFormatTest {
    @Test
    fun exactBytesBelowOneKibibyte() {
        assertEquals("0 B", formatByteSize(0))
        assertEquals("1 B", formatByteSize(1))
        assertEquals("845 B", formatByteSize(845))
        assertEquals("1023 B", formatByteSize(1023))
    }

    @Test
    fun oneDecimalPlaceAtAndAboveOneKibibyte() {
        assertEquals("1.0 KiB", formatByteSize(1024))
        assertEquals("1.5 KiB", formatByteSize(1536))
        assertEquals("1.0 MiB", formatByteSize(1024L * 1024))
        assertEquals("1.0 GiB", formatByteSize(1024L * 1024 * 1024))
        assertEquals("1.0 TiB", formatByteSize(1024L * 1024 * 1024 * 1024))
        assertEquals("1.0 PiB", formatByteSize(1024L * 1024 * 1024 * 1024 * 1024))
    }

    /** The savings total from the first successful device run. */
    @Test
    fun rendersARealSavingsTotal() {
        assertEquals("4.8 MiB", formatByteSize(5_031_338))
    }

    /**
     * One byte below a unit boundary rounds up to 1024.0 of the smaller unit, which would read as
     * "1024.0 KiB". The unit has to be promoted after rounding, not before.
     */
    @Test
    fun roundingUpToAFullUnitPromotesTheUnit() {
        assertEquals("1.0 MiB", formatByteSize(1024L * 1024 - 1))
        assertEquals("1.0 GiB", formatByteSize(1024L * 1024 * 1024 - 1))
        assertEquals("1.0 TiB", formatByteSize(1024L * 1024 * 1024 * 1024 - 1))
    }

    @Test
    fun negativeCountsKeepTheirSign() {
        assertEquals("-1 B", formatByteSize(-1))
        assertEquals("-1023 B", formatByteSize(-1023))
        assertEquals("-1.0 KiB", formatByteSize(-1024))
        assertEquals("-4.8 MiB", formatByteSize(-5_031_338))
    }

    /** Nothing above the largest unit exists, so the value keeps growing in pebibytes. */
    @Test
    fun extremesClampToTheLargestUnitWithoutOverflowing() {
        assertEquals("8192.0 PiB", formatByteSize(Long.MAX_VALUE))
        assertEquals("-8192.0 PiB", formatByteSize(Long.MIN_VALUE))
    }

    @Test
    fun everyValueRendersANonEmptyLabelledSize() {
        val units = listOf(" B", " KiB", " MiB", " GiB", " TiB", " PiB")
        val samples = listOf(
            Long.MIN_VALUE, -1_048_576L, -1L, 0L, 1L, 1023L, 1024L,
            999_999L, 5_031_338L, 1_099_511_627_776L, Long.MAX_VALUE
        )
        for (sample in samples) {
            val rendered = formatByteSize(sample)
            assertTrue("$sample rendered as \"$rendered\"", units.any(rendered::endsWith))
        }
    }
}
