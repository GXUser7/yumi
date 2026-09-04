package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.Subscription
import java.nio.file.Files
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SubscriptionTransferUpsertTest {
    @Test
    fun sameUrlUpdatesCredentialsWithoutDuplicatingOrChangingLocalState() {
        val directory = Files.createTempDirectory("yumi-pair-upsert").toFile()
        try {
            val repository = ProfileRepository(directory, TestScope())
            repository.addSubscription(
                Subscription(
                    id = "local-id",
                    name = "Old",
                    url = "https://example.com/private",
                    enabled = false,
                    headers = mapOf("Old" to "value"),
                ),
            )

            val result = repository.upsertSubscriptionSource(
                name = "Living room",
                url = "https://example.com/private",
                userAgentOverride = "Happ/1.0",
                headers = mapOf("Authorization" to "Bearer token"),
            )

            assertEquals("local-id", result.id)
            assertFalse(result.enabled)
            assertEquals(1, repository.state.value.subscriptions.size)
            assertEquals("Bearer token", result.headers["Authorization"])
        } finally {
            directory.deleteRecursively()
        }
    }
}
