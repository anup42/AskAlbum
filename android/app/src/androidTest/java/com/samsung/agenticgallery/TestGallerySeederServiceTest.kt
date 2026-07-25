package com.samsung.agenticgallery

import android.content.ComponentName
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestGallerySeederServiceTest {
    @Test
    fun debugSeederIsAnExplicitDataSyncForegroundService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, TestGallerySeederService::class.java),
            0,
        )
        assertTrue(info.exported)
        assertTrue(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
    }

    @Test
    fun runIdContractRejectsTraversalBeforeServiceStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = runCatching { TestGallerySeederService.start(context, "../../personal") }
        assertTrue(result.isFailure)
    }
}
