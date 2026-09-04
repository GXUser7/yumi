package com.mydrop.vpn.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DeepLinkParserTest {

    private fun b64url(value: String) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    @Test
    fun `happ add link with base64 payload becomes a subscription`() {
        val target = "https://provider.example.com/sub/token123"
        val result = DeepLinkParser.parse("happ://add/${b64url(target)}")

        assertTrue(result is DeepLinkPayload.AddSubscription)
        assertEquals(target, (result as DeepLinkPayload.AddSubscription).url)
    }

    @Test
    fun `happ add link with an unencoded url is also accepted`() {
        val result = DeepLinkParser.parse("happ://add/https%3A%2F%2Fp.example.com%2Fsub")
        assertEquals(
            "https://p.example.com/sub",
            (result as DeepLinkPayload.AddSubscription).url,
        )
    }

    @Test
    fun `bare https link is treated as a subscription`() {
        val result = DeepLinkParser.parse("https://provider.example.com/sub/abc")
        assertTrue(result is DeepLinkPayload.AddSubscription)
    }

    @Test
    fun `single share link is imported as a server`() {
        val result = DeepLinkParser.parse("vless://uuid@a.example.com:443?security=tls#A")
        assertEquals(1, (result as DeepLinkPayload.AddNodes).nodes.size)
    }

    @Test
    fun `multi line share blob imports every server`() {
        val blob = """
            vless://uuid@a.example.com:443?security=tls#A
            trojan://pw@b.example.com:443#B
        """.trimIndent()

        val result = DeepLinkParser.parse(blob)
        assertEquals(2, (result as DeepLinkPayload.AddNodes).nodes.size)
    }

    @Test
    fun `base64 blob with no scheme is decoded before import`() {
        val blob = "vless://uuid@a.example.com:443?security=tls#A"
        val result = DeepLinkParser.parse(Base64.getEncoder().encodeToString(blob.toByteArray()))
        assertEquals(1, (result as DeepLinkPayload.AddNodes).nodes.size)
    }

    @Test
    fun `mydrop scheme behaves like happ`() {
        val result = DeepLinkParser.parse("mydrop://add/${b64url("https://p.example.com/s")}")
        assertTrue(result is DeepLinkPayload.AddSubscription)
    }

    @Test
    fun `name query parameter is carried through`() {
        val result = DeepLinkParser.parse(
            "happ://add/${b64url("https://p.example.com/s")}?name=Provider",
        )
        assertEquals("Provider", (result as DeepLinkPayload.AddSubscription).name)
    }

    @Test
    fun `unrecognised payload reports why instead of failing silently`() {
        val result = DeepLinkParser.parse("happ://add/????")
        assertTrue(result is DeepLinkPayload.Unsupported)
        // The code, not a sentence: the wording lives in resources and changes with the language,
        // while "this was not recognised as anything" is the fact worth pinning down.
        assertEquals(
            UnsupportedReason.NotRecognised,
            (result as DeepLinkPayload.Unsupported).reason,
        )
    }
}
