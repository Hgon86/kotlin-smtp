package io.github.kotlinsmtp.server

import kotlin.test.Test
import kotlin.test.assertEquals

class SmtpSpoolerTest {
    @Test
    fun tryTriggerOnce_returnsAcceptedWhenTriggerSucceeds() {
        val spooler = object : SmtpSpooler {
            override fun triggerOnce() = Unit
        }

        assertEquals(SpoolTriggerResult.ACCEPTED, spooler.tryTriggerOnce())
    }

    @Test
    fun tryTriggerOnce_returnsUnavailableWhenTriggerThrows() {
        val spooler = object : SmtpSpooler {
            override fun triggerOnce() {
                error("spooler down")
            }
        }

        assertEquals(SpoolTriggerResult.UNAVAILABLE, spooler.tryTriggerOnce())
    }

    @Test
    fun domainTryTriggerOnce_returnsAcceptedWhenDomainTriggerSucceeds() {
        var capturedDomain: String? = null
        val spooler = object : SmtpDomainSpooler {
            override fun triggerOnce() = Unit

            override fun triggerOnce(domain: String) {
                capturedDomain = domain
            }
        }

        assertEquals(SpoolTriggerResult.ACCEPTED, spooler.tryTriggerOnce("example.com"))
        assertEquals("example.com", capturedDomain)
    }

    @Test
    fun domainTryTriggerOnce_returnsUnavailableWhenDomainTriggerThrows() {
        val spooler = object : SmtpDomainSpooler {
            override fun triggerOnce() = Unit

            override fun triggerOnce(domain: String) {
                throw IllegalStateException("busy")
            }
        }

        assertEquals(SpoolTriggerResult.UNAVAILABLE, spooler.tryTriggerOnce("example.com"))
    }
}
