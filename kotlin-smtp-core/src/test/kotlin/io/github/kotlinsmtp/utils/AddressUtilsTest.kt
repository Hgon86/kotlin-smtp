package io.github.kotlinsmtp.utils

import java.net.IDN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AddressUtilsTest {
    @Test
    fun validateAddress_rejectsCrlfInjection() {
        assertFalse(AddressUtils.validateAddress("user@example.com\r\nRCPT TO:<x@y.com>"))
    }

    @Test
    fun validateSmtpUtf8Address_acceptsUtf8LocalPart() {
        assertTrue(AddressUtils.validateSmtpUtf8Address("테스트@도메인.한국"))
    }

    @Test
    fun normalizeDomainInAddress_convertsIdnDomainToAscii() {
        val expectedDomain = IDN.toASCII("도메인.한국", IDN.ALLOW_UNASSIGNED).lowercase()
        val normalized = AddressUtils.normalizeDomainInAddress("user@도메인.한국")

        assertEquals("user@$expectedDomain", normalized)
    }

    @Test
    fun normalizeValidDomain_rejectsInvalidLabelCharacter() {
        assertEquals(null, AddressUtils.normalizeValidDomain("bad_domain.com"))
    }

    @Test
    fun validateHost_requiresAtPrefixAndValidDomain() {
        assertTrue(AddressUtils.validateHost("@example.com"))
        assertFalse(AddressUtils.validateHost("example.com"))
        assertNotNull(AddressUtils.normalizeDomain("example.com"))
    }
}
