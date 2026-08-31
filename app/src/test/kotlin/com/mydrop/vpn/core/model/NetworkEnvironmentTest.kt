package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a change of network is allowed to move the tunnel.
 *
 * Every rule here exists because of something a phone actually did. The asymmetry between the two
 * settling times is the doorway problem: at the edge of a network a phone flickers for as long as
 * somebody stands there. The `None` case is the lift, which this app has already once mistaken for
 * three servers dying at once.
 */
class NetworkEnvironmentTest {

    private val mobile = setOf("m1", "m2")

    /**
     * Confirming Wi-Fi has to be slower than confirming cellular, or standing in a doorway moves
     * the tunnel back and forth for as long as the person stands there.
     */
    @Test
    fun `coming back to wifi is confirmed more slowly than leaving for cellular`() {
        val toCellular = NetworkEnvironment.settleMillis(NetworkTransport.Cellular)
        val toWifi = NetworkEnvironment.settleMillis(NetworkTransport.Wifi)

        assertTrue("wifi $toWifi ms, cellular $toCellular ms", toWifi > toCellular)
        // And leaving must stay quick enough that walking out of the door is not a visible wait.
        assertTrue("leaving took $toCellular ms", toCellular <= 5_000L)
    }

    /** A phone with no network at all decides nothing. That is the lift. */
    @Test
    fun `no network is not something to act on`() {
        assertFalse(NetworkEnvironment.actionable(NetworkTransport.None))
        assertFalse(NetworkEnvironment.actionable(NetworkTransport.Other))
        assertTrue(NetworkEnvironment.actionable(NetworkTransport.Cellular))
        assertTrue(NetworkEnvironment.actionable(NetworkTransport.Wifi))
    }

    @Test
    fun `cellular moves the tunnel onto the mobile list`() {
        assertTrue(NetworkEnvironment.wantsMobileServer(NetworkTransport.Cellular, mobile, "wifi-1"))
    }

    /** Already there is nothing to do — including when the user put it there by hand. */
    @Test
    fun `a server already on the list is left alone`() {
        assertFalse(NetworkEnvironment.wantsMobileServer(NetworkTransport.Cellular, mobile, "m1"))
    }

    /**
     * The distinction the whole mobile feature turned out to rest on, and the one it was missing.
     *
     * "Nothing to move" is not "anywhere will do". A courier's journal has the tunnel on an
     * Estonian mobile server over LTE, that server dying, and the failover — which asked only the
     * moving question — drawing from the Wi-Fi spares and landing on France, then fifteen minutes
     * later on Latvia. Both answers below describe the same phone in the same second.
     */
    @Test
    fun `a server already on the list is still confined to it`() {
        assertFalse(NetworkEnvironment.wantsMobileServer(NetworkTransport.Cellular, mobile, "m1"))
        assertTrue(NetworkEnvironment.restrictsToMobileList(NetworkTransport.Cellular, mobile))
    }

    /** An empty list is the feature switched off, and constrains nothing. */
    @Test
    fun `an empty list confines nothing`() {
        assertFalse(NetworkEnvironment.restrictsToMobileList(NetworkTransport.Cellular, emptySet()))
    }

    /**
     * Only cellular. `Other` is the honest default of a controller that cannot tell what it is on
     * — see TunnelController.transport — and confining the tunnel on the strength of a shrug would
     * cut the spares down for a reason that does not exist.
     */
    @Test
    fun `no other network confines the tunnel to the mobile list`() {
        assertFalse(NetworkEnvironment.restrictsToMobileList(NetworkTransport.Wifi, mobile))
        assertFalse(NetworkEnvironment.restrictsToMobileList(NetworkTransport.Other, mobile))
        assertFalse(NetworkEnvironment.restrictsToMobileList(NetworkTransport.None, mobile))
    }

    /**
     * Whenever the tunnel is told to move onto the list, it is also confined to it. The reverse
     * does not hold, and that gap is exactly where the bug lived.
     */
    @Test
    fun `moving onto the list implies being confined to it`() {
        for (transport in NetworkTransport.entries) {
            for (id in listOf<String?>("m1", "wifi-1", null)) {
                if (NetworkEnvironment.wantsMobileServer(transport, mobile, id)) {
                    assertTrue(
                        "$transport / $id moves onto the list without being held to it",
                        NetworkEnvironment.restrictsToMobileList(transport, mobile),
                    )
                }
            }
        }
    }

    /** An empty list means the feature does not exist, on any network. */
    @Test
    fun `an empty list never moves anything`() {
        assertFalse(NetworkEnvironment.wantsMobileServer(NetworkTransport.Cellular, emptySet(), "x"))
        assertFalse(NetworkEnvironment.wantsOrdinaryServer(NetworkTransport.Wifi, emptySet(), "x"))
    }

    @Test
    fun `wifi brings the tunnel back off the mobile list`() {
        assertTrue(NetworkEnvironment.wantsOrdinaryServer(NetworkTransport.Wifi, mobile, "m2"))
    }

    /**
     * Reaching Wi-Fi while already on an ordinary server is not a return, and treating it as one
     * would be a switch nobody asked for — most often right after the user chose a server by hand.
     */
    @Test
    fun `reaching wifi on an ordinary server changes nothing`() {
        assertFalse(NetworkEnvironment.wantsOrdinaryServer(NetworkTransport.Wifi, mobile, "wifi-1"))
    }

    /** Neither rule fires without a network, whatever the lists say. */
    @Test
    fun `nothing moves while the phone has no network`() {
        assertFalse(NetworkEnvironment.wantsMobileServer(NetworkTransport.None, mobile, "wifi-1"))
        assertFalse(NetworkEnvironment.wantsOrdinaryServer(NetworkTransport.None, mobile, "m1"))
    }

    /** A tunnel with no server is not a situation to reason about. */
    @Test
    fun `no current server means no decision`() {
        assertFalse(NetworkEnvironment.wantsMobileServer(NetworkTransport.Cellular, mobile, null))
        assertFalse(NetworkEnvironment.wantsOrdinaryServer(NetworkTransport.Wifi, mobile, null))
    }

    /** The two rules must never both be true, or the tunnel would have two places to be. */
    @Test
    fun `the two rules never agree`() {
        val cases = listOf<String?>("m1", "wifi-1", null)
        for (transport in NetworkTransport.entries) {
            for (id in cases) {
                val out = NetworkEnvironment.wantsMobileServer(transport, mobile, id)
                val back = NetworkEnvironment.wantsOrdinaryServer(transport, mobile, id)
                assertEquals("$transport / $id said both", false, out && back)
            }
        }
    }
}
