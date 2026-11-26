package com.indigo.mobileobservatory.media

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

enum class ExclusiveAccessResult {
    BUSY,
    SUCCESS,
    FAILED
}

/** Process-wide file leases plus open-descriptor detection for destructive media maintenance. */
object MediaUsageRegistry {
    const val SHARE_GRACE_MS = 15L * 60L * 1000L

    private val leases = mutableMapOf<String, Int>()
    private val locks = mutableMapOf<String, ReentrantLock>()
    private val releaseExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "media-share-lease-release").apply { isDaemon = true }
    }

    fun acquire(file: File, waitForExclusive: Boolean = true): AutoCloseable {
        val key = identity(file)
        val lock = lock(key)
        if (waitForExclusive) lock.lock()
        else check(lock.tryLock()) { "Media path is being changed." }
        try {
            return registerLease(file, key, lock)
        } finally {
            lock.unlock()
        }
    }

    internal fun acquireInterruptibly(file: File, checkActive: () -> Unit): AutoCloseable {
        val key = identity(file)
        val lock = lock(key)
        lock.lockInterruptibly()
        try {
            checkActive()
            return registerLease(file, key, lock)
        } finally {
            lock.unlock()
        }
    }

    private fun registerLease(file: File, key: String, lock: ReentrantLock): AutoCloseable {
        require(file.exists()) { "Media path is no longer available." }
        synchronized(leases) {
            leases[key] = leases.getOrDefault(key, 0) + 1
        }
        return Lease(key, lock)
    }

    fun acquire(files: Collection<File>, waitForExclusive: Boolean = true): AutoCloseable {
        val acquired = mutableListOf<AutoCloseable>()
        return try {
            files.distinctBy(::identity).sortedBy(::identity).forEach {
                acquired += acquire(it, waitForExclusive)
            }
            AutoCloseable { acquired.asReversed().forEach(AutoCloseable::close) }
        } catch (error: Throwable) {
            acquired.asReversed().forEach(AutoCloseable::close)
            throw error
        }
    }

    fun releaseAfter(lease: AutoCloseable, delayMs: Long) {
        releaseExecutor.schedule(
            { lease.close() },
            delayMs.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS
        )
    }

    fun isInUse(file: File): Boolean {
        val key = identity(file)
        val lock = lock(key)
        lock.lock()
        return try {
            hasUsage(key)
        } finally {
            lock.unlock()
        }
    }

    fun withExclusiveAccess(
        file: File,
        action: () -> Boolean
    ): ExclusiveAccessResult {
        val key = identity(file)
        val lock = lock(key)
        lock.lock()
        return try {
            when {
                hasUsage(key) -> ExclusiveAccessResult.BUSY
                action() -> ExclusiveAccessResult.SUCCESS
                else -> ExclusiveAccessResult.FAILED
            }
        } finally {
            lock.unlock()
        }
    }

    private fun hasUsage(key: String): Boolean {
        synchronized(leases) {
            if (leases.getOrDefault(key, 0) > 0) return true
        }
        val descriptors = File("/proc/self/fd").listFiles() ?: return false
        return descriptors.any { descriptor ->
            val target = runCatching {
                Files.readSymbolicLink(descriptor.toPath()).toString()
                    .removeSuffix(" (deleted)")
            }.getOrNull() ?: return@any false
            runCatching { identity(File(target)) == key }.getOrDefault(false)
        }
    }

    private fun lock(key: String): ReentrantLock =
        synchronized(locks) { locks.getOrPut(key, ::ReentrantLock) }

    private fun identity(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private class Lease(
        private val key: String,
        private val lock: ReentrantLock
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            lock.lock()
            try {
                synchronized(leases) {
                    val count = leases.getOrDefault(key, 0)
                    if (count <= 1) leases.remove(key) else leases[key] = count - 1
                }
            } finally {
                lock.unlock()
            }
        }
    }
}
