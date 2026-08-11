package io.github.anup42.askalbum

import android.content.ComponentName
import android.content.pm.PermissionInfo
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
        assertFalse(info.exported)
        assertEquals("${context.packageName}.permission.TEST_HARNESS", info.permission)
        assertTrue(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
    }

    @Test
    fun runIdContractRejectsTraversalBeforeServiceStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = runCatching { TestGallerySeederService.start(context, "../../personal") }
        assertTrue(result.isFailure)
    }

    @Test
    fun debugHarnessHasNoExternallyReachableComponent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val permission = "${context.packageName}.permission.TEST_HARNESS"
        val permissionInfo = packageManager.getPermissionInfo(permission, 0)
        assertEquals(
            PermissionInfo.PROTECTION_SIGNATURE,
            permissionInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE,
        )

        listOf(TestGallerySeederReceiver::class.java, TestGemmaModelReceiver::class.java).forEach { receiver ->
            val info = packageManager.getReceiverInfo(ComponentName(context, receiver), 0)
            assertFalse(info.exported)
            assertEquals(permission, info.permission)
        }
        val provider = packageManager.getProviderInfo(ComponentName(context, TestSeedContentProvider::class.java), 0)
        assertFalse(provider.exported)
        assertEquals(permission, provider.readPermission)
        assertEquals(permission, provider.writePermission)

        val activity = packageManager.getActivityInfo(ComponentName(context, TestGemmaDownloadActivity::class.java), 0)
        assertFalse(activity.exported)
        assertEquals(permission, activity.permission)
    }
}
