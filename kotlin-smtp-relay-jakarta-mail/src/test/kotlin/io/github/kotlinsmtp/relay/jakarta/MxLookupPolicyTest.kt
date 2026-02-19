package io.github.kotlinsmtp.relay.jakarta

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.MXRecord
import org.xbill.DNS.Name

class MxLookupPolicyTest {
    @Test
    fun `single MX 0 dot is treated as explicit null MX`() {
        val records = listOf(
            MXRecord(
                Name.fromString("example.com."),
                DClass.IN,
                300,
                0,
                Name.root,
            ),
        )

        assertTrue(MxLookupPolicy.hasExplicitNullMx(records))
    }

    @Test
    fun `regular MX records are not treated as null MX`() {
        val records = listOf(
            MXRecord(
                Name.fromString("example.com."),
                DClass.IN,
                300,
                10,
                Name.fromString("mx1.example.com."),
            ),
        )

        assertFalse(MxLookupPolicy.hasExplicitNullMx(records))
    }
}
