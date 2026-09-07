package com.mydrop.vpn.data

import com.mydrop.vpn.remote.REMOTE_PAIRING_LIFETIME_MILLIS
import com.mydrop.vpn.remote.REMOTE_PORT
import com.mydrop.vpn.remote.RemoteAuthority
import com.mydrop.vpn.remote.RemoteBond
import com.mydrop.vpn.remote.RemoteCrypto
import com.mydrop.vpn.remote.RemoteIdentity
import com.mydrop.vpn.remote.RemotePeer
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Everything the remote control remembers, on both ends of it.
 *
 * One file for two roles, because the two never occur together: a television fills [peers] and a
 * phone fills [bonds], and neither app has any use for the other's half. Splitting it would mean
 * two stores, two migrations and two chances to forget one.
 *
 * The secrets in here are plain text in the app's private directory, which is where the servers'
 * own keys already live — see `profiles.json`. Anything that can read this file can read that one,
 * so encrypting only this would buy nothing but the belief that it had.
 */
@Serializable
data class RemoteState(
    /** The television's own name on the wire. Minted once, then never again. */
    val hostId: String = "",
    val hostPrivateKey: String = "",
    val hostPublicKey: String = "",
    /** Phones this television lets drive it. */
    val peers: List<RemotePeer> = emptyList(),
    /** Televisions this phone has been introduced to. */
    val bonds: List<RemoteBond> = emptyList(),
)

class RemoteRepository(
    directory: File,
    scope: CoroutineScope,
    private val deviceName: () -> String,
    onWriteFailure: (Throwable) -> Unit = {},
) : RemoteAuthority {

    private val store = JsonStore(
        file = File(directory, "remote.json"),
        serializer = RemoteState.serializer(),
        defaultValue = RemoteState(),
        scope = scope,
        onWriteFailure = onWriteFailure,
    )

    val state: StateFlow<RemoteState> = store.state
    val value: RemoteState get() = store.value

    private val lock = ReentrantLock()

    /**
     * The code on the screen right now.
     *
     * In memory and nowhere else: a pairing window that survived a restart would be a code nobody
     * is looking at any more, still opening the set to whoever wrote it down.
     */
    private var pairingCode: String? = null
    private var pairingExpiresAt = 0L

    /**
     * The parsed key pair, kept once it has been read.
     *
     * Parsing a PKCS8 blob through `KeyFactory` is not free, and [identity] is read on every
     * snapshot the television sends — once a second while a phone is watching. Without this the
     * set would spend its evening re-deriving a key that has not changed since it was minted.
     */
    private var cachedIdentity: RemoteIdentity? = null

    /* ── The television ───────────────────────────────────────────────────────────────────── */

    override fun identity(): RemoteIdentity {
        lock.withLock {
            // The name is the one thing that can move under it — a firmware update renaming the
            // device — so it is re-read rather than cached with the keys.
            cachedIdentity?.let { return it.copy(name = deviceName()) }
            val current = store.value
            val secret = RemoteCrypto.privateKeyOf(current.hostPrivateKey)
            val open = RemoteCrypto.publicKeyOf(current.hostPublicKey)
            // The name is read fresh every time rather than stored: it is the set's model, and a
            // firmware update that renames the device should rename it on the phone too.
            if (current.hostId.isNotEmpty() && secret != null && open != null) {
                return RemoteIdentity(current.hostId, deviceName(), open, secret)
                    .also { cachedIdentity = it }
            }
            // Minted on first use rather than at install: a set nobody ever asks to drive from a
            // phone has no reason to hold a key at all.
            val keys = RemoteCrypto.keyPair()
            val id = RemoteCrypto.token(12)
            store.update {
                it.copy(
                    hostId = id,
                    hostPrivateKey = RemoteCrypto.encode(keys.private),
                    hostPublicKey = RemoteCrypto.encode(keys.public),
                )
            }
            return RemoteIdentity(id, deviceName(), keys.public, keys.private)
                .also { cachedIdentity = it }
        }
    }

    /** Opens the window and returns the code to put on screen. */
    fun openPairing(now: Long = System.currentTimeMillis()): String = lock.withLock {
        val code = RemoteCrypto.token()
        pairingCode = code
        pairingExpiresAt = now + REMOTE_PAIRING_LIFETIME_MILLIS
        code
    }

    fun closePairing() = lock.withLock {
        pairingCode = null
        pairingExpiresAt = 0L
    }

    override fun openPairingCode(): String? = lock.withLock {
        pairingCode?.takeIf { System.currentTimeMillis() < pairingExpiresAt }
    }

    override fun acceptPairing(deviceName: String): RemotePeer? {
        val peer = lock.withLock {
            if (openPairingCode() == null) return null
            // Shut behind the first phone through it. The code is one introduction, not a door
            // left open for five minutes.
            pairingCode = null
            pairingExpiresAt = 0L
            RemotePeer(
                id = RemoteCrypto.token(12),
                name = deviceName.ifBlank { "Phone" },
                secret = RemoteCrypto.token(32),
                pairedAtEpochMillis = System.currentTimeMillis(),
                lastSeenEpochMillis = System.currentTimeMillis(),
            )
        }
        // Replaced by name rather than added beside: pairing the same phone twice is somebody
        // redoing an introduction that went wrong, not a second remote, and a list that grew an
        // entry every time would leave the set trusting keys nobody can account for.
        store.update { it.copy(peers = it.peers.filter { known -> known.name != peer.name } + peer) }
        return peer
    }

    override fun secretOf(peerId: String): String? =
        store.value.peers.firstOrNull { it.id == peerId }?.secret

    override fun seen(peerId: String) {
        val now = System.currentTimeMillis()
        store.update { current ->
            current.copy(
                peers = current.peers.map {
                    // A minute's resolution: this fires on every reconnect and the value is only
                    // ever read as "when did this phone last use the set".
                    if (it.id == peerId && now - it.lastSeenEpochMillis > 60_000) {
                        it.copy(lastSeenEpochMillis = now)
                    } else {
                        it
                    }
                },
            )
        }
    }

    fun forgetPeer(peerId: String) =
        store.update { it.copy(peers = it.peers.filterNot { peer -> peer.id == peerId }) }

    fun forgetAllPeers() = store.update { it.copy(peers = emptyList()) }

    /* ── The phone ────────────────────────────────────────────────────────────────────────── */

    fun remember(bond: RemoteBond) = store.update { current ->
        current.copy(bonds = current.bonds.filterNot { it.hostId == bond.hostId } + bond)
    }

    /** Where the set answered this time, so the next session can try it before it searches. */
    fun rememberAddress(hostId: String, host: String, port: Int) = store.update { current ->
        current.copy(
            bonds = current.bonds.map {
                if (it.hostId == hostId && (it.lastHost != host || it.lastPort != port)) {
                    it.copy(lastHost = host, lastPort = port.takeIf { p -> p > 0 } ?: REMOTE_PORT)
                } else {
                    it
                }
            },
        )
    }

    fun forgetBond(hostId: String) =
        store.update { it.copy(bonds = it.bonds.filterNot { bond -> bond.hostId == hostId }) }
}
