package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildVariantTest {
    @Test
    fun generatedVariantContractMatchesTheReleaseFlavor() {
        assertEquals("com.fileforge.optimizer", BuildConfig.APPLICATION_ID)
        assertEquals(2, BuildConfig.VERSION_CODE)
        assertEquals("0.2.0", BuildConfig.VERSION_NAME)

        when (BuildConfig.FILEFORGE_VARIANT) {
            "standard" -> {
                assertEquals("standard", BuildConfig.DISTRIBUTION_VARIANT)
                assertEquals("STANDARD", BuildConfig.RELEASE_ASSET_KIND)
                assertEquals("none", BuildConfig.PACKAGED_ABI)
                assertEquals(0, BuildConfig.NATIVE_TOOL_COUNT)
            }
            "native-arm64" -> {
                assertEquals("native-arm64", BuildConfig.DISTRIBUTION_VARIANT)
                assertEquals("NATIVE_ARM64", BuildConfig.RELEASE_ASSET_KIND)
                assertEquals("arm64-v8a", BuildConfig.PACKAGED_ABI)
                assertEquals(5, BuildConfig.NATIVE_TOOL_COUNT)
            }
            else -> throw AssertionError("Unexpected release flavor: ${BuildConfig.FILEFORGE_VARIANT}")
        }
    }
}
