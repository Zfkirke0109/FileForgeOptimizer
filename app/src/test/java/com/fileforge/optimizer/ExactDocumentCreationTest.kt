package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactDocumentCreationTest {
    @Test
    fun everyPostCreationValidationFailureDeletesTheCreatedProviderDocument() {
        val requested = "FileForge_Restore_run.jsonl"
        val failures = listOf(
            DocumentNode("renamed", "$requested (1)", isDirectory = false, length = 0) to
                DocumentNode("renamed", "$requested (1)", isDirectory = false, length = 0),
            DocumentNode("wrong-type", requested, isDirectory = true, length = 0) to
                DocumentNode("wrong-type", requested, isDirectory = true, length = 0),
            DocumentNode("unreachable", requested, isDirectory = false, length = 0) to null,
            DocumentNode("wrong-id", requested, isDirectory = false, length = 0) to
                DocumentNode("different", requested, isDirectory = false, length = 0)
        )

        failures.forEach { (created, resolved) ->
            var deleted = 0

            assertThrows(Throwable::class.java) {
                requireExactCreation(
                    requestedName = requested,
                    directory = false,
                    created = created,
                    resolveCreated = { resolved },
                    deleteCreated = { deleted += 1; true }
                )
            }

            assertEquals("created=${created.id}", 1, deleted)
        }
    }

    @Test
    fun exactReachableCreationIsReturnedWithoutDeletion() {
        val created = DocumentNode("exact", "receipt.jsonl", isDirectory = false, length = 0)
        var deleted = false

        val result = requireExactCreation(
            requestedName = created.name,
            directory = false,
            created = created,
            resolveCreated = { created },
            deleteCreated = { deleted = true; true }
        )

        assertSame(created, result)
        assertTrue(!deleted)
    }

    @Test
    fun cleanupFailureIsAttachedWithoutMaskingTheValidationFailure() {
        val created = DocumentNode("renamed", "receipt (1).jsonl", isDirectory = false, length = 0)

        val failure = assertThrows(DocumentAlreadyExistsException::class.java) {
            requireExactCreation(
                requestedName = "receipt.jsonl",
                directory = false,
                created = created,
                resolveCreated = { created },
                deleteCreated = { false }
            )
        }

        assertEquals(1, failure.suppressed.size)
        assertTrue(failure.suppressed.single().message!!.contains("delete", ignoreCase = true))
    }
}
