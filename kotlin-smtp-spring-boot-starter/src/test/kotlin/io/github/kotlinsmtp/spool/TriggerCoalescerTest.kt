package io.github.kotlinsmtp.spool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TriggerCoalescerTest {

    /**
     * 동일 도메인 트리거는 1건으로 병합되어야 합니다.
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
     * 전체 트리거는 기존 도메인 트리거를 흡수해야 합니다.
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
     * 전체 트리거가 pending일 때 추가 도메인 트리거는 무시되어야 합니다.
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
