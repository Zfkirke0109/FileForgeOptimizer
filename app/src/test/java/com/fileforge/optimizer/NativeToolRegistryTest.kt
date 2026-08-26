package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NativeToolRegistryTest {
    @Test
    fun standardManifestExposesNoNativeTools() = withNativeDirectory { directory ->
        val manifest = NativeToolManifest.parse("""{"schemaVersion":1,"abi":null,"tools":[]}""")

        val registry = NativeToolRegistry(manifest, directory)

        assertTrue(NativeToolId.entries.none(registry::available))
    }

    @Test
    fun parserRejectsUnknownDuplicateAndUnsafeExecutableInventory() {
        val valid = descriptorJson("qpdf", "libfileforge_qpdf.so", "Apache-2.0")
        listOf(
            manifestJson(valid.replace("\"qpdf\"", "\"unknown\"")),
            manifestJson("$valid,$valid"),
            manifestJson(descriptorJson("qpdf", "../libfileforge_qpdf.so", "Apache-2.0")),
            manifestJson(descriptorJson("qpdf", "libfileforge_qpdf.so", "GPL-3.0-only"))
        ).forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { NativeToolManifest.parse(json) }
        }
    }

    @Test
    fun registryRejectsMissingExecutablesAndResolvesCommandsBelowNativeLibraryDirectory() =
        withNativeDirectory { directory ->
            val manifest = NativeToolManifest.parse(manifestJson(qpdfDescriptor))
            assertThrows(IllegalArgumentException::class.java) { NativeToolRegistry(manifest, directory) }
            executable(directory, "libfileforge_qpdf.so")

            val registry = NativeToolRegistry(manifest, directory)
            val command = registry.commandFor(
                NativeToolId.QPDF,
                File(directory.parentFile, "input.pdf"),
                File(directory.parentFile, "output.pdf"),
                OptimizeMode.SAFE
            )

            assertEquals(directory.canonicalFile, File(command.first()).canonicalFile.parentFile)
            assertFalse(command.take(2).any { it.endsWith("/sh") || it == "-c" })
        }

    @Test
    fun qpdfSafePolicyIsLosslessAndUsesSeparateOutput() = withRegistry("qpdf", qpdfDescriptor) { registry, root ->
        val input = File(root, "input with spaces.pdf")
        val output = File(root, "output.pdf")

        val command = registry.commandFor(NativeToolId.QPDF, input, output, OptimizeMode.SAFE)

        assertEquals(input.absolutePath, command[command.lastIndex - 1])
        assertEquals(output.absolutePath, command.last())
        assertTrue("--stream-data=compress" in command)
        assertTrue("--recompress-flate" in command)
        assertFalse(command.any { it.contains("--jpeg-quality") })
    }

    @Test
    fun jpegPolicyPreservesRenderingMetadataInEveryMode() =
        withRegistry("jpegtran", descriptorJson("jpegtran", "libfileforge_jpegtran.so", "IJG-AND-BSD-3-Clause")) { registry, root ->
            val input = File(root, "input.jpg")
            val output = File(root, "output.jpg")

            val safe = registry.commandFor(NativeToolId.JPEGTRAN, input, output, OptimizeMode.SAFE)
            val aggressive = registry.commandFor(NativeToolId.JPEGTRAN, input, output, OptimizeMode.AGGRESSIVE)

            assertTrue("all" in safe)
            assertTrue("all" in aggressive)
            assertEquals(safe, aggressive)
        }

    private fun withRegistry(id: String, descriptor: String, block: (NativeToolRegistry, File) -> Unit) =
        withNativeDirectory { directory ->
            executable(directory, "libfileforge_${id}.so")
            block(
                NativeToolRegistry(NativeToolManifest.parse(manifestJson(descriptor)), directory),
                requireNotNull(directory.parentFile)
            )
        }

    private fun withNativeDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("fileforge-native-registry").toFile()
        val directory = File(root, "lib/arm64").apply { mkdirs() }
        try {
            block(directory)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun executable(directory: File, name: String) {
        File(directory, name).apply {
            writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            assertTrue(setExecutable(true, true))
        }
    }

    private fun manifestJson(tools: String): String =
        """{"schemaVersion":1,"abi":"arm64-v8a","tools":[$tools]}"""

    private fun descriptorJson(id: String, executable: String, license: String): String =
        """{"id":"$id","version":"1.0.0","executableName":"$executable","licenseId":"$license","sourceUrl":"https://example.com/$id","resolvedCommit":"${"a".repeat(40)}"}"""

    private val qpdfDescriptor = descriptorJson("qpdf", "libfileforge_qpdf.so", "Apache-2.0")
}
