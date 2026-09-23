package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTextTest {
    @Test
    fun textWithinTheLimitIsReturnedUnchanged() {
        val exact = "p".repeat(MAX_DISPLAY_CHARS)
        assertEquals("", boundedForDisplay(""))
        assertEquals("DCIM/Camera/IMG_0001.jpg", boundedForDisplay("DCIM/Camera/IMG_0001.jpg"))
        assertSame(exact, boundedForDisplay(exact))
    }

    @Test
    fun longTextKeepsItsStartAndEndAroundAMiddleEllipsis() {
        assertEquals("abcde…wxyz", boundedForDisplay("abcdefghijklmnopqrstuvwxyz", maxChars = 10))
    }

    /** The path the instrumented test uses, which took the app to ~6 GB before this bound. */
    @Test
    fun theOversizedRestorePathShrinksToTheLimitAndStaysReadable() {
        val huge = "docs/${"x".repeat(600_000)}.txt"
        val shown = boundedForDisplay(huge)

        assertEquals(MAX_DISPLAY_CHARS, shown.length)
        assertTrue(shown.startsWith("docs/"))
        assertTrue(shown.endsWith(".txt"))
        assertTrue(shown.contains('…'))
    }

    @Test
    fun resultNeverExceedsTheLimit() {
        for (length in 0..40) {
            for (limit in 2..12) {
                val shown = boundedForDisplay("q".repeat(length), maxChars = limit)
                assertTrue("length=$length limit=$limit -> ${shown.length}", shown.length <= limit)
            }
        }
    }

    /** A file name can hold emoji; cutting between a surrogate pair's halves corrupts it. */
    @Test
    fun surrogatePairsAreNeverSplit() {
        val emoji = "😀"
        // maxChars 6 keeps a 3-char head and a 2-char tail, and both cuts land mid-pair.
        val shown = boundedForDisplay("ab$emoji${"x".repeat(20)}${emoji}z", maxChars = 6)

        assertEquals("ab…z", shown)
        assertFalse(hasUnpairedSurrogate(shown))
    }

    @Test
    fun aLimitWithNoRoomBesideTheEllipsisIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { boundedForDisplay("abc", maxChars = 1) }
    }

    private fun hasUnpairedSurrogate(text: String): Boolean = text.indices.any { i ->
        val c = text[i]
        (c.isHighSurrogate() && text.getOrNull(i + 1)?.isLowSurrogate() != true) ||
            (c.isLowSurrogate() && text.getOrNull(i - 1)?.isHighSurrogate() != true)
    }
}
