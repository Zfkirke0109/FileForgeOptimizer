package com.fileforge.optimizer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OptimizationServiceTest {
    @Test
    fun serviceAndBinderExposeStableLocalContract() {
        assertEquals(OptimizationServiceContract.ACTION_START, OptimizationService.ACTION_START)
        assertEquals(OptimizationServiceContract.ACTION_RESTORE, OptimizationService.ACTION_RESTORE)
        assertEquals(OptimizationServiceContract.ACTION_CANCEL, OptimizationService.ACTION_CANCEL)
        assertEquals(OptimizationServiceContract.EXTRA_TREE_URI, OptimizationService.EXTRA_TREE_URI)
        assertEquals(OptimizationServiceContract.EXTRA_RUN_INTENT, OptimizationService.EXTRA_RUN_INTENT)
        assertEquals(OptimizationServiceContract.EXTRA_UNDO_LOG_ID, OptimizationService.EXTRA_UNDO_LOG_ID)
        assertEquals(
            OptimizationServiceContract.EXTRA_RESTORE_SELECTION,
            OptimizationService.EXTRA_RESTORE_SELECTION
        )
        assertTrue(Binder::class.java.isAssignableFrom(OptimizationBinder::class.java))
        val methods = OptimizationBinder::class.java.methods.map { it.name }.toSet()
        assertTrue("getCurrentState" in methods)
        assertTrue("addListener" in methods)
        assertTrue("removeListener" in methods)
    }

    @Test
    @SdkSuppress(minSdkVersion = 35)
    fun manifestDeclaresNonExportedMediaProcessingServiceAndRequiredPermissions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requestedPermissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.FOREGROUND_SERVICE in requestedPermissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING in requestedPermissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in requestedPermissions)
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, OptimizationService::class.java),
            PackageManager.GET_META_DATA
        )
        assertFalse(serviceInfo.exported)
        assertTrue(
            serviceInfo.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING != 0
        )
    }

    @Test
    @SdkSuppress(minSdkVersion = 35)
    fun serviceDeclaresAndroid15TimeoutCallback() {
        val timeout = OptimizationService::class.java.getDeclaredMethod(
            "onTimeout",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )

        assertNotNull(timeout)
        assertEquals(Void.TYPE, timeout.returnType)
    }
}
