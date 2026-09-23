package com.fileforge.optimizer

import org.json.JSONException
import org.json.JSONObject
import java.net.URI

enum class NativeToolId(val manifestId: String) {
    QPDF("qpdf"),
    OXIPNG("oxipng"),
    JPEGTRAN("jpegtran"),
    ZOPFLIPNG("zopflipng"),
    ZIPALIGN("zipalign");

    companion object {
        fun fromManifestId(value: String): NativeToolId = entries.firstOrNull { it.manifestId == value }
            ?: throw IllegalArgumentException("Unknown native tool id: $value")
    }
}

data class NativeToolDescriptor(
    val id: NativeToolId,
    val version: String,
    val executableName: String,
    val licenseId: String,
    val sourceUrl: String,
    val resolvedCommit: String
)

data class NativeToolManifest(
    val schemaVersion: Int,
    val abi: String?,
    val tools: List<NativeToolDescriptor>
) {
    companion object {
        fun parse(json: String): NativeToolManifest = try {
            val root = JSONObject(json)
            root.requireExactKeys(ROOT_KEYS)
            require(root.strictInt("schemaVersion") == SCHEMA_VERSION) {
                "Unsupported native tool manifest schema"
            }
            val abi = when (val rawAbi = root.opt("abi")) {
                JSONObject.NULL -> null
                is String -> rawAbi.also { require(it == NATIVE_ABI) { "Unsupported native ABI: $it" } }
                else -> throw IllegalArgumentException("Manifest ABI must be a string or null")
            }
            val array = root.optJSONArray("tools")
                ?: throw IllegalArgumentException("Manifest tools must be an array")
            val descriptors = ArrayList<NativeToolDescriptor>(array.length())
            val ids = mutableSetOf<NativeToolId>()
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index)
                    ?: throw IllegalArgumentException("Native tool entry $index must be an object")
                item.requireExactKeys(TOOL_KEYS)
                val id = NativeToolId.fromManifestId(item.strictString("id"))
                require(ids.add(id)) { "Duplicate native tool id: ${id.manifestId}" }
                val executableName = item.strictString("executableName")
                require(executableName == "libfileforge_${id.manifestId}.so" && SAFE_EXECUTABLE.matches(executableName)) {
                    "Unsafe or unexpected executable name for ${id.manifestId}"
                }
                val license = item.strictString("licenseId")
                require(license == EXPECTED_LICENSES.getValue(id)) {
                    "Unapproved license for ${id.manifestId}: $license"
                }
                val sourceUrl = item.strictString("sourceUrl")
                val sourceUri = URI(sourceUrl)
                require(sourceUri.scheme == "https" && !sourceUri.host.isNullOrBlank() && sourceUri.userInfo == null) {
                    "Native source URL must be an unauthenticated HTTPS URL"
                }
                val version = item.strictString("version")
                require(version.isNotBlank() && version.length <= 64) { "Invalid native tool version" }
                val commit = item.strictString("resolvedCommit")
                require(COMMIT.matches(commit)) { "Resolved commit must be a full hexadecimal object id" }
                descriptors += NativeToolDescriptor(id, version, executableName, license, sourceUrl, commit.lowercase())
            }
            require((abi == null) == descriptors.isEmpty()) {
                "Only the standard manifest may omit an ABI and it must contain no tools"
            }
            NativeToolManifest(SCHEMA_VERSION, abi, descriptors.toList())
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: JSONException) {
            throw IllegalArgumentException("Malformed native tool manifest", failure)
        }

        const val SCHEMA_VERSION = 1
        const val NATIVE_ABI = "arm64-v8a"
        private val ROOT_KEYS = setOf("schemaVersion", "abi", "tools")
        private val TOOL_KEYS = setOf(
            "id", "version", "executableName", "licenseId", "sourceUrl", "resolvedCommit"
        )
        private val SAFE_EXECUTABLE = Regex("libfileforge_[a-z0-9]+\\.so")
        private val COMMIT = Regex("[0-9a-fA-F]{40}")
        private val EXPECTED_LICENSES = mapOf(
            NativeToolId.QPDF to "Apache-2.0",
            NativeToolId.OXIPNG to "MIT",
            NativeToolId.JPEGTRAN to "IJG-AND-BSD-3-Clause",
            NativeToolId.ZOPFLIPNG to "Apache-2.0",
            NativeToolId.ZIPALIGN to "Apache-2.0"
        )
    }
}

private fun JSONObject.requireExactKeys(expected: Set<String>) {
    val actual = keys().asSequence().toSet()
    require(actual == expected) { "Unexpected manifest fields: ${(actual - expected).sorted()}" }
}

private fun JSONObject.strictString(name: String): String = opt(name) as? String
    ?: throw IllegalArgumentException("Manifest field $name must be a string")

private fun JSONObject.strictInt(name: String): Int = when (val value = opt(name)) {
    is Int -> value
    else -> throw IllegalArgumentException("Manifest field $name must be an integer")
}
