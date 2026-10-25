package com.indigo.mobileobservatory.media

import android.app.ActivityManager
import android.content.Context

/** Java sample buffers and native bitmap pixels have separate admission checks (Android 8+). */
object MediaImageMemory {
    private var manager: ActivityManager? = null
    fun configure(context: Context) { manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }

    fun check(managedBytes: Long = 0, nativeBytes: Long = 0) {
        val runtime = Runtime.getRuntime()
        val heapFree = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val system = manager?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }
        require(admitted(managedBytes, nativeBytes, heapFree, system?.availMem,
            system?.threshold ?: 0)) { "memory_budget" }
    }

    internal fun admitted(managed: Long, native: Long, heapFree: Long, systemFree: Long?, reserve: Long = 0): Boolean {
        if (managed < 0 || native < 0 || managed > heapFree * .85) return false
        // A missing system reading never substitutes the ART heap limit for native memory.
        return systemFree == null || systemFree > reserve && managed + native <=
            minOf((systemFree * .65).toLong(), systemFree - reserve)
    }
}
