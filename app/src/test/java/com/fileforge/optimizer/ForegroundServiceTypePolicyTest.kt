package com.fileforge.optimizer

import android.content.pm.ServiceInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A foreground service that starts with type none is killed by Android 14 and newer with
 * "Starting FGS with type none ... has been prohibited", which turns every optimization run into an
 * immediate terminal failure. These tests pin the three conditions that keep that from recurring.
 */
@Suppress("DEPRECATION")  // FOREGROUND_SERVICE_TYPE_NONE is the value these tests exist to reject.
class ForegroundServiceTypePolicyTest {
    @Test
    fun runtimeTypeIsDataSync() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foregroundServiceType())
        assertNotEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE, foregroundServiceType())
    }

    /**
     * `androidx.core.app.ServiceCompat.startForeground` masks the requested type against this
     * allow-list and never reports what it dropped. A type outside it becomes zero and Android
     * rejects the start. FileForge no longer routes through ServiceCompat, but staying inside the
     * allow-list keeps both call paths equivalent.
     */
    @Test
    fun runtimeTypeSurvivesTheServiceCompatMask() {
        val allowedSinceU = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        assertEquals(foregroundServiceType(), foregroundServiceType() and allowedSinceU)
    }

    @Test
    fun manifestDeclaresTheRuntimeTypeAndItsPermission() {
        val manifest = readManifest()
        val declared = declaredServiceTypes(manifest)

        assertEquals(
            "android:foregroundServiceType must cover the type passed to startForeground",
            foregroundServiceType(),
            foregroundServiceType() and declared
        )
        for (label in declaredLabels(manifest) - PERMISSIONLESS_TYPES) {
            val permission = "android.permission.FOREGROUND_SERVICE_${permissionSuffix(label)}"
            assertTrue(
                "$permission must be declared for android:foregroundServiceType=\"$label\"",
                manifest.contains("android:name=\"$permission\"")
            )
        }
        assertTrue(
            "android.permission.FOREGROUND_SERVICE must be declared",
            manifest.contains("android:name=\"android.permission.FOREGROUND_SERVICE\"")
        )
    }

    private fun declaredLabels(manifest: String): List<String> {
        val declaration = Regex("android:foregroundServiceType=\"([^\"]+)\"").find(manifest)
        checkNotNull(declaration) { "OptimizationService declares no android:foregroundServiceType" }
        return declaration.groupValues[1].split('|').map(String::trim).filter(String::isNotEmpty)
    }

    private fun declaredServiceTypes(manifest: String): Int =
        declaredLabels(manifest).fold(ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) { types, label ->
            val flag = checkNotNull(MANIFEST_TYPE_FLAGS[label]) {
                "Unrecognized android:foregroundServiceType value \"$label\""
            }
            types or flag
        }

    private fun permissionSuffix(label: String): String =
        label.replace(Regex("(?<=.)([A-Z])"), "_$1").uppercase()

    private fun readManifest(): String {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            for (relative in MANIFEST_LOCATIONS) {
                val candidate = File(directory, relative)
                if (candidate.isFile) return candidate.readText()
            }
            directory = directory.parentFile
        }
        throw IllegalStateException(
            "AndroidManifest.xml not found above ${System.getProperty("user.dir")}"
        )
    }

    private companion object {
        /** Short services are the one type Android grants without a dedicated permission. */
        val PERMISSIONLESS_TYPES = setOf("shortService")

        val MANIFEST_LOCATIONS = listOf(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml"
        )

        val MANIFEST_TYPE_FLAGS = mapOf(
            "dataSync" to ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            "mediaPlayback" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            "phoneCall" to ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            "location" to ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            "connectedDevice" to ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            "mediaProjection" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            "camera" to ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            "microphone" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            "health" to ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
            "remoteMessaging" to ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            "systemExempted" to ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            "shortService" to ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            "mediaProcessing" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            "specialUse" to ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }
}
