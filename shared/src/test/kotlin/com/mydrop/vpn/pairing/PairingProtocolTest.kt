package com.mydrop.vpn.pairing

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingProtocolTest {
    @Test
    fun inviteRoundTripsThroughQrText() {
        val keys = PairingCrypto.keyPair()
        val invite = PairingInvite(
            version = VERSION,
            host = "192.168.1.20",
            port = 43127,
            sessionId = PairingCrypto.sessionId(),
            receiverPublicKey = PairingCrypto.publicKey(keys.public),
            deviceName = "Yumi TV · Living room",
        )

        assertEquals(invite, PairingInvite.decode(invite.encode()))
        assertFalse(invite.encode().contains("subscription"))
    }

    @Test
    fun invalidOrUnsupportedInviteIsRejected() {
        assertNull(PairingInvite.decode("https://example.com"))
        assertNull(PairingInvite.decode("yumi://pair?v=9&host=192.168.1.2&port=1&sid=x&key=x"))
    }

    @Test
    fun senderAndReceiverAuthenticateBothDirections() {
        val receiver = PairingCrypto.keyPair()
        val invite = PairingInvite(
            VERSION,
            "192.168.0.3",
            1234,
            PairingCrypto.sessionId(),
            PairingCrypto.publicKey(receiver.public),
            "TV",
        )
        val source = SubscriptionTransfer(
            name = "Private source",
            url = "https://provider.example/sub/secret-token",
            userAgentOverride = "Happ/1.0",
            headers = mapOf("Authorization" to "Bearer secret"),
        )

        val (request, senderKey) = PairingCrypto.request(invite, source)
        val (opened, receiverKey) = PairingCrypto.openRequest(request, receiver)
        assertEquals(source, opened)
        assertTrue(senderKey.contentEquals(receiverKey))

        val result = PairingResult(true, "accepted", source.name)
        val reply = PairingCrypto.reply(result, receiverKey, invite.sessionId)
        assertEquals(result, PairingCrypto.openReply(reply, senderKey, invite.sessionId))
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        val receiver = PairingCrypto.keyPair()
        val invite = PairingInvite(
            VERSION,
            "10.0.0.2",
            1234,
            PairingCrypto.sessionId(),
            PairingCrypto.publicKey(receiver.public),
            "TV",
        )
        val (request, _) = PairingCrypto.request(
            invite,
            SubscriptionTransfer(name = "Source", url = "https://example.com/sub"),
        )
        val damaged = Base64.getUrlDecoder().decode(request.ciphertext).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }

        assertThrows(Exception::class.java) {
            PairingCrypto.openRequest(
                request.copy(ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(damaged)),
                receiver,
            )
        }
    }

    @Test
    fun transferValidationRejectsNonSubscriptionAndHeaderInjection() {
        assertNull(SubscriptionTransfer(name = "x", url = "vless://secret").validate())
        assertNull(
            SubscriptionTransfer(
                name = "x",
                url = "https://example.com/sub",
                headers = mapOf("Authorization\r\nInjected" to "secret"),
            ).validate(),
        )
        assertNull(
            SubscriptionTransfer(
                name = "x",
                url = "https://example.com/sub",
                headers = mapOf("X-HWID" to "phone-identity"),
            ).validate(),
        )
        assertEquals(
            "x",
            SubscriptionTransfer(name = "x", url = "https://example.com/sub").validate()?.name,
        )
    }

    @Test
    fun sessionExpiresAndCannotBeReplayed() {
        var now = 1_000L
        val guard = PairingSessionGuard("session", expiresAtMillis = 2_000L) { now }

        assertFalse(guard.isCurrent("different"))
        assertTrue(guard.isCurrent("session"))
        assertTrue(guard.claim())
        assertFalse(guard.claim())
        assertFalse(guard.isCurrent("session"))

        now = 2_000L
        val expired = PairingSessionGuard("expired", expiresAtMillis = 2_000L) { now }
        assertFalse(expired.isCurrent("expired"))
        assertFalse(expired.claim())
    }

    @Test
    fun serializedTransferContainsOnlyAllowedSourceFields() {
        val encoded = PairingCrypto.json.encodeToString(
            SubscriptionTransfer(
                name = "Source",
                url = "https://example.com/sub",
                userAgentOverride = "Yumi-test",
                headers = mapOf("Authorization" to "Bearer token"),
            ),
        )

        assertTrue(encoded.contains("name"))
        assertTrue(encoded.contains("url"))
        assertTrue(encoded.contains("userAgentOverride"))
        assertTrue(encoded.contains("headers"))
        assertFalse(encoded.contains("x-hwid", ignoreCase = true))
        assertFalse(encoded.contains("servers", ignoreCase = true))
        assertFalse(encoded.contains("statistics", ignoreCase = true))
        assertFalse(encoded.contains("internalId", ignoreCase = true))
    }
}
