package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentPathPolicyTest {
    @Test
    fun safDisplayNameCharactersRemainLiteralPathSegmentContent() {
        assertEquals("report:final\\v2.zip", DocumentPathPolicy.requireSafeSegment("report:final\\v2.zip"))
        assertEquals(
            listOf("folder:2026", "report\\final.zip"),
            DocumentPathPolicy.requireSafeRelative("folder:2026/report\\final.zip")
        )
        assertEquals(listOf("\\leading-backslash.zip"), DocumentPathPolicy.requireSafeRelative("\\leading-backslash.zip"))
        assertEquals(listOf(" "), DocumentPathPolicy.requireSafeRelative(" "))
    }

    @Test
    fun onlyForwardSlashStructureEmptyAndDotSegmentsAreRejected() {
        listOf("", ".", "..", "has/slash").forEach { segment ->
            assertThrows(IllegalArgumentException::class.java) {
                DocumentPathPolicy.requireSafeSegment(segment)
            }
        }
        listOf("", "/absolute", "trailing/", "double//slash", "dot/./file", "parent/../file").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                DocumentPathPolicy.requireSafeRelative(path)
            }
        }
    }
}
