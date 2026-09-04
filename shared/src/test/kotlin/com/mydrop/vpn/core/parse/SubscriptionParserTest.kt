package com.mydrop.vpn.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionParserTest {

    private val links = """
        vless://uuid@a.example.com:443?security=reality&pbk=KEY&sid=00#A
        trojan://pw@b.example.com:443#B
        hysteria2://pw@c.example.com:443#C
    """.trimIndent()

    @Test
    fun `base64 encoded subscription body yields every server`() {
        val body = Base64.getEncoder().encodeToString(links.toByteArray())
        val result = SubscriptionParser.parse(body, "sub-1")

        assertTrue(result is SubscriptionBody.Nodes)
        val nodes = (result as SubscriptionBody.Nodes).nodes
        assertEquals(3, nodes.size)
        assertTrue(nodes.all { it.subscriptionId == "sub-1" })
    }

    @Test
    fun `plain text subscription body is accepted without base64`() {
        val result = SubscriptionParser.parse(links, "sub-1")
        assertEquals(3, (result as SubscriptionBody.Nodes).nodes.size)
    }

    @Test
    fun `url safe base64 without padding still decodes`() {
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(links.toByteArray())
        val result = SubscriptionParser.parse(body, "sub-1")
        assertEquals(3, (result as SubscriptionBody.Nodes).nodes.size)
    }

    @Test
    fun `clash yaml is reported as unsupported rather than silently empty`() {
        val yaml = """
            proxies:
              - name: test
                type: vmess
        """.trimIndent()

        val result = SubscriptionParser.parse(yaml, "sub-1")
        assertTrue(result is SubscriptionBody.UnsupportedFormat)
        assertEquals("Clash YAML", (result as SubscriptionBody.UnsupportedFormat).format)
    }

    @Test
    fun `sing-box json is reported as unsupported`() {
        val json = """{"outbounds":[{"type":"vless"}]}"""
        val result = SubscriptionParser.parse(json, "sub-1")
        assertEquals("sing-box JSON", (result as SubscriptionBody.UnsupportedFormat).format)
    }

    @Test
    fun `empty body is reported with a reason`() {
        assertTrue(SubscriptionParser.parse("   ", "sub-1") is SubscriptionBody.Empty)
    }

    @Test
    fun `subscription userinfo header is parsed into a quota`() {
        val info = requireNotNull(
            SubscriptionParser.parseUserInfo(
                "upload=1024; download=2048; total=10240; expire=1735689600",
            ),
        )

        assertEquals(1024L, info.uploadBytes)
        assertEquals(2048L, info.downloadBytes)
        assertEquals(10240L, info.totalBytes)
        assertEquals(3072L, info.usedBytes)
        assertEquals(7168L, info.remainingBytes)
        assertEquals(0.3f, info.usedFraction!!, 0.001f)
        assertEquals(1735689600L, info.expiresAtEpochSeconds)
    }

    @Test
    fun `userinfo without a total reports no quota fraction`() {
        val info = requireNotNull(SubscriptionParser.parseUserInfo("upload=1; download=2"))
        assertEquals(3L, info.usedBytes)
        assertNull(info.usedFraction)
        assertNull(info.remainingBytes)
    }

    @Test
    fun `expiry sent in milliseconds is normalised to seconds`() {
        val info = requireNotNull(SubscriptionParser.parseUserInfo("expire=1735689600000"))
        assertEquals(1735689600L, info.expiresAtEpochSeconds)
    }

    @Test
    fun `absent or malformed userinfo header yields null`() {
        assertNull(SubscriptionParser.parseUserInfo(null))
        assertNull(SubscriptionParser.parseUserInfo(""))
        assertNull(SubscriptionParser.parseUserInfo("garbage"))
    }
}
