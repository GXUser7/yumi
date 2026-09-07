package com.mydrop.vpn.remote

import javax.crypto.AEADBadTagException
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote's wire, tested where it is pure.
 *
 * Sockets and the network are not here — those need two devices and a router that behaves. What is
 * here is every claim the protocol makes about who can read what: that a stranger cannot open a
 * frame, that yesterday's frame cannot be played back today, and that a frame the television sent
 * cannot be played back at it as though a phone had sent it.
 */
class RemoteProtocolTest {

    private fun invite(host: PublicPair = keys()) = RemoteInvite(
        version = REMOTE_VERSION,
        host = "192.168.0.14",
        port = REMOTE_PORT,
        code = RemoteCrypto.token(),
        hostPublicKey = RemoteCrypto.encode(host.publicKey),
        hostId = RemoteCrypto.token(12),
        hostName = "Yumi TV · Living room",
    )

    private class PublicPair(val pair: java.security.KeyPair) {
        val publicKey get() = pair.public
        val privateKey get() = pair.private
    }

    private fun keys() = PublicPair(RemoteCrypto.keyPair())

    @Test
    fun inviteRoundTripsThroughQrText() {
        val invite = invite()
        assertEquals(invite, RemoteInvite.decode(invite.encode()))
        // The code is a secret and travels in the QR by necessity; nothing else about the set does.
        assertTrue(invite.encode().startsWith("yumi://remote?"))
    }

    @Test
    fun subscriptionInviteIsNotMistakenForARemoteOne() {
        // The two codes live on two different screens of the same app, and the phone tells them
        // apart by the scheme's host alone — see MainViewModel.importText.
        assertNull(RemoteInvite.decode("yumi://pair?v=1&host=192.168.0.14&port=1&sid=x&key=x"))
        assertNull(RemoteInvite.decode("https://example.com"))
    }

    @Test
    fun futureVersionsAndShortCodesAreRejected() {
        val invite = invite()
        assertNull(RemoteInvite.decode(invite.encode().replace("v=1", "v=2")))
        assertNull(RemoteInvite.decode(invite.copy(code = "c2hvcnQ").encode()))
    }

    @Test
    fun bothEndsDeriveTheSameKeyFromTheCodeAndTheSetsOwnKey() {
        val host = keys()
        val invite = invite(host)
        val phone = keys()
        val sessionId = RemoteCrypto.token()

        val fromPhone = RemoteCrypto.sessionKey(phone.privateKey, host.publicKey, invite.code, sessionId)
        val fromTv = RemoteCrypto.sessionKey(host.privateKey, phone.publicKey, invite.code, sessionId)
        assertTrue(fromPhone.contentEquals(fromTv))
    }

    @Test
    fun aPhoneWithoutTheSecretCannotOpenAnything() {
        val host = keys()
        val phone = keys()
        val sessionId = RemoteCrypto.token()
        val real = RemoteCrypto.sessionKey(phone.privateKey, host.publicKey, "the-code", sessionId)
        val guessed = RemoteCrypto.sessionKey(phone.privateKey, host.publicKey, "another-code", sessionId)
        assertFalse(real.contentEquals(guessed))

        val sealed = RemoteCrypto.seal(
            "hello".toByteArray(),
            real,
            RemoteCrypto.aad(sessionId, fromPhone = true, counter = 0),
        )
        assertThrows(AEADBadTagException::class.java) {
            RemoteCrypto.open(sealed, guessed, RemoteCrypto.aad(sessionId, fromPhone = true, counter = 0))
        }
    }

    @Test
    fun anImpostorOnThePortCannotBeTalkedTo() {
        // The set is authenticated by its long-lived key, so somebody answering on 47654 with a key
        // of their own derives a different session key even holding a valid code.
        val real = keys()
        val impostor = keys()
        val phone = keys()
        val sessionId = RemoteCrypto.token()
        val code = RemoteCrypto.token()
        assertFalse(
            RemoteCrypto.sessionKey(phone.privateKey, real.publicKey, code, sessionId)
                .contentEquals(
                    RemoteCrypto.sessionKey(phone.privateKey, impostor.publicKey, code, sessionId),
                ),
        )
    }

    @Test
    fun aFrameCannotBeReplayedOrReordered() {
        val key = ByteArray(32) { it.toByte() }
        val sessionId = RemoteCrypto.token()
        val command = RemoteCrypto.json.encodeToString<RemoteCommand>(RemoteCommand.Disconnect)
        val first = RemoteCrypto.seal(
            command.toByteArray(),
            key,
            RemoteCrypto.aad(sessionId, fromPhone = true, counter = 1),
        )

        // Arriving where it was expected: fine.
        assertEquals(
            command,
            RemoteCrypto.open(first, key, RemoteCrypto.aad(sessionId, fromPhone = true, counter = 1))
                .decodeToString(),
        )
        // The same frame offered again, where the receiver is now expecting the next one.
        assertThrows(AEADBadTagException::class.java) {
            RemoteCrypto.open(first, key, RemoteCrypto.aad(sessionId, fromPhone = true, counter = 2))
        }
        // And the same frame turned around, as though the set had sent it.
        assertThrows(AEADBadTagException::class.java) {
            RemoteCrypto.open(first, key, RemoteCrypto.aad(sessionId, fromPhone = false, counter = 1))
        }
    }

    @Test
    fun yesterdaysConversationDoesNotUnlockTodays() {
        val host = keys()
        val phone = keys()
        val code = RemoteCrypto.token()
        assertFalse(
            RemoteCrypto.sessionKey(phone.privateKey, host.publicKey, code, RemoteCrypto.token())
                .contentEquals(
                    RemoteCrypto.sessionKey(phone.privateKey, host.publicKey, code, RemoteCrypto.token()),
                ),
        )
    }

    @Test
    fun commandsAndSnapshotsSurviveTheWire() {
        val command: RemoteCommand = RemoteCommand.Select("node-17")
        assertEquals(
            command,
            RemoteCrypto.json.decodeFromString<RemoteCommand>(
                RemoteCrypto.json.encodeToString(command),
            ),
        )

        val snapshot = RemoteSnapshot(
            hostName = "Yumi TV",
            state = RemoteTunnelState.On,
            selectedNodeId = "node-17",
            connectedAtEpochMillis = 1_757_000_000_000L,
            downloadBytesPerSecond = 4_096,
            uploadBytesPerSecond = 512,
            nodesRevision = 42,
            nodes = listOf(RemoteNode("node-17", "🇳🇱 Amsterdam", "Provider", 38, false)),
        )
        assertEquals(
            snapshot,
            RemoteCrypto.json.decodeFromString<RemoteSnapshot>(
                RemoteCrypto.json.encodeToString(snapshot),
            ),
        )
    }

    @Test
    fun aSnapshotWithoutItsServerListIsStillASnapshot() {
        // Most snapshots leave the list out — see RemoteServer.pump — and the phone has to be able
        // to tell "no list this time" from "the set has no servers".
        val trimmed = RemoteSnapshot(nodesRevision = 42, nodes = null)
        val decoded = RemoteCrypto.json.decodeFromString<RemoteSnapshot>(
            RemoteCrypto.json.encodeToString(trimmed),
        )
        assertNull(decoded.nodes)
        assertNotEquals(emptyList<RemoteNode>(), decoded.nodes)
    }
}
