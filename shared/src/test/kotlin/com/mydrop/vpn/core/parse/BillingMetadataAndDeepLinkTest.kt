package com.mydrop.vpn.core.parse

import com.mydrop.vpn.core.model.formatBillingMoney
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingMetadataAndDeepLinkTest {

    @Test
    fun `formatBillingMoney formats RUB USD and XTR cleanly`() {
        assertEquals("1 890 ₽", formatBillingMoney(189000, "RUB"))
        assertEquals("1 701 ₽", formatBillingMoney(170100, "RUB"))
        assertEquals("189.5 ₽", formatBillingMoney(18950, "RUB"))
        assertEquals("$12.99", formatBillingMoney(1299, "USD"))
        assertEquals("150 Stars", formatBillingMoney(150, "XTR"))
    }

    @Test
    fun `parseMetadataComments reads hash and slash headers in plain and base64 bodies`() {
        val plainBody = """
            #billing-manifest-url: https://panel.example.com/api/billing/tok
            //payment-url: https://pay.example.com/quick
            #profile-web-page-url: https://panel.example.com/me
            #support-url: https://t.me/example_support
            vless://11111111-1111-1111-1111-111111111111@203.0.113.10:443#DE1
        """.trimIndent()

        val parsedPlain = SubscriptionParser.parseMetadataComments(plainBody)
        assertEquals("https://panel.example.com/api/billing/tok", parsedPlain["billing-manifest-url"])
        assertEquals("https://pay.example.com/quick", parsedPlain["payment-url"])
        assertEquals("https://panel.example.com/me", parsedPlain["profile-web-page-url"])
        assertEquals("https://t.me/example_support", parsedPlain["support-url"])

        val encoded = Base64.getEncoder().encodeToString(plainBody.toByteArray(Charsets.UTF_8))
        val parsedBase64 = SubscriptionParser.parseMetadataComments(encoded)
        assertEquals("https://panel.example.com/api/billing/tok", parsedBase64["billing-manifest-url"])
        assertEquals("https://pay.example.com/quick", parsedBase64["payment-url"])
    }

    @Test
    fun `parseMetadataComments stops scanning after first 10 non-blank lines`() {
        val lines = (1..10).map { "vless://11111111-1111-1111-1111-111111111111@203.0.113.$it:443#S$it" } +
            "#billing-manifest-url: https://late.example.com/api"
        val body = lines.joinToString("\n")

        val parsed = SubscriptionParser.parseMetadataComments(body)
        assertNull(parsed["billing-manifest-url"])
    }

    @Test
    fun `DeepLinkParser handles mydrop and yumi billing return URIs and rejects unknown billing actions`() {
        val mydropReturn = DeepLinkParser.parse("mydrop://billing/return?sub_id=sub-42&order_id=ord_99")
        assertTrue(mydropReturn is DeepLinkPayload.BillingReturn)
        mydropReturn as DeepLinkPayload.BillingReturn
        assertEquals("sub-42", mydropReturn.subscriptionId)
        assertEquals("ord_99", mydropReturn.orderId)

        val yumiReturn = DeepLinkParser.parse("yumi://billing/return?sub_id=sub-7")
        assertTrue(yumiReturn is DeepLinkPayload.BillingReturn)
        yumiReturn as DeepLinkPayload.BillingReturn
        assertEquals("sub-7", yumiReturn.subscriptionId)
        assertNull(yumiReturn.orderId)

        val unknownBilling = DeepLinkParser.parse("mydrop://billing/other?sub_id=sub-42")
        assertFalse(unknownBilling is DeepLinkPayload.BillingReturn)
    }
}
