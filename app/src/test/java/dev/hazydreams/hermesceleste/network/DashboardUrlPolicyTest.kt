package dev.hazydreams.hermesceleste.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DashboardUrlPolicyTest {
    @Test
    fun normalizesSecureDashboardOrigins() {
        assertEquals("https://hermes.example", DashboardUrlPolicy.normalize(" https://hermes.example/ "))
        assertEquals("https://hermes.example:9443", DashboardUrlPolicy.normalize("https://hermes.example:9443"))
        assertEquals("https://hermes.example/nested", DashboardUrlPolicy.normalize("https://hermes.example/nested/"))
    }

    @Test
    fun permitsPrivateAndTailscaleHttpOrigins() {
        assertEquals("http://192.168.1.12:9119", DashboardUrlPolicy.normalize("http://192.168.1.12:9119/"))
        assertEquals("http://100.95.236.127:9119", DashboardUrlPolicy.normalize("http://100.95.236.127:9119"))
        assertEquals("http://100.95.236.127:9119", DashboardUrlPolicy.normalize("100.95.236.127:9119"))
        assertEquals("http://hermes.local:9119", DashboardUrlPolicy.normalize("http://hermes.local:9119"))
        assertEquals("http://juno.example.ts.net:9119", DashboardUrlPolicy.normalize("http://juno.example.ts.net:9119"))
    }

    @Test
    fun rejectsPublicCleartextAndNonOriginAddresses() {
        assertThrows(IllegalArgumentException::class.java) { DashboardUrlPolicy.normalize("http://example.com:9119") }
        assertThrows(IllegalArgumentException::class.java) { DashboardUrlPolicy.normalize("ftp://192.168.1.2") }
    }
}
