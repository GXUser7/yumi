package com.mydrop.vpn.remote

import com.mydrop.vpn.pairing.base64Url
import com.mydrop.vpn.pairing.base64UrlBytes
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The remote: one phone driving one television's tunnel over the local network.
 *
 * Separate from `pairing/`, which hands a subscription across once and hangs up. This is a
 * conversation that stays open — the phone sends what the user pressed and the television answers
 * with what it is doing — and it survives being closed, because a remote control you have to
 * re-introduce to the set every evening is not a remote control.
 *
 * The introduction is the same shape as the subscription hand-off: a code on the television's
 * screen, read by the phone's camera, valid for five minutes and usable once. What it leaves
 * behind is different. The television keeps a secret per phone and the phone keeps that secret
 * along with the television's public key, so every later session authenticates both ends without
 * anybody walking to the set.
 */

/** How the two ends address one another. Bumped when a frame's meaning changes. */
const val REMOTE_VERSION = 1

/** The control channel. Fixed, so a phone that knows the address can try it before it searches. */
const val REMOTE_PORT = 47654

/** Where the television answers "yes, I am here", so the phone can find it after a new lease. */
const val REMOTE_BEACON_PORT = 47655

const val REMOTE_MAX_FRAME_BYTES = 256 * 1024

/** As long as the subscription hand-off gets, and for the same reason: a code on screen is a key. */
const val REMOTE_PAIRING_LIFETIME_MILLIS = 5 * 60 * 1000L

/** Everything the phone needs to introduce itself to one television, carried by the QR code. */
@Serializable
data class RemoteInvite(
    val version: Int,
    val host: String,
    val port: Int,
    /** The one-time secret this window is open with; see [RemoteCrypto.sessionKey]. */
    val code: String,
    /** The television's long-lived public key — what authenticates it in every later session. */
    val hostPublicKey: String,
    val hostId: String,
    val hostName: String,
) {
    fun encode(): String = buildString {
        append("yumi://remote?")
        append(
            listOf(
                "v" to version.toString(),
                "host" to host,
                "port" to port.toString(),
                "code" to code,
                "key" to hostPublicKey,
                "id" to hostId,
                "name" to hostName,
            ).joinToString("&") { (name, value) -> "$name=${value.urlEncoded()}" },
        )
    }

    companion object {
        fun decode(raw: String): RemoteInvite? = runCatching {
            val uri = URI(raw.trim())
            if (uri.scheme != "yumi" || uri.host != "remote") return null
            val values = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null else part.substring(0, separator) to
                    URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8.name())
            }.toMap()
            RemoteInvite(
                version = values.getValue("v").toInt(),
                host = values.getValue("host"),
                port = values.getValue("port").toInt(),
                code = values.getValue("code"),
                hostPublicKey = values.getValue("key"),
                hostId = values.getValue("id"),
                hostName = values["name"].orEmpty().take(96),
            ).takeIf {
                it.version == REMOTE_VERSION && it.port in 1..65535 &&
                    it.code.base64UrlBytes().size >= 18 &&
                    RemoteCrypto.publicKeyOf(it.hostPublicKey) != null
            }
        }.getOrNull()
    }
}

/* ── What crosses the wire ────────────────────────────────────────────────────────────────── */

/**
 * The phone's opening move, in the clear.
 *
 * Only the routing lives outside the seal: which television, which conversation, and the public
 * half of a key that exists for this conversation alone. The name of the phone is inside, because
 * a listener on the same Wi-Fi has no business reading it.
 */
@Serializable
internal data class RemoteGreeting(
    val version: Int,
    val sessionId: String,
    /** Empty while pairing: the television has not named this phone yet. */
    val peerId: String = "",
    val senderPublicKey: String,
    val iv: String,
    val ciphertext: String,
)

@Serializable
internal data class RemoteOpening(val deviceName: String)

@Serializable
internal data class RemoteSealed(val iv: String, val ciphertext: String)

/** What the television grants, sealed inside its reply. */
@Serializable
internal data class RemoteAccess(
    val accepted: Boolean,
    val reason: String = "",
    val peerId: String = "",
    /** Only ever filled on the introduction; a resumed session already has it. */
    val secret: String = "",
    val hostId: String = "",
    val hostName: String = "",
)

/** One press on the phone. */
@Serializable
sealed interface RemoteCommand {
    /** Asks for a snapshot and nothing else — what the phone sends the moment it is connected. */
    @Serializable @SerialName("look") data object Look : RemoteCommand

    @Serializable @SerialName("connect") data object Connect : RemoteCommand

    @Serializable @SerialName("disconnect") data object Disconnect : RemoteCommand

    /**
     * Chooses the exit server. The television switches over to it if its tunnel is already up,
     * which is what selecting a server does when somebody does it with the set's own remote.
     */
    @Serializable @SerialName("select") data class Select(val nodeId: String) : RemoteCommand
}

/** One server as the phone needs to draw it. Addresses and keys stay on the television. */
@Serializable
data class RemoteNode(
    val id: String,
    val name: String,
    val group: String = "",
    val latencyMillis: Int? = null,
    val latencyFailed: Boolean = false,
)

/**
 * What the television is doing, as one message.
 *
 * A whole picture rather than a diff, sent on every change and once a second besides. The list of
 * servers is the expensive part and it barely ever moves, so it is sent only when [nodesRevision]
 * says it has — the phone keeps the last list it was given.
 */
@Serializable
data class RemoteSnapshot(
    val hostName: String = "",
    val state: RemoteTunnelState = RemoteTunnelState.Off,
    /** A failure's own words, or the sentence about consent. Already in the set's language. */
    val message: String = "",
    /**
     * Set when the television cannot go on without somebody pressing "OK" on its own screen.
     *
     * Android will not let an app raise a tunnel until the owner of the device has agreed to it in
     * a system dialog, and that dialog is on the television. The remote has to be able to say so,
     * or the button appears to do nothing at all.
     */
    val needsConsentOnTv: Boolean = false,
    val selectedNodeId: String? = null,
    val connectedAtEpochMillis: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val nodesRevision: Int = 0,
    val nodes: List<RemoteNode>? = null,
)

@Serializable
enum class RemoteTunnelState { Off, Warming, On, Stopping, Failed }

/* ── Who is trusted, and by what ──────────────────────────────────────────────────────────── */

/** A television the phone has been introduced to. */
@Serializable
data class RemoteBond(
    val hostId: String,
    val hostName: String,
    val hostPublicKey: String,
    val peerId: String,
    val secret: String,
    /** Where it answered last. Tried first, because it is usually still right. */
    val lastHost: String = "",
    val lastPort: Int = REMOTE_PORT,
)

/** A phone the television has been introduced to. */
@Serializable
data class RemotePeer(
    val id: String,
    val name: String,
    val secret: String,
    val pairedAtEpochMillis: Long = 0,
    val lastSeenEpochMillis: Long = 0,
)

/* ── The crypto ───────────────────────────────────────────────────────────────────────────── */

/**
 * One key per session, and it takes two things to derive it.
 *
 * The elliptic-curve half is between the phone's throwaway key and the television's long-lived
 * one, so the phone is talking to that television and not to whatever else answered on the port.
 * The shared secret is mixed in on top, so the television knows the phone is one it has met — and
 * so that somebody who later reads the secret off a stolen phone still cannot make sense of a
 * conversation recorded today.
 *
 * During the introduction the "secret" is the one-time code from the screen, which is the whole
 * point of showing it there: possession of that code is what stands in for the phone having been
 * introduced yet.
 */
internal object RemoteCrypto {
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val random = SecureRandom()

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }.generateKeyPair()

    fun token(bytes: Int = 24): String = ByteArray(bytes).also(random::nextBytes).base64Url()

    fun encode(key: PublicKey): String = key.encoded.base64Url()
    fun encode(key: PrivateKey): String = key.encoded.base64Url()

    fun publicKeyOf(encoded: String): PublicKey? = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded.base64UrlBytes()))
    }.getOrNull()

    fun privateKeyOf(encoded: String): PrivateKey? = runCatching {
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded.base64UrlBytes()))
    }.getOrNull()

    fun sessionKey(
        ours: PrivateKey,
        theirs: PublicKey,
        secret: String,
        sessionId: String,
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ours)
        agreement.doPhase(theirs, true)
        val material = agreement.generateSecret() + secret.toByteArray()
        val salt = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray())
        val extract = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(material)
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(extract, "HmacSHA256"))
            doFinal("yumi-remote-v1".toByteArray()).copyOf(32)
        }
    }

    fun seal(plain: ByteArray, key: ByteArray, aad: ByteArray): RemoteSealed {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return RemoteSealed(iv.base64Url(), cipher.doFinal(plain).base64Url())
    }

    fun open(sealed: RemoteSealed, key: ByteArray, aad: ByteArray): ByteArray {
        val iv = sealed.iv.base64UrlBytes()
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.ciphertext.base64UrlBytes())
    }

    /**
     * What every frame is sealed against.
     *
     * The counter is in here rather than on the wire, so a frame replayed or served out of order
     * is one the receiver simply cannot open: it is expecting the next number and the tag was
     * computed against a different one. The direction keeps a frame the television sent from being
     * played back at it as though the phone had sent it.
     */
    fun aad(sessionId: String, fromPhone: Boolean, counter: Long): ByteArray =
        "yumi-remote-v1:$sessionId:${if (fromPhone) "up" else "down"}:$counter".toByteArray()
}

private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
