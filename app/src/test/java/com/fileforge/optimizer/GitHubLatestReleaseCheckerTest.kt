package com.fileforge.optimizer

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubLatestReleaseCheckerTest {
    @Test
    fun semanticVersionsAcceptVPrefixAndApplySemVerPrereleasePrecedence() {
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("v1.2.3"))
        assertEquals(
            SemanticVersion(1, 2, 3, listOf("beta", "2")),
            SemanticVersion.parse("1.2.3-beta.2+build.7")
        )
        assertTrue(SemanticVersion.parse("1.0.0")!! > SemanticVersion.parse("1.0.0-rc.1")!!)
        assertTrue(SemanticVersion.parse("1.0.0-beta.11")!! > SemanticVersion.parse("1.0.0-beta.2")!!)
        assertTrue(SemanticVersion.parse("1.0.0-beta.2")!! > SemanticVersion.parse("1.0.0-beta.alpha")!!)
        assertNull(SemanticVersion.parse("1.2"))
        assertNull(SemanticVersion.parse("release-1.2.3"))
        assertNull(SemanticVersion.parse("1.0.0-01"))
    }

    @Test
    fun requestUsesOnlyTheExactAnonymousBoundedGitHubContract() {
        lateinit var request: UpdateHttpRequest
        val transport = UpdateHttpTransport { captured ->
            request = captured
            response(200, releaseJson("v1.0.0"))
        }

        GitHubLatestReleaseChecker("1.0.0", ReleaseAssetKind.STANDARD, transport).check()

        assertEquals(
            "https://api.github.com/repos/Zfkirke0109/FileForgeOptimizer/releases/latest",
            request.url
        )
        assertEquals(10_000, request.connectTimeoutMillis)
        assertEquals(10_000, request.readTimeoutMillis)
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertEquals("2026-03-10", request.headers["X-GitHub-Api-Version"])
        assertTrue(request.headers.getValue("User-Agent").contains("FileForgeOptimizer"))
        assertFalse(request.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertEquals(1_048_576, request.maxResponseBytes)
    }

    @Test
    fun mapsCurrentAvailableNoReleaseRateLimitOfflineAndInvalidResponses() {
        assertTrue(check("1.2.3", 200, releaseJson("v1.2.3")) is UpdateCheckResult.Current)
        assertTrue(check("1.2.4", 200, releaseJson("v1.2.3")) is UpdateCheckResult.Current)
        assertTrue(check("1.2.2", 200, releaseJson("v1.2.3")) is UpdateCheckResult.Available)
        assertEquals(UpdateCheckResult.NoRelease, check("1.2.3", 404, "{}"))
        assertEquals(UpdateCheckResult.RateLimited, check("1.2.3", 403, "{}"))
        assertEquals(UpdateCheckResult.RateLimited, check("1.2.3", 429, "{}"))
        assertEquals(UpdateCheckResult.Invalid, check("1.2.3", 200, "not json"))
        assertEquals(UpdateCheckResult.Invalid, check("not-semver", 200, releaseJson("v1.2.3")))

        val offline = GitHubLatestReleaseChecker(
            "1.2.3",
            ReleaseAssetKind.STANDARD,
            UpdateHttpTransport { throw IOException("network unavailable") }
        ).check()
        assertEquals(UpdateCheckResult.Offline, offline)
    }

    @Test
    fun refusesOversizedOrInsecureReleaseDataAndAlwaysClosesTheResponse() {
        val oversized = ByteArray(1_048_577) { 'x'.code.toByte() }
        val oversizedResponse = TrackingResponse(200, oversized)
        val oversizedResult = GitHubLatestReleaseChecker(
            "1.0.0",
            ReleaseAssetKind.STANDARD,
            UpdateHttpTransport { oversizedResponse }
        ).check()
        assertEquals(UpdateCheckResult.Invalid, oversizedResult)
        assertTrue(oversizedResponse.closed)

        val insecure = releaseJson("v2.0.0", pageUrl = "http://github.com/example/release")
        assertEquals(UpdateCheckResult.Invalid, check("1.0.0", 200, insecure))
    }

    @Test
    fun availableReleaseSelectsOnlyTheRequestedVariantAssetWithoutDownloadingIt() {
        val json = releaseJson(
            "v2.0.0",
            assets = """
                [
                  {"name":"FileForgeOptimizer-standard.apk","browser_download_url":"https://github.com/Zfkirke0109/FileForgeOptimizer/releases/download/v2.0.0/standard.apk"},
                  {"name":"FileForgeOptimizer-native-arm64.apk","browser_download_url":"https://github.com/Zfkirke0109/FileForgeOptimizer/releases/download/v2.0.0/native-arm64.apk"}
                ]
            """.trimIndent()
        )

        val standard = check("1.0.0", 200, json, ReleaseAssetKind.STANDARD) as UpdateCheckResult.Available
        val native = check("1.0.0", 200, json, ReleaseAssetKind.NATIVE_ARM64) as UpdateCheckResult.Available

        assertEquals("FileForgeOptimizer-standard.apk", standard.selectedAssetName)
        assertEquals("FileForgeOptimizer-native-arm64.apk", native.selectedAssetName)
        assertEquals("https://github.com/Zfkirke0109/FileForgeOptimizer/releases/tag/v2.0.0", standard.releasePageUrl)
    }

    private fun check(
        installed: String,
        status: Int,
        body: String,
        kind: ReleaseAssetKind = ReleaseAssetKind.STANDARD
    ): UpdateCheckResult = GitHubLatestReleaseChecker(
        installed,
        kind,
        UpdateHttpTransport { response(status, body) }
    ).check()

    private fun response(status: Int, body: String): UpdateHttpResponse =
        UpdateHttpResponse(status, ByteArrayInputStream(body.toByteArray()))

    private fun releaseJson(
        tag: String,
        pageUrl: String = "https://github.com/Zfkirke0109/FileForgeOptimizer/releases/tag/$tag",
        assets: String = "[]"
    ): String = """
        {"tag_name":"$tag","html_url":"$pageUrl","assets":$assets}
    """.trimIndent()

    private class TrackingResponse(status: Int, bytes: ByteArray) :
        UpdateHttpResponse(status, ByteArrayInputStream(bytes)) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
