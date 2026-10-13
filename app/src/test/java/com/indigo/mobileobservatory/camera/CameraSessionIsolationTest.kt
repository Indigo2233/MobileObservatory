package com.indigo.mobileobservatory.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class CameraSessionIsolationTest {
    @Test fun blocksOnlyProvenSingletonSdkPairs() {
        assertTrue(CameraSessionIsolation.incompatible(CameraBrand.TOUPCAM, CameraBrand.TOUPCAM))
        assertTrue(CameraSessionIsolation.incompatible(CameraBrand.QHY, CameraBrand.QHY))
        assertFalse(CameraSessionIsolation.incompatible(CameraBrand.ZWO, CameraBrand.ZWO))
        assertFalse(CameraSessionIsolation.incompatible(CameraBrand.PLAYERONE, CameraBrand.PLAYERONE))
        assertFalse(CameraSessionIsolation.incompatible(CameraBrand.TOUPCAM, CameraBrand.QHY))
        assertFalse(CameraSessionIsolation.incompatible(null, CameraBrand.QHY))
    }
    @Test fun rejectedOwnerCannotReleaseTheActiveHandleAndCloseAllowsReconnect() {
        val lease = CameraSdkLease(); val first = Any(); val rejected = Any()
        assertTrue(lease.claim("qhy", first)); assertFalse(lease.claim("qhy", rejected))
        lease.release("qhy", rejected); assertTrue(lease.owns("qhy", first))
        assertTrue(lease.claim("qhy", first))
        lease.release("qhy", first); assertTrue(lease.claim("qhy", rejected))
    }
    @Test fun concurrentOpensHaveOneOwnerAndDifferentSdkCanRemainIndependent() {
        val lease = CameraSdkLease(); val start = CountDownLatch(1); val won = AtomicInteger()
        val threads = (1..12).map { Thread { start.await(); if (lease.claim("toupcam", Any())) won.incrementAndGet() }.apply { start() } }
        start.countDown(); threads.forEach { it.join(1000) }
        assertEquals(1, won.get()); assertTrue(lease.claim("qhy", Any()))
    }
}
