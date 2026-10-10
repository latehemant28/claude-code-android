package com.example.hinglishpdf.data.llm

import android.content.Context

/** Small persistent preferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * Off by default: inference runs on the GPU only. Turning this on lets a
     * phone whose GPU cannot run the model fall back to the (much slower) CPU.
     */
    var allowCpu: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_CPU, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_CPU, value).apply()

    private companion object {
        const val KEY_ALLOW_CPU = "allow_cpu"
    }
}
