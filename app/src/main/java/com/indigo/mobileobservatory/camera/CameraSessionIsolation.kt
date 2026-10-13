package com.indigo.mobileobservatory.camera

import java.util.concurrent.ConcurrentHashMap

/** Only SDK bindings with a proven process-global native camera handle require a lease. */
object CameraSessionIsolation {
    fun incompatible(first: CameraBrand?, second: CameraBrand?): Boolean = first != null && first == second &&
        first in setOf(CameraBrand.TOUPCAM, CameraBrand.QHY)
}

/** The ownership token is an adapter instance. A rejected adapter cannot close another camera. */
class CameraSdkLease {
    private val owners = ConcurrentHashMap<String, Any>()
    private val locks = ConcurrentHashMap<String, Any>()
    fun <T> withSdkLock(sdk: String, action: () -> T): T = synchronized(locks.getOrPut(sdk) { Any() }, action)
    fun isClaimed(sdk: String): Boolean = owners.containsKey(sdk)
    fun claim(sdk: String, owner: Any): Boolean = synchronized(owners) {
        val current = owners[sdk]
        if (current == null) { owners[sdk] = owner; true } else current === owner
    }
    fun owns(sdk: String, owner: Any): Boolean = owners[sdk] === owner
    fun release(sdk: String, owner: Any) { synchronized(owners) { if (owners[sdk] === owner) owners.remove(sdk) } }
    companion object { val process = CameraSdkLease() }
}
