package io.github.kotlinsmtp.model

/**
 * Minimal model for RFC 3461 (DSN) related RCPT TO extension parameters
 *
 * NOTE:
 * - Currently stores only the minimum needed for practical use,
 *   and reflects NOTIFY=NEVER/FAILURE when sending DSN.
 * - ORCPT is reflected in the `Original-Recipient` field when generating DSN.
 *
 * @property notify Original RFC 3461 NOTIFY parameter text
 * @property orcpt Original RFC 3461 ORCPT parameter text
 */
public data class RcptDsn(
    public val notify: String? = null,
    public val orcpt: String? = null,
)
