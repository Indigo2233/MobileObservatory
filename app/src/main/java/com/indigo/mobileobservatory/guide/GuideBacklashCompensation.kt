package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.mount.MountDirection

/** Direction changes are committed only after the mount accepts the command. */
class GuideBacklashCompensation {
    private var lastDirection: MountDirection? = null
    fun reset() { lastDirection = null }
    fun accepted(direction: MountDirection) {
        require(direction == MountDirection.NORTH || direction == MountDirection.SOUTH)
        lastDirection = direction
    }
    fun pulse(baseMs: Int, direction: MountDirection?, backlashMs: Int, enabled: Boolean): Int {
        if (baseMs <= 0 || direction == null) return 0
        val extra = if (enabled && lastDirection != null && lastDirection != direction) backlashMs.coerceIn(0, 2000) else 0
        return (baseMs.coerceIn(0, 5000) + extra).coerceAtMost(5000)
    }
}
