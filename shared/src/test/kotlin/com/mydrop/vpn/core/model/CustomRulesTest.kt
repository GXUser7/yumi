package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomRulesTest {

    @Test
    fun `normalize converts domains, urls, host-ports, ips, cidrs and prefixes`() {
        assertEquals("domain:youtube.com", CustomRules.normalize("  YouTube.COM./  "))
        assertEquals("domain:www.youtube.com", CustomRules.normalize("https://www.youtube.com/watch?v=123"))
        assertEquals("domain:example.com", CustomRules.normalize("https://user@example.com:8443/path?a=1#frag"))
        assertEquals("domain:example.com", CustomRules.normalize("example.com:443"))
        assertEquals("geosite:category-ru", CustomRules.normalize("GEOSITE:category-ru"))
        assertEquals("geoip:ru", CustomRules.normalize("GeoIP:ru"))
        assertEquals("full:api.example.com", CustomRules.normalize("FULL:api.example.com"))
        assertEquals("keyword:tracker", CustomRules.normalize("KEYWORD:tracker"))
        assertEquals("regexp:^ads\\.", CustomRules.normalize("RegExp:^ads\\."))
        assertEquals("1.2.3.4", CustomRules.normalize("1.2.3.4"))
        assertEquals("1.2.3.4", CustomRules.normalize("1.2.3.4:8080"))
        assertEquals("2001:db8::1", CustomRules.normalize("2001:db8::1"))
        assertEquals("2001:db8::1", CustomRules.normalize("[2001:db8::1]:443"))
        assertEquals("10.0.0.0/8", CustomRules.normalize("10.0.0.0/8"))
        assertEquals("2001:db8::/32", CustomRules.normalize("[2001:db8::]/32"))
    }

    @Test
    fun `normalize rejects invalid inputs, whitespace, out-of-range cidrs and ext prefixes`() {
        assertNull(CustomRules.normalize(""))
        assertNull(CustomRules.normalize("   "))
        assertNull(CustomRules.normalize("not a site"))
        assertNull(CustomRules.normalize("geosite:"))
        assertNull(CustomRules.normalize("domain:   "))
        assertNull(CustomRules.normalize("10.0.0.0/33"))
        assertNull(CustomRules.normalize("2001:db8::/129"))
        assertNull(CustomRules.normalize("12345"))
        assertNull(CustomRules.normalize("ext:custom.dat:ru"))
        assertNull(CustomRules.normalize("foo:bar"))
    }

    @Test
    fun `adding moves entry between buckets without duplicates and removing clears it`() {
        val step1 = CustomRules()
            .adding(CustomRules.Action.Direct, "domain:a.com")
            .adding(CustomRules.Action.Direct, "domain:a.com")
        assertEquals(listOf("domain:a.com"), step1.direct)
        assertEquals(1, step1.size)

        val step2 = step1.adding(CustomRules.Action.Proxy, "domain:a.com")
        assertEquals(listOf("domain:a.com"), step2.proxy)
        assertTrue(step2.direct.isEmpty())
        assertTrue(step2.block.isEmpty())

        val step3 = step2.adding(CustomRules.Action.Block, "domain:a.com")
        assertEquals(listOf("domain:a.com"), step3.block)
        assertTrue(step3.proxy.isEmpty())

        val cleared = step3.removing(CustomRules.Action.Block, "domain:a.com")
        assertTrue(cleared.isEmpty)
        assertEquals(0, cleared.size)
    }

    @Test
    fun `isAddress identifies ip, cidr and geoip entries`() {
        assertTrue(CustomRules.isAddress("geoip:ru"))
        assertTrue(CustomRules.isAddress("GeoIP:!cn"))
        assertTrue(CustomRules.isAddress("1.2.3.4"))
        assertTrue(CustomRules.isAddress("10.0.0.0/8"))
        assertTrue(CustomRules.isAddress("2001:db8::/32"))
        assertFalse(CustomRules.isAddress("domain:example.com"))
        assertFalse(CustomRules.isAddress("geosite:category-ru"))
        assertFalse(CustomRules.isAddress("full:example.com"))
    }
}
