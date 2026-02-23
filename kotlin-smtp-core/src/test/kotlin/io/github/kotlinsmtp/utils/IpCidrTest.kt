package io.github.kotlinsmtp.utils

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IpCidrTest {
    @Test
    fun contains_matchesIpv4WithinCidrRange() {
        val cidr = IpCidr.parse("10.0.0.0/8")

        assertNotNull(cidr)
        assertTrue(cidr.contains(InetAddress.getByName("10.1.2.3")))
        assertFalse(cidr.contains(InetAddress.getByName("11.1.2.3")))
    }

    @Test
    fun parse_withoutPrefixUsesExactAddressMatch() {
        val cidr = IpCidr.parse("192.168.1.10")

        assertNotNull(cidr)
        assertTrue(cidr.contains(InetAddress.getByName("192.168.1.10")))
        assertFalse(cidr.contains(InetAddress.getByName("192.168.1.11")))
    }

    @Test
    fun contains_rejectsMixedIpFamilies() {
        val cidr = IpCidr.parse("10.0.0.0/8")

        assertNotNull(cidr)
        assertFalse(cidr.contains(InetAddress.getByName("::1")))
    }

    @Test
    fun parse_coercesOutOfRangePrefixToAddressBitSize() {
        val cidr = IpCidr.parse("10.1.2.3/999")

        assertNotNull(cidr)
        assertTrue(cidr.contains(InetAddress.getByName("10.1.2.3")))
        assertFalse(cidr.contains(InetAddress.getByName("10.1.2.4")))
    }

    @Test
    fun parse_returnsNullForInvalidInput() {
        assertNull(IpCidr.parse(""))
        assertNull(IpCidr.parse("not-an-ip"))
    }
}
