package com.mydrop.vpn.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillingUrlTest {

    @Test
    fun `allows valid https URLs regardless of loopback flag`() {
        val url = "https://pay.example.com/api/billing?token=abc"
        assertEquals(url, sanitizeBillingUrl(url, allowLoopbackHttp = false))
        assertEquals(url, sanitizeBillingUrl(url, allowLoopbackHttp = true))
        assertEquals(url, sanitizePortalUrl(url, allowLoopbackHttp = false))
    }

    @Test
    fun `rejects remote http URLs even when allowLoopbackHttp is true`() {
        assertNull(sanitizeBillingUrl("http://pay.example.com/checkout", allowLoopbackHttp = false))
        assertNull(sanitizeBillingUrl("http://pay.example.com/checkout", allowLoopbackHttp = true))
        assertNull(sanitizePortalUrl("http://portal.example.com/", allowLoopbackHttp = true))
    }

    @Test
    fun `rejects dangerous non-http schemes and embedded userinfo`() {
        assertNull(sanitizeBillingUrl("javascript:alert(1)", allowLoopbackHttp = true))
        assertNull(sanitizeBillingUrl("file:///etc/passwd", allowLoopbackHttp = true))
        assertNull(sanitizeBillingUrl("data:text/html,<script>", allowLoopbackHttp = true))
        assertNull(sanitizePortalUrl("intent://pay/#Intent;end", allowLoopbackHttp = true))
        assertNull(sanitizeBillingUrl("https://user:secret@pay.example.com/c", allowLoopbackHttp = true))
    }

    @Test
    fun `rejects unspecified or non-canonical loopback literals even in dev mode`() {
        assertNull(sanitizeBillingUrl("http://0.0.0.0:8787/api", allowLoopbackHttp = true))
        assertNull(sanitizeBillingUrl("https://0.0.0.0/api", allowLoopbackHttp = true))
        assertNull(sanitizeBillingUrl("http://[::]:8787/api", allowLoopbackHttp = true))
        assertNull(sanitizeBillingUrl("http://0x7f000001:8787/api", allowLoopbackHttp = true))
        assertNull(sanitizeBillingUrl("http://2130706433:8787/api", allowLoopbackHttp = true))
    }

    @Test
    fun `allows documented local dev hosts only when allowLoopbackHttp is true`() {
        val devUrls = listOf(
            "http://127.0.0.1:8787/api/billing/demo",
            "http://localhost:8787/api/billing/demo",
            "http://[::1]:8787/api/billing/demo",
            "http://10.0.2.2:8787/api/billing/demo",
        )
        for (url in devUrls) {
            assertNull("Expected release mode to reject $url", sanitizeBillingUrl(url, allowLoopbackHttp = false))
            assertEquals("Expected debug mode to allow $url", url, sanitizeBillingUrl(url, allowLoopbackHttp = true))
            assertEquals("Expected debug portal to allow $url", url, sanitizePortalUrl(url, allowLoopbackHttp = true))
        }
    }

    @Test
    fun `billingDisplayHost omits default ports and lowercases host`() {
        assertEquals("pay.example.com", billingDisplayHost("https://PAY.Example.COM/checkout?x=1"))
        assertEquals("pay.example.com:8443", billingDisplayHost("https://pay.example.com:8443/checkout"))
        assertEquals("127.0.0.1:8787", billingDisplayHost("http://127.0.0.1:8787/pay/ord_1"))
    }
}
