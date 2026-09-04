package com.mydrop.vpn.pairing

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Everything a nearby Yumi installation needs to reach one ephemeral TV receiver. */
@Serializable
data class PairingInvite(
    val version: Int,
    val host: String,
    val port: Int,
    val sessionId: String,
    val receiverPublicKey: String,
    val deviceName: String,
) {
    fun encode(): String = buildString {
        append("yumi://pair?")
        append(
            listOf(
                "v" to version.toString(),
                "host" to host,
                "port" to port.toString(),
                "sid" to sessionId,
                "key" to receiverPublicKey,
                "name" to deviceName,
            ).joinToString("&") { (name, value) -> "$name=${value.urlEncoded()}" },
        )
    }

    companion object {
        fun decode(raw: String): PairingInvite? = runCatching {
            val uri = URI(raw.trim())
            if (uri.scheme != "yumi" || uri.host != "pair") return null
            val values = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null else part.substring(0, separator) to
                    URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8.name())
            }.toMap()
            PairingInvite(
                version = values.getValue("v").toInt(),
                host = values.getValue("host"),
                port = values.getValue("port").toInt(),
                sessionId = values.getValue("sid"),
                receiverPublicKey = values.getValue("key"),
                deviceName = values["name"].orEmpty(),
            ).takeIf {
                it.version == VERSION && it.port in 1..65535 &&
                    Base64.getUrlDecoder().decode(it.sessionId).size >= 16 &&
                    decodePublicKey(it.receiverPublicKey) != null
            }
        }.getOrNull()
    }
}

/** Subscription source only: runtime state and the installation identity never leave the phone. */
@Serializable
data class SubscriptionTransfer(
    val version: Int = VERSION,
    val name: String,
    val url: String,
    val userAgentOverride: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    fun validate(): SubscriptionTransfer? = takeIf {
        version == VERSION &&
            name.length in 1..256 &&
            url.length in 8..16_384 &&
            (url.startsWith("https://", true) || url.startsWith("http://", true)) &&
            (userAgentOverride == null ||
                userAgentOverride.length <= 1_024 && '\n' !in userAgentOverride && '\r' !in userAgentOverride) &&
            headers.size <= 32 && headers.all { (key, value) ->
                !key.equals("x-hwid", ignoreCase = true) &&
                    key.length in 1..128 && value.length <= 4_096 && '\n' !in key && '\r' !in key &&
                    '\n' !in value && '\r' !in value
            }
    }
}

@Serializable
data class PairingResult(
    val accepted: Boolean,
    val status: String,
    val subscriptionName: String? = null,
)

@Serializable
internal data class RequestEnvelope(
    val sessionId: String,
    val senderPublicKey: String,
    val iv: String,
    val ciphertext: String,
)

@Serializable
internal data class ReplyEnvelope(val iv: String, val ciphertext: String)

internal object PairingCrypto {
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val random = SecureRandom()

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }.generateKeyPair()

    fun sessionId(): String = ByteArray(18).also(random::nextBytes).base64Url()

    fun publicKey(key: PublicKey): String = key.encoded.base64Url()

    fun request(invite: PairingInvite, transfer: SubscriptionTransfer): Pair<RequestEnvelope, ByteArray> {
        val sender = keyPair()
        val receiver = requireNotNull(decodePublicKey(invite.receiverPublicKey))
        val key = derive(sender, receiver, invite.sessionId)
        val sealed = seal(json.encodeToString(transfer).toByteArray(), key, aad(invite.sessionId, "request"))
        return RequestEnvelope(
            sessionId = invite.sessionId,
            senderPublicKey = publicKey(sender.public),
            iv = sealed.first.base64Url(),
            ciphertext = sealed.second.base64Url(),
        ) to key
    }

    fun openRequest(envelope: RequestEnvelope, receiver: KeyPair): Pair<SubscriptionTransfer, ByteArray> {
        val sender = requireNotNull(decodePublicKey(envelope.senderPublicKey))
        val key = derive(receiver, sender, envelope.sessionId)
        val plain = open(
            envelope.iv.base64UrlBytes(),
            envelope.ciphertext.base64UrlBytes(),
            key,
            aad(envelope.sessionId, "request"),
        )
        return json.decodeFromString<SubscriptionTransfer>(plain.decodeToString()) to key
    }

    fun reply(result: PairingResult, key: ByteArray, sessionId: String): ReplyEnvelope {
        val sealed = seal(json.encodeToString(result).toByteArray(), key, aad(sessionId, "reply"))
        return ReplyEnvelope(sealed.first.base64Url(), sealed.second.base64Url())
    }

    fun openReply(envelope: ReplyEnvelope, key: ByteArray, sessionId: String): PairingResult {
        val plain = open(
            envelope.iv.base64UrlBytes(),
            envelope.ciphertext.base64UrlBytes(),
            key,
            aad(sessionId, "reply"),
        )
        return json.decodeFromString(plain.decodeToString())
    }

    private fun derive(ours: KeyPair, theirs: PublicKey, sessionId: String): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ours.private)
        agreement.doPhase(theirs, true)
        val shared = agreement.generateSecret()
        val salt = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray())
        val extract = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(shared)
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(extract, "HmacSHA256"))
            doFinal("yumi-pair-v1\u0001".toByteArray()).copyOf(32)
        }
    }

    private fun seal(plain: ByteArray, key: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return iv to cipher.doFinal(plain)
    }

    private fun open(iv: ByteArray, sealed: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed)
    }

    private fun aad(sessionId: String, direction: String) =
        "yumi-pair-v1:$sessionId:$direction".toByteArray()
}

internal fun decodePublicKey(encoded: String): PublicKey? = runCatching {
    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded.base64UrlBytes()))
}.getOrNull()

internal fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

internal fun String.base64UrlBytes(): ByteArray = Base64.getUrlDecoder().decode(this)

private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

const val VERSION = 1
const val MAX_FRAME_BYTES = 64 * 1024
const val PAIRING_LIFETIME_MILLIS = 5 * 60 * 1000L
