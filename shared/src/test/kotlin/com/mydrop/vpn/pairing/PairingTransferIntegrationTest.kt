package com.mydrop.vpn.pairing

import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.data.ProfileRepository
import java.nio.file.Files
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PairingTransferIntegrationTest {
    @Test
    fun encryptedTransferCopiesSourceToTvAndLeavesPhoneUntouched() {
        val phoneDirectory = Files.createTempDirectory("yumi-phone").toFile()
        val tvDirectory = Files.createTempDirectory("yumi-tv").toFile()
        try {
            val phone = ProfileRepository(phoneDirectory, TestScope())
            val tv = ProfileRepository(tvDirectory, TestScope())
            val original = Subscription(
                id = "phone-internal-id",
                name = "Home subscription",
                url = "https://provider.example/sub/private-token",
                userAgentOverride = "Yumi/mobile",
                headers = mapOf(
                    "Authorization" to "Bearer private",
                    "x-hwid" to "phone-must-not-leave",
                ),
            )
            phone.addSubscription(original)

            val receiverKeys = PairingCrypto.keyPair()
            val invite = PairingInvite(
                VERSION,
                "192.168.1.10",
                43_210,
                PairingCrypto.sessionId(),
                PairingCrypto.publicKey(receiverKeys.public),
                "Yumi TV",
            )
            val outbound = SubscriptionTransfer(
                name = original.name,
                url = original.url,
                userAgentOverride = original.userAgentOverride,
                headers = original.headers.filterKeys { !it.equals("x-hwid", ignoreCase = true) },
            )
            val (sealed, _) = PairingCrypto.request(invite, outbound)
            val (received, _) = PairingCrypto.openRequest(sealed, receiverKeys)
            val tvSubscription = tv.upsertSubscriptionSource(
                received.name,
                received.url,
                received.userAgentOverride,
                received.headers,
            )

            assertEquals(original, phone.state.value.subscriptions.single())
            assertEquals(original.url, tvSubscription.url)
            assertEquals(mapOf("Authorization" to "Bearer private"), tvSubscription.headers)
            assertNotEquals(original.id, tvSubscription.id)
        } finally {
            phoneDirectory.deleteRecursively()
            tvDirectory.deleteRecursively()
        }
    }
}
