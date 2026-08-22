package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutBuildMetadataTest {
    @Test
    fun generatedStandardMetadataIsHonestAndAbiIndependent() {
        val metadata = AboutBuildMetadata.fromGeneratedValues(
            distribution = "standard",
            releaseAssetKind = "STANDARD",
            packagedAbi = "none"
        )

        assertEquals("standard", metadata.distribution)
        assertEquals(ReleaseAssetKind.STANDARD, metadata.releaseAssetKind)
        assertEquals("none", metadata.packagedAbi)
    }

    @Test
    fun taskCNativeArm64ValuesSelectTheNativeReleaseAssetWithoutUsingDeviceAbi() {
        val metadata = AboutBuildMetadata.fromGeneratedValues(
            distribution = "native-arm64",
            releaseAssetKind = "NATIVE_ARM64",
            packagedAbi = "arm64-v8a"
        )

        assertEquals("native-arm64", metadata.distribution)
        assertEquals(ReleaseAssetKind.NATIVE_ARM64, metadata.releaseAssetKind)
        assertEquals("arm64-v8a", metadata.packagedAbi)
    }
}
