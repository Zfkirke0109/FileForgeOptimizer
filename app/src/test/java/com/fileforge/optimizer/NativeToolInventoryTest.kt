package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeToolInventoryTest {
    @Test
    fun currentInjectableInventoryHonestlyReportsNativeToolsUnavailable() {
        val tools = UnavailableNativeToolInventory.snapshot()

        assertEquals(listOf("qpdf", "oxipng", "jpegtran", "zopflipng", "zipalign"), tools.map { it.name })
        assertFalse(tools.any { it.available })
        assertEquals(setOf("Unavailable in this build"), tools.map { it.detail }.toSet())
    }
}
