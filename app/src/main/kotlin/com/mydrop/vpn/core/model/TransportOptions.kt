package com.mydrop.vpn.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The v2ray-style transport layered under a proxy protocol. `null` transport means raw TCP,
 * which is what VLESS+REALITY+Vision normally uses.
 */
@Serializable
sealed interface TransportOptions {

    /** Label used on server cards; kept short because it sits in a dense badge row. */
    val label: String

    @Serializable
    @SerialName("ws")
    data class WebSocket(
        val path: String = "/",
        val headers: Map<String, String> = emptyMap(),
        val maxEarlyData: Int = 0,
        val earlyDataHeaderName: String? = null,
    ) : TransportOptions {
        override val label: String get() = "WS"
    }

    @Serializable
    @SerialName("grpc")
    data class Grpc(
        val serviceName: String = "",
        val permitWithoutStream: Boolean = false,
    ) : TransportOptions {
        override val label: String get() = "gRPC"
    }

    @Serializable
    @SerialName("http")
    data class Http(
        val host: List<String> = emptyList(),
        val path: String = "/",
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
    ) : TransportOptions {
        override val label: String get() = "HTTP"
    }

    @Serializable
    @SerialName("httpupgrade")
    data class HttpUpgrade(
        val host: String = "",
        val path: String = "/",
        val headers: Map<String, String> = emptyMap(),
    ) : TransportOptions {
        override val label: String get() = "HTTPUpgrade"
    }

    /**
     * XHTTP, which Xray also answers to as `splithttp`.
     *
     * The transport this port exists for. A subscription started handing out `type=xhttp` nodes,
     * sing-box has no implementation of it and never will, and the parser of the day dropped the
     * name and produced ordinary-looking VLESS — servers that connected, showed a plausible
     * latency, and failed every single request underneath.
     */
    @Serializable
    @SerialName("xhttp")
    data class Xhttp(
        val path: String = "/",
        val host: String = "",
        /** `auto`, `packet-up`, `stream-up`, `stream-one`; the core decides when left as `auto`. */
        val mode: String = "auto",
        val headers: Map<String, String> = emptyMap(),
    ) : TransportOptions {
        override val label: String get() = "XHTTP"
    }

    @Serializable
    @SerialName("quic")
    data object Quic : TransportOptions {
        override val label: String get() = "QUIC"
    }
}

@Serializable
data class MultiplexOptions(
    val enabled: Boolean = false,
    val protocol: String = "h2mux",
    val maxConnections: Int = 4,
    val minStreams: Int = 4,
    val padding: Boolean = false,
)
