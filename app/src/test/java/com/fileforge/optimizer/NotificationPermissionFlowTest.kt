package com.fileforge.optimizer

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionFlowTest {
    @Test
    fun firstAndroid13RunRequestsNotificationPermissionImmediatelyBeforeStarting() {
        assertEquals(
            NotificationPermissionStep.REQUEST_PERMISSION,
            NotificationPermissionFlow.beforeRun(
                sdkInt = 33,
                permissionGranted = false,
                permissionPreviouslyRequested = false
            )
        )
    }

    @Test
    fun deniedPermissionExplainsReducedVisibilityButStillStartsRun() {
        assertEquals(
            NotificationPermissionStep.RUN_WITH_REDUCED_VISIBILITY_EXPLANATION,
            NotificationPermissionFlow.afterPermissionResult(granted = false)
        )
        assertEquals(
            NotificationPermissionStep.RUN_WITH_REDUCED_VISIBILITY_EXPLANATION,
            NotificationPermissionFlow.beforeRun(
                sdkInt = 35,
                permissionGranted = false,
                permissionPreviouslyRequested = true
            )
        )
    }

    @Test
    fun preAndroid13AndGrantedPermissionProceedWithoutPrompt() {
        assertEquals(
            NotificationPermissionStep.RUN,
            NotificationPermissionFlow.beforeRun(
                sdkInt = 32,
                permissionGranted = false,
                permissionPreviouslyRequested = false
            )
        )
        assertEquals(
            NotificationPermissionStep.RUN,
            NotificationPermissionFlow.beforeRun(
                sdkInt = 35,
                permissionGranted = true,
                permissionPreviouslyRequested = false
            )
        )
    }
}
