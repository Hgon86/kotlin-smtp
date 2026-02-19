package io.github.kotlinsmtp.model

/**
 * SMTP user model.
 *
 * - The minimal unit returned by [SmtpUserHandler] implementation as a lookup result.
 * - Provides email representation to be reused in display/logs/protocol responses.
 *
 * @property localPart Email local part (before @)
 * @property domain Email domain (after @)
 * @property username Display username (optional)
 */
public class SmtpUser(
    public val localPart: String,
    public val domain: String,
    public val username: String? = null,
) {
    /** Completed email address (localPart@domain) */
    public val email: String
        get() = "$localPart@$domain"

    /** String representation for use in SMTP responses/logs */
    public val stringRepresentation: String by lazy {
        val e = email
        if (username != null) "$username <$e>" else e
    }
}
