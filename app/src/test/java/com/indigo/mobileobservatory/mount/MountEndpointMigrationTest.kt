package com.indigo.mobileobservatory.mount

import com.indigo.mobileobservatory.mount.MountModule.Companion.DEFAULT_TCP_HOST
import com.indigo.mobileobservatory.mount.MountModule.Companion.DEFAULT_TCP_PORT
import org.junit.Assert.assertEquals
import org.junit.Test

class MountEndpointMigrationTest {
    @Test
    fun legacyIoptronTcpDefaultMovesToTheDocumentedWifiEndpoint() {
        val (host, port) = MountModule.migratedTcpEndpoint("192.168.0.1", 9998)

        assertEquals("10.10.100.254", host)
        assertEquals(8899, port)
    }

    @Test
    fun userConfiguredIoptronEndpointIsPreserved() {
        val (host, port) = MountModule.migratedTcpEndpoint("192.168.1.50", 8899)

        assertEquals("192.168.1.50", host)
        assertEquals(8899, port)
    }

    @Test
    fun defaultIoptronEndpointIsStable() {
        val (host, port) = MountModule.migratedTcpEndpoint(DEFAULT_TCP_HOST, DEFAULT_TCP_PORT)

        assertEquals(DEFAULT_TCP_HOST, host)
        assertEquals(DEFAULT_TCP_PORT, port)
    }

    @Test
    fun synScanEndpointKeepsItsOwnDefault() {
        val (host, port) = MountModule.migratedSynScanEndpoint("192.168.4.1", 11880)

        assertEquals("192.168.4.1", host)
        assertEquals(11880, port)
    }
}
