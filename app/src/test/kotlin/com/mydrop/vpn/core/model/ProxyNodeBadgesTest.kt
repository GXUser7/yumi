package com.mydrop.vpn.core.model

import com.mydrop.vpn.core.parse.ProxyUriParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyNodeBadgesTest {

    private fun badges(uri: String): List<String> =
        requireNotNull(ProxyUriParser.parse(uri)).badges

    @Test
    fun `skipped certificate checking is visible on the row`() {
        assertTrue("no-verify" in badges("hysteria2://pass@se.example.com:443?insecure=1#N"))
        assertFalse("no-verify" in badges("hysteria2://pass@se.example.com:443#N"))
    }

    /**
     * REALITY authenticates with its own key exchange and never consults the certificate it is
     * shown, so the flag says nothing there. Badging it would advertise a weakness that is not
     * one and push a genuinely informative badge out of the four the row has room for.
     */
    @Test
    fun `reality is not badged as unverified`() {
        val reality = badges(
            "vless://uuid-1@de.example.com:443?security=reality&pbk=PUBKEY&insecure=1#N",
        )
        assertTrue("REALITY" in reality)
        assertFalse("no-verify" in reality)
    }
}
