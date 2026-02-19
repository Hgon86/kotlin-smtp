package io.github.kotlinsmtp.relay.jakarta

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.MXRecord
import org.xbill.DNS.Name
import kotlin.random.Random

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

    @Test
    fun `mx ties are randomized but lower preference still comes first`() {
        val records = listOf(
            MxCandidate("mx10-a", 10),
            MxCandidate("mx10-b", 10),
            MxCandidate("mx20", 20),
        )

        val ordered = MxLookupPolicy.orderByPriorityWithRandomizedTies(
            records = records,
            prioritySelector = { it.priority },
            random = Random(7),
        )

        assertTrue(ordered.take(2).all { it.priority == 10 })
        assertTrue(ordered.last().priority == 20)
        assertTrue(ordered.map { it.host }.toSet() == records.map { it.host }.toSet())
    }

    private data class MxCandidate(
        val host: String,
        val priority: Int,
    )
}
