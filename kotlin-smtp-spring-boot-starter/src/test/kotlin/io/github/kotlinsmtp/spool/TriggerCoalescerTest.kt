package io.github.kotlinsmtp.spool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TriggerCoalescerTest {

    /**
     * Duplicate domain triggers should be coalesced into one.
     */
    @Test
    fun `duplicate domain triggers are coalesced`() {
        val coalescer = TriggerCoalescer()

        coalescer.submit("example.com")
        coalescer.submit("example.com")
        coalescer.submit("example.com")

        assertEquals(SpoolTrigger.Domain("example.com"), coalescer.poll())
        assertNull(coalescer.poll())
    }

    /**
     * Full trigger should absorb existing domain triggers.
     */
    @Test
    fun `full trigger absorbs pending domains`() {
        val coalescer = TriggerCoalescer()

        coalescer.submit("a.com")
        coalescer.submit("b.com")
        coalescer.submit(null)

        assertEquals(SpoolTrigger.Full, coalescer.poll())
        assertNull(coalescer.poll())
    }

    /**
     * Additional domain triggers should be ignored while full trigger is pending.
     */
    @Test
    fun `domain trigger is ignored while full trigger pending`() {
        val coalescer = TriggerCoalescer()

        coalescer.submit(null)
        coalescer.submit("ignored.com")

        assertEquals(SpoolTrigger.Full, coalescer.poll())
        assertNull(coalescer.poll())
    }
}
