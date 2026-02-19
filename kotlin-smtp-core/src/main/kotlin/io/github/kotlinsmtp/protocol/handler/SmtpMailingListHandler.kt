package io.github.kotlinsmtp.protocol.handler

/**
 * Handler for EXPN (mailing-list expansion) processing
 *
 * - Feature-first: provide local file/directory based implementation
 * - TODO(DB/MSA): migrate to DB/Directory/Policy service in production
 */
public interface SmtpMailingListHandler {
    /**
     * @param listName EXPN argument (e.g., "dev-team" or "dev-team@example.com")
     * @return Expanded member representation strings (each line used directly in SMTP multiline response)
     */
    public fun expand(listName: String): List<String>
}
