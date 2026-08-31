package com.mydrop.vpn.core.model

/**
 * How long a change of network has to hold before the tunnel is moved because of it.
 *
 * Not a nicety, and not symmetric. Phones do not step cleanly from one network to another:
 * firmwares shuttle them back and forth on their own — Samsung's Adaptive Wi-Fi, Xiaomi's WLAN+,
 * Huawei's Smart Data Saver all switch networks to chase a better signal — and a field journal
 * from this app caught `wlan0 → rmnet16 → wlan0` inside twenty seconds while its owner walked to
 * the shops. Acting on the first callback would have moved the tunnel three times in that stretch,
 * and every move is somebody's downloads and calls.
 *
 * So each direction waits, and they wait for different reasons.
 */
object NetworkEnvironment {

    /**
     * Cellular is confirmed quickly, because until it is, the phone is on the network it just
     * left. Four seconds covers the ordinary handover plus a router that blinked, and nobody
     * walking out of their door waits noticeably longer for the tunnel to follow.
     */
    const val TO_CELLULAR_MILLIS = 4_000L

    /**
     * Wi-Fi is confirmed slowly, and the asymmetry is the whole point. The edge of a network is
     * where a phone flickers between it and cellular for as long as somebody stands there — at a
     * gate, in a doorway, by a lift. Twelve seconds means the phone is properly inside the
     * coverage rather than brushing against it, and the cost of being wrong in this direction is
     * only that the tunnel spends a few more seconds on a mobile server that works.
     */
    const val TO_WIFI_MILLIS = 12_000L

    fun settleMillis(target: NetworkTransport): Long =
        if (target == NetworkTransport.Cellular) TO_CELLULAR_MILLIS else TO_WIFI_MILLIS

    /**
     * Whether a transport is worth acting on at all.
     *
     * [NetworkTransport.None] is a phone with no network, and moving the tunnel then would repeat
     * the mistake this app has already made once — blaming servers for a lift. [NetworkTransport.Other]
     * is Ethernet, a dock, something unusual; there is no list for it and no reason to invent one.
     */
    fun actionable(transport: NetworkTransport): Boolean =
        transport == NetworkTransport.Cellular || transport == NetworkTransport.Wifi

    /**
     * Whether the mobile list is the only place cellular traffic may go, right now.
     *
     * [wantsMobileServer] answers a neighbouring question — whether the tunnel needs *moving*
     * onto the list — and answers no the moment it is already there. That is right for a change
     * of network and wrong for everything else, because it makes the list a one-off correction
     * rather than a standing constraint: a mobile server dying under a phone already on the list
     * left the failover free to draw from the ordinary spares. A field journal has a courier on
     * LTE carried from an Estonian mobile server onto France, and fifteen minutes later onto
     * Latvia, both from the Wi-Fi list, because nothing between the two questions was asking
     * which list was allowed at all.
     *
     * So the constraint is stated separately from the correction, and both are read from here.
     */
    fun restrictsToMobileList(transport: NetworkTransport, mobileIds: Set<String>): Boolean =
        transport == NetworkTransport.Cellular && mobileIds.isNotEmpty()

    /**
     * Whether the tunnel should move onto the mobile list.
     *
     * @param currentId the server carrying traffic now. Already being on the list means there is
     *   nothing to do — including when the user picked it themselves.
     */
    fun wantsMobileServer(
        transport: NetworkTransport,
        mobileIds: Set<String>,
        currentId: String?,
    ): Boolean = transport == NetworkTransport.Cellular &&
        mobileIds.isNotEmpty() &&
        currentId != null &&
        currentId !in mobileIds

    /**
     * Whether the tunnel should come back off the mobile list.
     *
     * Only when it is actually on one. A phone that reaches Wi-Fi while already on an ordinary
     * server has nothing to return from, and moving it would be a switch nobody asked for.
     */
    fun wantsOrdinaryServer(
        transport: NetworkTransport,
        mobileIds: Set<String>,
        currentId: String?,
    ): Boolean = transport == NetworkTransport.Wifi &&
        mobileIds.isNotEmpty() &&
        currentId != null &&
        currentId in mobileIds
}
