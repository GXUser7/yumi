package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUserInfo
import com.mydrop.vpn.core.parse.ProxyUriParser
import java.util.Base64

/**
 * Sample servers used to populate a fresh install so the interface can be judged with real
 * content. Every host is under `example.com`, which is reserved by RFC 2606 and can never
 * resolve to a working proxy — the entries are unmistakably examples, not live servers.
 *
 * Built by running the real parser over real share-links, so the seed doubles as a smoke test
 * of [ProxyUriParser] on every cold start.
 */
object DemoData {

    const val SUBSCRIPTION_ID = "demo-subscription"

    private val vmessLink: String by lazy {
        val json = """
            {"v":"2","ps":"Демо · Париж VMess+WS","add":"fr.example.com","port":"443",
             "id":"11111111-2222-3333-4444-555555555555","aid":"0","scy":"auto","net":"ws",
             "host":"fr.example.com","path":"/vm","tls":"tls","sni":"fr.example.com","fp":"chrome"}
        """.trimIndent().replace("\n", "")
        "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
    }

    private val links: List<String> by lazy {
        listOf(
            "vless://11111111-2222-3333-4444-555555555555@de.example.com:443" +
                "?security=reality&sni=www.microsoft.com&fp=chrome" +
                "&pbk=xR8LmN2pQvT7yZ4aB6cD9eF1gH3jK5lM7nP9qR2sT4U&sid=a1b2c3d4" +
                "&type=tcp&flow=xtls-rprx-vision#Демо · Франкфурт REALITY",

            "vless://11111111-2222-3333-4444-555555555555@nl.example.com:8443" +
                "?security=tls&type=ws&path=%2Fws&host=nl.example.com&fp=firefox" +
                "#Демо · Амстердам VLESS+WS",

            "trojan://demo-password@fi.example.com:443" +
                "?sni=fi.example.com&type=grpc&serviceName=trojan-grpc" +
                "#Демо · Хельсинки Trojan+gRPC",

            "hysteria2://demo-password@se.example.com:443" +
                "?sni=se.example.com&obfs=salamander&obfs-password=demo-obfs" +
                "#Демо · Стокгольм Hysteria2",

            "tuic://11111111-2222-3333-4444-555555555555:demo-password@jp.example.com:443" +
                "?sni=jp.example.com&congestion_control=bbr&alpn=h3" +
                "#Демо · Токио TUIC",

            "ss://YWVzLTI1Ni1nY206ZGVtby1wYXNzd29yZA@us.example.com:8388" +
                "#Демо · Нью-Йорк Shadowsocks",

            "anytls://demo-password@uk.example.com:443?sni=uk.example.com" +
                "#Демо · Лондон AnyTLS",

            vmessLink,
        )
    }

    fun nodes(): List<ProxyNode> = ProxyUriParser.parseAll(links.joinToString("\n"), SUBSCRIPTION_ID)

    fun subscription(nodeIds: List<String>): Subscription = Subscription(
        id = SUBSCRIPTION_ID,
        name = "Демо-набор",
        url = "https://example.com/subscription/demo",
        nodeIds = nodeIds,
        userInfo = SubscriptionUserInfo(
            uploadBytes = 4_294_967_296,
            downloadBytes = 68_719_476_736,
            totalBytes = 214_748_364_800,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + 27L * 86_400,
        ),
        lastUpdatedEpochMillis = System.currentTimeMillis(),
        remoteTitle = "Демонстрационные серверы",
    )
}
