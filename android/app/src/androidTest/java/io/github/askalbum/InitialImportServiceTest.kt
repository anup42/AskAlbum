package io.github.anup42.askalbum

import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InitialImportServiceTest {
    @Test
    fun serviceIsPrivateDataSyncAndStartsForegroundChannel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, InitialImportService::class.java),
            0,
        )
        assertFalse(info.exported)
        val expectedType = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        assertTrue(info.foregroundServiceType and expectedType != 0)

        InitialImportService.start(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 5_000
        while (notifications.getNotificationChannel("gallery_initial_import") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertNotNull(notifications.getNotificationChannel("gallery_initial_import"))
    }
}
