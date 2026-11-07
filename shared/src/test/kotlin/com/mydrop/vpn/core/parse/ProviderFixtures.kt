package com.mydrop.vpn.core.parse

/**
 * Provider documents shaped like the ones a Remnawave panel hands Happ, with every credential and
 * address replaced. The structure is copied from a real Quattro answer: the same inbounds, the same
 * outbound tags, the same rule shapes in the same order — including the ones that cannot be carried.
 */
object ProviderFixtures {

    const val UUID = "11111111-2222-3333-4444-555555555555"

    private val INBOUNDS = """
        [
          {"tag": "socks", "port": 10808, "listen": "127.0.0.1", "protocol": "socks",
           "settings": {"udp": true, "auth": "noauth"},
           "sniffing": {"enabled": true, "routeOnly": true, "destOverride": ["http", "tls", "quic"]}},
          {"tag": "http", "port": 10809, "listen": "127.0.0.1", "protocol": "http",
           "settings": {"allowTransparent": false},
           "sniffing": {"enabled": true, "routeOnly": true, "destOverride": ["http", "tls", "quic"]}}
        ]
    """.trimIndent()

    private const val DNS = """{"servers": ["8.8.8.8", "1.1.1.1"], "queryStrategy": "UseIP"}"""

    private fun vless(tag: String, address: String) = """
        {"tag": "$tag", "protocol": "vless",
         "settings": {"vnext": [{"address": "$address", "port": 443,
           "users": [{"id": "$UUID", "encryption": "none", "flow": "xtls-rprx-vision"}]}]},
         "streamSettings": {"network": "tcp", "security": "reality",
           "realitySettings": {"serverName": "decoy.example.com", "fingerprint": "chrome",
             "publicKey": "AAAA", "shortId": "0123"}}}
    """.trimIndent()

    private const val PLUMBING = """
        {"tag": "direct", "protocol": "freedom"},
        {"tag": "block", "protocol": "blackhole"}
    """

    /** The Russian list as Quattro writes it, cut to a few entries of each kind. */
    private const val RUSSIAN = """["max.ru", "full:go.yandex", "domain:2gis.ru", "domain:1c.ru", "geosite:category-ru"]"""

    /** An ordinary server: one proxy, its torrents sent around it. */
    fun ordinary(remarks: String = "🇩🇪 Германия #2"): String = """
        {"remarks": "$remarks", "dns": $DNS, "inbounds": $INBOUNDS,
         "outbounds": [${vless("proxy", "203.0.113.10")}, $PLUMBING],
         "routing": {"domainStrategy": "IPIfNonMatch", "rules": [
           {"type": "field", "domain": ["domain:2ip.ru"], "outboundTag": "proxy"},
           {"type": "field", "protocol": ["quic"], "outboundTag": "block"},
           {"type": "field", "protocol": ["bittorrent"], "outboundTag": "direct"},
           {"domain": $RUSSIAN, "outboundTag": "direct"}
         ]}}
    """.trimIndent()

    /** A torrent server: the same rules without the one that keeps torrents off it. */
    fun torrent(remarks: String = "🇳🇱 Нидерланды | Torrent ✅"): String = """
        {"remarks": "$remarks", "dns": $DNS, "inbounds": $INBOUNDS,
         "outbounds": [${vless("proxy", "203.0.113.20")}, $PLUMBING],
         "routing": {"domainStrategy": "IPIfNonMatch", "rules": [
           {"type": "field", "domain": ["domain:2ip.ru"], "outboundTag": "proxy"},
           {"type": "field", "protocol": ["quic"], "outboundTag": "block"},
           {"domain": $RUSSIAN, "outboundTag": "direct"}
         ]}}
    """.trimIndent()

    /** An auto-select server: a balancer, and a catch-all bound to the provider's own inbounds. */
    fun auto(remarks: String = "🇳🇱 LTE Авто - Нидерланды"): String = """
        {"remarks": "$remarks", "dns": $DNS, "inbounds": $INBOUNDS,
         "outbounds": [${vless("cand-01-a", "203.0.113.31")}, ${vless("cand-02-b", "203.0.113.32")},
                       ${vless("cand-03-c", "203.0.113.33")}, $PLUMBING],
         "observatory": {"subjectSelector": ["cand-01-a", "cand-02-b", "cand-03-c"],
                         "probeUrl": "https://www.google.com/generate_204", "probeInterval": "2m"},
         "routing": {"domainStrategy": "IPIfNonMatch",
           "balancers": [{"tag": "bal_price", "selector": ["cand-02-b", "cand-03-c"],
             "fallbackTag": "cand-01-a",
             "strategy": {"type": "leastLoad", "settings": {"baselines": ["2s"], "maxRTT": "2s",
               "costs": [{"match": "cand-02-b", "regexp": false, "value": 0},
                         {"match": "cand-03-c", "regexp": false, "value": 100}]}}}],
           "rules": [
             {"type": "field", "domain": ["domain:2ip.ru"], "balancerTag": "bal_price"},
             {"type": "field", "protocol": ["quic"], "outboundTag": "block"},
             {"type": "field", "protocol": ["bittorrent"], "outboundTag": "direct"},
             {"domain": $RUSSIAN, "outboundTag": "direct"},
             {"type": "field", "inboundTag": ["socks", "http"], "network": "tcp,udp", "balancerTag": "bal_price"}
           ]}}
    """.trimIndent()

    /** A document whose direct road fragments the handshake: not a plain direct outbound. */
    fun fragmenting(remarks: String = "🇫🇮 Финляндия"): String = """
        {"remarks": "$remarks", "inbounds": $INBOUNDS,
         "outbounds": [${vless("proxy", "203.0.113.40")}, $PLUMBING,
           {"tag": "fragment", "protocol": "freedom",
            "settings": {"fragment": {"packets": "tlshello", "length": "100-200", "interval": "10-20"}}}],
         "routing": {"rules": [
           {"type": "field", "domain": ["domain:youtube.com"], "outboundTag": "fragment"},
           {"type": "field", "domain": ["domain:example.org"], "attrs": {":method": "GET"}, "outboundTag": "direct"},
           {"type": "field", "ip": ["198.51.100.0/24"], "outboundTag": "dns"},
           {"type": "field", "domain": ["domain:2ip.ru"], "outboundTag": "proxy"}
         ]}}
    """.trimIndent()

    fun list(vararg documents: String): String = documents.joinToString(",", prefix = "[", postfix = "]")
}
