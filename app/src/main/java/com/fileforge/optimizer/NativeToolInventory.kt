package com.fileforge.optimizer

data class NativeToolAvailability(
    val name: String,
    val available: Boolean,
    val detail: String,
    val licenseNotice: String
)

fun interface NativeToolInventory {
    fun snapshot(): List<NativeToolAvailability>
}

object UnavailableNativeToolInventory : NativeToolInventory {
    override fun snapshot(): List<NativeToolAvailability> =
        NativeToolId.entries.map { tool ->
            NativeToolAvailability(
                tool.manifestId,
                available = false,
                detail = "Unavailable in this build",
                licenseNotice = "Not bundled"
            )
        }
}
