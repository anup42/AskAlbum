package com.askphotos.android

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundWorkAdmissionPolicyTest {
    @Test
    fun permitsCoolAndLightStates() {
        listOf(PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT).forEach { status ->
            val decision = ThermalWorkAdmission.evaluate(status)
            assertTrue(decision.allowed)
            assertNull(decision.reason)
            assertEquals(status, decision.thermalStatus)
        }
    }

    @Test
    fun pausesEveryModerateOrHotterState() {
        listOf(
            PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
        ).forEach { status ->
            val decision = ThermalWorkAdmission.evaluate(status)
            assertFalse(decision.allowed)
            assertEquals("thermal_status_$status", decision.reason)
        }
    }
}
