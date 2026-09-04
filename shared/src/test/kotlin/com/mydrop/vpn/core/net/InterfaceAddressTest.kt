package com.mydrop.vpn.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * These cases are transcribed from a real crash. The core parses every address it is handed with
 * Go's `netip.MustParsePrefix`, which panics on an IPv6 zone instead of erroring — so a single
 * malformed entry aborted the whole process with a native SIGABRT and no Java stack.
 */
class InterfaceAddressTest {

    @Test
    fun `link-local ipv6 loses its zone`() {
        // Verbatim from the panic: netip.ParsePrefix("fe80::38dd:baff:fe7a:5fc%dummy0/64"):
        // IPv6 zones cannot be present in a prefix
        assertEquals(
            "fe80::38dd:baff:fe7a:5fc/64",
            interfaceCidr("fe80::38dd:baff:fe7a:5fc%dummy0", 64),
        )
    }

    @Test
    fun `numeric scope ids are stripped too`() {
        assertEquals("fe80::1/64", interfaceCidr("fe80::1%7", 64))
    }

    @Test
    fun `ordinary addresses pass through untouched`() {
        assertEquals("192.168.1.5/24", interfaceCidr("192.168.1.5", 24))
        assertEquals("2a00:1450:4010:c05::8a/128", interfaceCidr("2a00:1450:4010:c05::8a", 128))
        assertEquals("127.0.0.1/8", interfaceCidr("127.0.0.1", 8))
    }

    @Test
    fun `an address that cannot be rendered is dropped rather than emitted empty`() {
        assertNull(interfaceCidr(null, 64))
        assertNull(interfaceCidr("", 64))
        // A bare zone leaves nothing to parse; the core must not receive "/64".
        assertNull(interfaceCidr("%wlan0", 64))
    }

    @Test
    fun `a negative prefix length is dropped`() {
        assertNull(interfaceCidr("192.168.1.5", -1))
    }
}
