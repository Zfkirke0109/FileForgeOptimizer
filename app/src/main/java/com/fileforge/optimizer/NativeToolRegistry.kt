package com.fileforge.optimizer

import android.content.Context
import java.io.File

class NativeToolRegistry(
    val manifest: NativeToolManifest,
    nativeLibraryDirectory: File
) : NativeToolInventory {
    private val nativeDirectory = nativeLibraryDirectory.canonicalFile
    private val executables: Map<NativeToolId, File>

    init {
        require(manifest.abi == null || manifest.abi == NativeToolManifest.NATIVE_ABI) {
            "Unsupported native tool ABI"
        }
        executables = manifest.tools.associate { descriptor ->
            val executable = File(nativeDirectory, descriptor.executableName)
            require(executable.canonicalFile.parentFile == nativeDirectory) {
                "Native executable escapes the application native library directory"
            }
            require(executable.isFile && executable.canExecute()) {
                "Native executable is missing or not executable: ${descriptor.executableName}"
            }
            descriptor.id to executable.canonicalFile
        }
    }

    fun available(id: NativeToolId): Boolean = id in executables

    fun descriptor(id: NativeToolId): NativeToolDescriptor? = manifest.tools.firstOrNull { it.id == id }

    fun commandFor(
        id: NativeToolId,
        input: File,
        output: File,
        mode: OptimizeMode
    ): List<String> {
        require(input.isAbsolute && output.isAbsolute) { "Native input and output paths must be absolute" }
        require(input.canonicalFile != output.canonicalFile) { "Native input and output must be separate files" }
        val executable = executables[id]
            ?: throw IllegalStateException("Native tool is unavailable in this build: ${id.manifestId}")
        val inputPath = input.absolutePath
        val outputPath = output.absolutePath
        return when (id) {
            NativeToolId.QPDF -> listOf(
                executable.absolutePath,
                "--stream-data=compress",
                "--object-streams=generate",
                "--recompress-flate",
                "--compression-level=9",
                inputPath,
                outputPath
            )
            NativeToolId.OXIPNG -> listOf(
                executable.absolutePath,
                "-o", "4", "--preserve", "--out", outputPath, inputPath
            )
            NativeToolId.JPEGTRAN -> listOf(
                executable.absolutePath,
                "-copy", if (mode == OptimizeMode.AGGRESSIVE) "none" else "all",
                "-optimize", "-progressive", "-outfile", outputPath, inputPath
            )
            NativeToolId.ZOPFLIPNG -> listOf(
                executable.absolutePath,
                "--keepchunks=gAMA,cHRM,sRGB,iCCP,pHYs,tEXt,zTXt,iTXt",
                inputPath,
                outputPath
            )
            NativeToolId.ZIPALIGN -> listOf(
                executable.absolutePath,
                "-f", "-P", "16", "4", inputPath, outputPath
            )
        }
    }

    override fun snapshot(): List<NativeToolAvailability> = NativeToolId.entries.map { id ->
        val descriptor = descriptor(id)
        NativeToolAvailability(
            name = id.manifestId,
            available = available(id),
            detail = descriptor?.let { "${it.version} (${manifest.abi})" } ?: "Unavailable in this build",
            licenseNotice = descriptor?.licenseId ?: "Not bundled"
        )
    }

    companion object {
        fun load(context: Context): NativeToolRegistry {
            val manifest = context.assets.open("native-tools.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            return NativeToolRegistry(
                NativeToolManifest.parse(manifest),
                File(context.applicationInfo.nativeLibraryDir)
            )
        }
    }
}

object NativeToolRuntime {
    fun registryOrNull(context: Context): NativeToolRegistry? = try {
        NativeToolRegistry.load(context)
    } catch (_: Exception) {
        null
    }

    fun executorOrNull(context: Context): NativeToolExecutor? = registryOrNull(context)
        ?.takeIf { it.manifest.tools.isNotEmpty() }
        ?.let(::NativeToolRunner)

    fun inventory(context: Context): NativeToolInventory = registryOrNull(context)
        ?: UnavailableNativeToolInventory
}
