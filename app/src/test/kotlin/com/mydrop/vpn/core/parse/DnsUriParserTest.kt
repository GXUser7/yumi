package com.mydrop.vpn.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class DnsUriParserTest {

    /** Builds a stamp the way the specification lays one out, so the decoder is tested on shape. */
    private fun stamp(protocol: Int, vararg fields: String, hashes: Boolean = true): String {
        val bytes = mutableListOf<Byte>()
        bytes += protocol.toByte()
        repeat(8) { bytes += 0 }
        fields.forEachIndexed { index, field ->
            // The hash list sits after the address for every encrypted protocol.
            if (hashes && index == 1) bytes += 0
            bytes += field.length.toByte()
            bytes += field.toByteArray().toList()
        }
        return "sdns://" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(bytes.toByteArray())
    }

    @Test
    fun `doh url is taken as is`() {
        val profile = DnsUriParser.parse("https://xbox-dns.ru/dns-query")
        assertEquals("https://xbox-dns.ru/dns-query", profile?.url)
        assertEquals("DoH", profile?.kind)
        assertEquals("xbox-dns.ru", profile?.host)
    }

    @Test
    fun `dot and doq keep their scheme`() {
        assertEquals("DoT", DnsUriParser.parse("tls://xbox-dns.ru")?.kind)
        assertEquals("DoQ", DnsUriParser.parse("quic://dns.example.com")?.kind)
    }

    @Test
    fun `a bare address is a plain resolver`() {
        val profile = DnsUriParser.parse("1.1.1.1")
        assertEquals("1.1.1.1", profile?.url)
        assertEquals("UDP", profile?.kind)
    }

    @Test
    fun `a name after the hash becomes the title`() {
        assertEquals("Мой DNS", DnsUriParser.parse("tls://dns.example.com#Мой%20DNS")?.name)
    }

    @Test
    fun `doh stamp decodes to a url`() {
        val decoded = DnsUriParser.parse(stamp(0x02, "9.9.9.9:443", "dns.quad9.net", "/dns-query"))
        assertEquals("https://dns.quad9.net/dns-query", decoded?.url)
    }

    @Test
    fun `dot stamp decodes to a tls url`() {
        assertEquals("tls://dns.adguard.com", DnsUriParser.parse(stamp(0x03, "1.2.3.4:853", "dns.adguard.com"))?.url)
    }

    @Test
    fun `dnscrypt stamps are refused rather than downgraded`() {
        // sing-box has no DNSCrypt transport. Turning the stamp into its plain address would swap
        // an encrypted resolver for an unencrypted one without saying so.
        assertNull(DnsUriParser.parse(stamp(0x01, "1.2.3.4:443", "2.dnscrypt-cert.example")))
    }

    @Test
    fun `proxy links and subscription urls are not resolvers`() {
        assertNull(DnsUriParser.parse("vless://uuid@a.example.com:443?security=tls#N"))
        assertNull(DnsUriParser.parse("https://sub.example.com/abcdef"))
        assertNull(DnsUriParser.parse("просто текст"))
        assertNull(DnsUriParser.parse(""))
    }

    @Test
    fun `a page of addresses yields every distinct resolver`() {
        val page = """
            Для шифрованного DNS (DoH/DoT):
            tls://xbox-dns.ru
            https://xbox-dns.ru/dns-query
            tls://xbox-dns.ru
        """.trimIndent()

        assertEquals(
            listOf("tls://xbox-dns.ru", "https://xbox-dns.ru/dns-query"),
            DnsUriParser.parseAll(page).map { it.url },
        )
    }
}
