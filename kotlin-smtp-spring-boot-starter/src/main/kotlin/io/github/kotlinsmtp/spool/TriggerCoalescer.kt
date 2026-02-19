package io.github.kotlinsmtp.spool

/**
 * Queue for coalescing spool trigger requests.
 *
 * Rules:
 * - Full trigger has priority over domain trigger and absorbs queued domain triggers.
 * - Domain triggers merge duplicate domains into a single entry.
 */
internal class TriggerCoalescer {
    private var fullPending = false
    private val domains = linkedSetOf<String>()

    /**
     * Applies trigger request to the queue.
     *
     * @param domain null for full trigger, value for domain trigger
     */
    fun submit(domain: String?) {
        if (domain == null) {
            markFullPending()
        } else if (!fullPending) {
            domains.add(domain)
        }
    }

    private fun markFullPending() {
        fullPending = true
        domains.clear()
    }

    /**
     * Returns the next trigger to execute.
     *
     * @return null if no pending trigger exists
     */
    fun poll(): SpoolTrigger? = when {
        fullPending -> {
            fullPending = false
            domains.clear()
            SpoolTrigger.Full
        }
        domains.isNotEmpty() -> {
            val domain = domains.first().also { domains.remove(it) }
            SpoolTrigger.Domain(domain)
        }
        else -> null
    }
}

/**
 * Represents a spool trigger execution unit.
 */
internal sealed interface SpoolTrigger {
    /** Full-queue trigger. */
    data object Full : SpoolTrigger

    /**
     * Domain-scoped trigger.
     *
     * @property domain domain normalized to IDNA ASCII
     */
    data class Domain(val domain: String) : SpoolTrigger
}
