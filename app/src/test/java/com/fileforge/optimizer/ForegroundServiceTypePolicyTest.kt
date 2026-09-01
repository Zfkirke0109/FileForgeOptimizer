package com.fileforge.optimizer

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceTypePolicyTest {
    @Test
    fun dataSyncIsUsedForAllSdkVersions() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            foregroundServiceType()
        )
    }
}
