package com.pvzge.gardendless

import android.app.ActivityManager
import android.content.Context

enum class PerformanceMode(val targetFps: Int, val maxRenderEdge: Int, val label: Int) {
    LOW_END(30, 960, R.string.performance_low),
    BALANCED(60, 1280, R.string.performance_balanced),
    ORIGINAL(60, 0, R.string.performance_original);

    companion object {
        fun load(context: Context): PerformanceMode {
            val saved = context.getSharedPreferences("app_data", Context.MODE_PRIVATE)
                .getString("performance_mode", null)
            entries.firstOrNull { it.name == saved }?.let { return it }
            val manager = context.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            return if (manager.isLowRamDevice || memory.totalMem <= 4L * 1024 * 1024 * 1024) {
                LOW_END
            } else {
                BALANCED
            }
        }
    }
}
