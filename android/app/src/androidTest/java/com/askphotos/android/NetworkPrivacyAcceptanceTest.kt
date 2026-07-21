package com.askphotos.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkPrivacyAcceptanceTest {
    @Test
    fun distributionPermissionMatchesDownloadPolicyAndNoTelemetryTransportIsRegistered() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val internetGranted = packageManager.checkPermission(Manifest.permission.INTERNET, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
        assertEquals(BuildConfig.ALLOW_MODEL_DOWNLOAD, internetGranted)

        @Suppress("DEPRECATION")
        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
        )
        val components = buildList {
            packageInfo.services?.mapTo(this) { it.name }
            packageInfo.receivers?.mapTo(this) { it.name }
            packageInfo.providers?.mapTo(this) { it.name }
        }
        val telemetryComponents = components.filter { name ->
            name.contains("datatransport", ignoreCase = true) ||
                name.contains("CctBackend", ignoreCase = true)
        }
        assertTrue("Telemetry transport components were packaged: $telemetryComponents", telemetryComponents.isEmpty())
    }
}
