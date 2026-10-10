package com.example.hinglishpdf.ui.status

import kotlin.math.ceil

/**
 * Time-left estimates from the pace so far. They under-promise: the measured
 * pace is padded by a third and rounded up, so the work usually ends sooner
 * than shown, never later (free tiers slow down without warning).
 */
object Eta {

    private const val PADDING = 1.3

    /**
     * Milliseconds left, or null until there is enough to go on (two units
     * done in this run and at least 20 seconds of it).
     */
    fun remainingMillis(doneAtStart: Int, done: Int, total: Int, startedAt: Long, now: Long): Long? {
        val progressed = done - doneAtStart
        val elapsed = now - startedAt
        if (total <= 0 || done >= total) return if (total > 0 && done >= total) 0L else null
        if (progressed < 2 || elapsed < 20_000) return null
        val perUnit = elapsed.toDouble() / progressed
        return (perUnit * (total - done) * PADDING).toLong()
    }

    fun format(millis: Long?): String {
        if (millis == null) return "Estimating time…"
        val minutes = ceil(millis / 60_000.0).toLong()
        return when {
            millis <= 0 -> "Almost done"
            minutes <= 1 -> "About a minute left"
            minutes < 10 -> "About $minutes min left"
            minutes < 60 -> "About ${roundUp(minutes, 5)} min left"
            minutes < 24 * 60 -> {
                val rounded = roundUp(minutes, 15)
                val h = rounded / 60
                val m = rounded % 60
                if (m == 0L) "About $h h left" else "About $h h $m min left"
            }
            else -> "About ${ceil(minutes / (24 * 60.0)).toLong()} days left"
        }
    }

    private fun roundUp(value: Long, step: Long) = ((value + step - 1) / step) * step
}
