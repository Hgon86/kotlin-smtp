package io.github.kotlinsmtp.storage

/**
 * Trigger policy for sent-mailbox archiving.
 */
enum class SentArchiveMode {
    /** Disable archive feature. */
    DISABLED,

    /** Archive only messages submitted in authenticated AUTH sessions. */
    AUTHENTICATED_ONLY,

    /** Archive messages from authenticated AUTH or externally relayed submissions that passed policy. */
    TRUSTED_SUBMISSION,
}
