package com.fileforge.optimizer

data class AboutBuildMetadata(
    val distribution: String,
    val releaseAssetKind: ReleaseAssetKind,
    val packagedAbi: String
) {
    companion object {
        fun fromGeneratedValues(
            distribution: String,
            releaseAssetKind: String,
            packagedAbi: String
        ): AboutBuildMetadata {
            require(distribution.isNotBlank()) { "Distribution variant must be generated" }
            require(packagedAbi.isNotBlank()) { "Packaged ABI must be generated" }
            val parsedAssetKind = ReleaseAssetKind.entries.singleOrNull {
                it.name == releaseAssetKind
            } ?: throw IllegalArgumentException(
                "Unsupported generated release asset kind: $releaseAssetKind"
            )
            return AboutBuildMetadata(distribution, parsedAssetKind, packagedAbi)
        }
    }
}
