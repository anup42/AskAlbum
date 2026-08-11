package io.github.anup42.askalbum

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

    @Test
    fun unknownOrFailedThermalTelemetryPausesHeavyWork() {
        val failedStatus = ThermalWorkAdmission.readStatus { error("thermal service unavailable") }
        assertEquals(ThermalWorkAdmission.STATUS_UNKNOWN, failedStatus)

        listOf(failedStatus, Int.MAX_VALUE).forEach { status ->
            val decision = ThermalWorkAdmission.evaluate(status)
            assertFalse(decision.allowed)
            assertEquals("thermal_status_unknown", decision.reason)
            assertEquals("unknown", thermalStatusLabel(status))
        }
    }

    @Test
    fun missingBatteryTelemetryIsNotReportedAsFullBattery() {
        assertEquals(50, BatteryWorkAdmission.percentage(level = 50, scale = 100))
        assertEquals(100, BatteryWorkAdmission.percentage(level = 150, scale = 100))
        assertEquals(BatteryWorkAdmission.PERCENT_UNKNOWN, BatteryWorkAdmission.percentage(level = -1, scale = 100))
        assertEquals(BatteryWorkAdmission.PERCENT_UNKNOWN, BatteryWorkAdmission.percentage(level = 50, scale = 0))
    }
}
