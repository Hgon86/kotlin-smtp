package io.github.kotlinsmtp.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboundRoutingPolicyTest {
    @Test
    fun singleDomainPolicy_matchesCaseInsensitiveDomain() {
        val policy = SingleDomainRoutingPolicy("Example.COM")

        assertTrue(policy.isLocalDomain("user@example.com"))
        assertTrue(policy.isLocalDomain("user@EXAMPLE.COM"))
        assertFalse(policy.isLocalDomain("user@other.com"))
        assertEquals(setOf("example.com"), policy.localDomains())
    }

    @Test
    fun multiDomainPolicy_matchesAnyConfiguredDomain() {
        val policy = MultiDomainRoutingPolicy(setOf("A.COM", "b.org "))

        assertTrue(policy.isLocalDomain("u@a.com"))
        assertTrue(policy.isLocalDomain("u@B.ORG"))
        assertFalse(policy.isLocalDomain("u@c.net"))
        assertEquals(setOf("a.com", "b.org"), policy.localDomains())
    }

    @Test
    fun functionalPolicy_defaultLocalDomainsIsEmpty() {
        val policy = InboundRoutingPolicy { recipient -> recipient.endsWith("@example.com") }

        assertTrue(policy.isLocalDomain("u@example.com"))
        assertFalse(policy.isLocalDomain("u@other.com"))
        assertEquals(emptySet(), policy.localDomains())
    }
}
