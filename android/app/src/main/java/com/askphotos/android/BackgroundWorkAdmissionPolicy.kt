package com.askphotos.android

import android.content.Context
import android.os.PowerManager

data class BackgroundWorkAdmission(
    val allowed: Boolean,
    val thermalStatus: Int,
    val reason: String?,
)

object ThermalWorkAdmission {
    fun evaluate(thermalStatus: Int): BackgroundWorkAdmission {
        val paused = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        return BackgroundWorkAdmission(
            allowed = !paused,
            thermalStatus = thermalStatus,
            reason = if (paused) "thermal_status_$thermalStatus" else null,
        )
    }
}

class BackgroundWorkAdmissionPolicy(context: Context) {
    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)

    fun evaluate(): BackgroundWorkAdmission = ThermalWorkAdmission.evaluate(
        runCatching { powerManager.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE),
    )
}
