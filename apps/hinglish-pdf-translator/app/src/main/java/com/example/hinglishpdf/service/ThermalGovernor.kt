package com.example.hinglishpdf.service

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.delay

/**
 * Keeps a long translation from cooking the phone. A 3B model keeps the
 * CPU/GPU at full load; without breaks the SoC throttles (everything gets
 * slower anyway) and the battery drains faster.
 *
 *  - Every [pagesPerRest] pages: a short rest of [restMillis].
 *  - Phone reports MODERATE heat: a longer rest after every page.
 *  - SEVERE or worse: wait until it cools back down (up to [maxCoolDownMillis]).
 */
class ThermalGovernor(
    context: Context,
    private val pagesPerRest: Int = 5,
    private val restMillis: Long = 8_000,
    private val warmRestMillis: Long = 20_000,
    private val maxCoolDownMillis: Long = 10 * 60_000,
) {
    private val power = context.getSystemService(PowerManager::class.java)

    suspend fun afterPage(pagesDone: Int, onRest: (String) -> Unit) {
        when {
            thermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE -> {
                var waited = 0L
                while (thermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE && waited < maxCoolDownMillis) {
                    onRest("Phone is hot: cooling down before the next page…")
                    delay(COOL_DOWN_STEP_MILLIS)
                    waited += COOL_DOWN_STEP_MILLIS
                }
            }
            thermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE -> {
                onRest("Phone is warm: short pause…")
                delay(warmRestMillis)
            }
            pagesDone > 0 && pagesDone % pagesPerRest == 0 -> {
                onRest("Cooling pause after $pagesPerRest pages…")
                delay(restMillis)
            }
        }
    }

    private fun thermalStatus(): Int = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE

    private companion object {
        const val COOL_DOWN_STEP_MILLIS = 30_000L
    }
}
