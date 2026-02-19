package io.github.kotlinsmtp.relay.jakarta

import org.xbill.DNS.MXRecord
import org.xbill.DNS.Name
import kotlin.random.Random

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

    /**
     * Orders MX candidates by preference and randomizes ties at the same priority.
     *
     * @param records candidate records
     * @param prioritySelector selector for MX preference
     * @param random random source for tie shuffling
     * @return ordered records
     */
    fun <T> orderByPriorityWithRandomizedTies(
        records: List<T>,
        prioritySelector: (T) -> Int,
        random: Random = Random.Default,
    ): List<T> {
        if (records.size <= 1) return records
        return records
            .groupBy(prioritySelector)
            .toSortedMap()
            .values
            .flatMap { samePriority -> samePriority.shuffled(random) }
    }
}
