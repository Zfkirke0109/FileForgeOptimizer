package com.fileforge.optimizer

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

data class SemanticVersion(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger,
    val prerelease: List<String> = emptyList()
) : Comparable<SemanticVersion> {
    constructor(
        major: Int,
        minor: Int,
        patch: Int,
        prerelease: List<String> = emptyList()
    ) : this(
        BigInteger.valueOf(major.toLong()),
        BigInteger.valueOf(minor.toLong()),
        BigInteger.valueOf(patch.toLong()),
        prerelease
    )

    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isNotEmpty() -> 1
                prerelease.isNotEmpty() && other.prerelease.isEmpty() -> -1
                else -> 0
            }
        }
        val shared = minOf(prerelease.size, other.prerelease.size)
        repeat(shared) { index ->
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftIsNumber = left.all(Char::isDigit)
            val rightIsNumber = right.all(Char::isDigit)
            val comparison = when {
                leftIsNumber && rightIsNumber -> BigInteger(left).compareTo(BigInteger(right))
                leftIsNumber -> -1
                rightIsNumber -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return compareValues(prerelease.size, other.prerelease.size)
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (prerelease.isNotEmpty()) append('-').append(prerelease.joinToString("."))
    }

    companion object {
        private val versionPattern = Regex(
            "^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
        )

        fun parse(value: String): SemanticVersion? {
            val match = versionPattern.matchEntire(value) ?: return null
            val prerelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                .orEmpty()
            if (prerelease.any { identifier ->
                    identifier.all(Char::isDigit) && identifier.length > 1 && identifier.startsWith('0')
                }
            ) return null
            return SemanticVersion(
                match.groupValues[1].toBigInteger(),
                match.groupValues[2].toBigInteger(),
                match.groupValues[3].toBigInteger(),
                prerelease
            )
        }
    }
}

enum class ReleaseAssetKind { STANDARD, NATIVE_ARM64 }

sealed interface UpdateCheckResult {
    data class Current(val latestVersion: SemanticVersion) : UpdateCheckResult

    data class Available(
        val latestVersion: SemanticVersion,
        val releasePageUrl: String,
        val selectedAssetName: String?,
        val tagName: String = latestVersion.toString()
    ) : UpdateCheckResult

    data object NoRelease : UpdateCheckResult
    data object Offline : UpdateCheckResult
    data object RateLimited : UpdateCheckResult
    data object Invalid : UpdateCheckResult
}

data class UpdateHttpRequest(
    val url: String,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val headers: Map<String, String>,
    val maxResponseBytes: Int
)

open class UpdateHttpResponse(
    val statusCode: Int,
    val body: InputStream
) : Closeable {
    open override fun close() {
        body.close()
    }
}

fun interface UpdateHttpTransport {
    @Throws(IOException::class)
    fun execute(request: UpdateHttpRequest): UpdateHttpResponse
}

fun interface LatestReleaseChecker {
    fun check(): UpdateCheckResult
}

class GitHubLatestReleaseChecker(
    private val installedVersionName: String,
    private val assetKind: ReleaseAssetKind,
    private val transport: UpdateHttpTransport
) : LatestReleaseChecker, Closeable {
    override fun check(): UpdateCheckResult {
        val installedVersion = SemanticVersion.parse(installedVersionName)
            ?: return UpdateCheckResult.Invalid
        return try {
            transport.execute(request(installedVersionName)).use { response ->
                when (response.statusCode) {
                    200 -> parseRelease(response.body, installedVersion)
                    404 -> UpdateCheckResult.NoRelease
                    403, 429 -> UpdateCheckResult.RateLimited
                    else -> UpdateCheckResult.Invalid
                }
            }
        } catch (_: IOException) {
            UpdateCheckResult.Offline
        } catch (_: RuntimeException) {
            UpdateCheckResult.Invalid
        }
    }

    override fun close() {
        (transport as? Closeable)?.close()
    }

    private fun parseRelease(body: InputStream, installed: SemanticVersion): UpdateCheckResult {
        val bytes = readBounded(body, MAX_RESPONSE_BYTES) ?: return UpdateCheckResult.Invalid
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val tagName = json.getString("tag_name")
        val latest = SemanticVersion.parse(tagName)
            ?: return UpdateCheckResult.Invalid
        val releasePageUrl = json.getString("html_url")
        if (!isHttps(releasePageUrl)) return UpdateCheckResult.Invalid
        if (latest <= installed) return UpdateCheckResult.Current(latest)

        val assets = json.optJSONArray("assets")
        var selectedAssetName: String? = null
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val name = assets.optJSONObject(index)?.optString("name").orEmpty()
                if (matchesAsset(name, assetKind)) {
                    selectedAssetName = name
                    break
                }
            }
        }
        return UpdateCheckResult.Available(latest, releasePageUrl, selectedAssetName, tagName)
    }

    private fun matchesAsset(name: String, kind: ReleaseAssetKind): Boolean {
        val normalized = name.lowercase()
        if (!normalized.endsWith(".apk")) return false
        return when (kind) {
            ReleaseAssetKind.STANDARD -> normalized.contains("standard")
            ReleaseAssetKind.NATIVE_ARM64 -> normalized.contains("native-arm64")
        }
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray? {
        val output = java.io.ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            if (total > limit) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isHttps(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.isAbsolute &&
            uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.path.startsWith("/Zfkirke0109/FileForgeOptimizer/releases/")
    }.getOrDefault(false)

    companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Zfkirke0109/FileForgeOptimizer/releases/latest"
        const val MAX_RESPONSE_BYTES = 1_048_576
        private const val TIMEOUT_MILLIS = 10_000
        private const val API_VERSION = "2026-03-10"

        private fun request(installedVersionName: String): UpdateHttpRequest = UpdateHttpRequest(
            url = LATEST_RELEASE_URL,
            connectTimeoutMillis = TIMEOUT_MILLIS,
            readTimeoutMillis = TIMEOUT_MILLIS,
            headers = linkedMapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to API_VERSION,
                "User-Agent" to "FileForgeOptimizer/$installedVersionName Android manual-update-check"
            ),
            maxResponseBytes = MAX_RESPONSE_BYTES
        )
    }
}

class HttpUrlConnectionUpdateTransport : UpdateHttpTransport, Closeable {
    private val activeConnection = AtomicReference<HttpURLConnection?>()

    override fun execute(request: UpdateHttpRequest): UpdateHttpResponse {
        require(request.url.startsWith("https://"))
        val connection = URL(request.url).openConnection() as HttpURLConnection
        check(activeConnection.compareAndSet(null, connection)) { "An update request is already active" }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = request.connectTimeoutMillis
            connection.readTimeout = request.readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val stream = if (status in 200..399) {
                connection.inputStream
            } else {
                connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
            }
            return object : UpdateHttpResponse(status, stream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        activeConnection.compareAndSet(connection, null)
                        connection.disconnect()
                    }
                }
            }
        } catch (failure: Exception) {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
            throw failure
        }
    }

    override fun close() {
        activeConnection.getAndSet(null)?.disconnect()
    }
}
