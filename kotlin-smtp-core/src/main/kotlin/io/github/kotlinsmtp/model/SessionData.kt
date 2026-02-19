package io.github.kotlinsmtp.model

/**
 * State data accumulated by the engine during SMTP session (connection/transaction) processing.
 *
 * - The engine manages the state machine, and it is used primarily for reading by the external (host).
 * - Mutable objects such as collections only provide read-only views to prevent external mutation.
 *
 * @property rcptDsnView Read-only view of per-recipient DSN (RFC 3461) parameters
 */
public class SessionData {
    // Client identification (HELO/EHLO argument)
    public var helo: String? = null; internal set

    // State machine
    public var greeted: Boolean = false; internal set // Whether HELO/EHLO has been performed
    public var usedEhlo: Boolean = false; internal set // Whether EHLO is used
    public var mailFrom: String? = null; internal set // MAIL FROM address
    public var recipientCount: Int = 0; internal set // RCPT count

    // ESMTP parameters (values declared in MAIL FROM)
    public var mailParameters: Map<String, String> = emptyMap(); internal set // MAIL FROM parameters
    public var declaredSize: Long? = null; internal set // SIZE parameter value

    // RFC 6531 (SMTPUTF8)
    // - If SMTPUTF8 parameter is declared at MAIL FROM stage, this transaction may include UTF-8 addresses/headers.
    // - For now, the minimal implementation covers "accepting addresses + preserving in local/relay paths".
    public var smtpUtf8: Boolean = false; internal set

    // RFC 3461(DSN) - MAIL FROM extension
    // - RET=FULL|HDRS, ENVID=<id>
    public var dsnRet: String? = null; internal set
    public var dsnEnvid: String? = null; internal set

    // RFC 3461(DSN) - RCPT TO extension (per-recipient)
    // - NOTIFY=..., ORCPT=...
    // TODO(standard DSN): Reflect each recipient's options when generating RFC 3464
    internal var rcptDsn: MutableMap<String, RcptDsn> = mutableMapOf()

    /** Only provide read-only view to prevent external mutation of DSN state. */
    public val rcptDsnView: Map<String, RcptDsn>
        get() = java.util.Collections.unmodifiableMap(rcptDsn)

    // Connection context
    public var peerAddress: String? = null; internal set // Client IP:port
    public var serverHostname: String? = null; internal set // Server hostname
    public var tlsActive: Boolean = false; internal set // Whether TLS is used

    // AUTH state
    public var authFailedAttempts: Int? = null; internal set
    public var authLockedUntilEpochMs: Long? = null; internal set
    public var isAuthenticated: Boolean = false; internal set
    public var authenticatedUsername: String? = null; internal set
}
