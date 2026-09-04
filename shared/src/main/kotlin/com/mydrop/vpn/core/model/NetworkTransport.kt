package com.mydrop.vpn.core.model

/**
 * What kind of network is carrying the tunnel underneath.
 *
 * Read from the physical network the phone would use if the tunnel were not there, never from the
 * tunnel itself: a VpnService interface reports `TRANSPORT_VPN` and would answer every question
 * about the network with a description of us.
 *
 * [Cellular] means cellular and nothing else. The nearby-looking alternative — Android's "metered"
 * flag — describes a billing arrangement rather than a network: a 5G plan can report itself
 * temporarily unmetered, and a home Wi-Fi can be marked metered by whoever owns it. Keying a
 * feature off that would switch it on and off for reasons that have nothing to do with which
 * network the phone is on.
 *
 * [None] is the honest answer while the phone has nothing at all — a lift, a tunnel, airplane
 * mode — and it must not be confused with [Other]. Deciding anything on the strength of "no
 * network" is how a phone in a lift came to look like three dead servers.
 */
enum class NetworkTransport { Wifi, Cellular, Other, None }
