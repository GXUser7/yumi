package com.mydrop.vpn.core.xray

import com.mydrop.vpn.xray.yumi.Yumi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one thing the Go side and the Kotlin side have to agree about, checked by the compiler's
 * arithmetic rather than by two comments hoping to stay in step.
 *
 * `BalancerTag` and `NodeTagPrefix` are `const` on both sides, so gomobile emits them as compile-
 * time constants and reading them here loads no native library — which is why this can be a plain
 * JVM test at all.
 *
 * The failure it guards against has no symptom worth the name. Change the prefix in one file and
 * the balancer's selector matches nothing, so it has no candidates; the byte counters, which are
 * summed over the same prefix, stay at zero. A tunnel that routes nowhere and reports no traffic,
 * with nothing in either log saying which of the two spellings was the wrong one.
 */
class XrayTagContractTest {

    @Test
    fun `the balancer tag is spelled the same in Go and in Kotlin`() {
        assertEquals(Yumi.BalancerTag, XrayConfigFactory.BALANCER_TAG)
    }

    @Test
    fun `node tags are built with the prefix the binding selects by`() {
        assertTrue(XrayConfigFactory.nodeTag("abc").startsWith(Yumi.NodeTagPrefix))
        assertEquals(Yumi.NodeTagPrefix + "abc", XrayConfigFactory.nodeTag("abc"))
    }

    /**
     * The balancer is not an outbound, so its tag must not be one either — and it must not fall
     * inside its own selector, or it would try to choose itself.
     */
    @Test
    fun `the balancer tag is not itself a candidate`() {
        assertTrue(
            "the balancer would select itself",
            !Yumi.BalancerTag.startsWith(Yumi.NodeTagPrefix),
        )
    }
}
