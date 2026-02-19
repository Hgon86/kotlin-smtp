package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.model.SmtpUser


/**
 * Extension point for user lookup/verification.
 *
 * - Used when returning user list in VRFY, etc.
 */
public abstract class SmtpUserHandler {
    /**
     * @param searchTerm Search term (email/id, etc., depending on implementation policy)
     * @return Matched user list (empty collection when none)
     */
    public abstract fun verify(searchTerm: String): Collection<SmtpUser>
}
