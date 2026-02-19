package io.github.kotlinsmtp.relay.jakarta

import org.xbill.DNS.MXRecord
import org.xbill.DNS.Name

/**
 * MX interpretation policy for outbound SMTP routing.
 */
internal object MxLookupPolicy {
    /**
     * Detects explicit Null MX (`MX 0 .`) defined in RFC 7505.
     *
     * @param records MX records for a domain
     * @return true when domain explicitly does not accept email
     */
    fun hasExplicitNullMx(records: List<MXRecord>): Boolean {
        if (records.size != 1) return false
        val only = records.first()
        return only.priority == 0 && only.target == Name.root
    }
}
