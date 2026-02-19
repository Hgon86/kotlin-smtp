package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.model.SmtpUser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Simple user verification handler backed by local filesystem (mailboxDir).
 *
 * - Feature-first: minimal implementation for enabling VRFY in constrained setups.
 * - TODO(DB/MSA): migrate to DB/directory service (or policy service) for production.
 */
class LocalDirectoryUserHandler(
    private val mailboxDir: Path,
    private val localDomain: String,
) : SmtpUserHandler() {
    private val mailboxRoot: Path = mailboxDir.toAbsolutePath().normalize()

    override fun verify(searchTerm: String): Collection<SmtpUser> {
        val term = searchTerm.trim()
        if (term.isEmpty()) return emptyList()

        val (localPart, domain) = if (term.contains('@')) {
            val lp = term.substringBeforeLast('@')
            val d = term.substringAfterLast('@')
            lp to d
        } else {
            term to localDomain
        }

        // External domains cannot be verified against local user storage.
        if (!domain.equals(localDomain, ignoreCase = true)) return emptyList()

        val sanitized = sanitizeUsername(localPart)
        val userMailbox = mailboxRoot.resolve(sanitized).normalize()
        if (!userMailbox.startsWith(mailboxRoot)) return emptyList()
        if (!Files.exists(userMailbox)) return emptyList()

        return listOf(SmtpUser(localPart = localPart, domain = localDomain, username = localPart))
    }

    private fun sanitizeUsername(username: String): String =
        username.trim().map { ch ->
            when {
                ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == '+' -> ch
                else -> '_'
            }
        }.joinToString("").take(128).let { sanitized ->
            if (sanitized.isBlank() || sanitized == "." || sanitized == "..") "_" else sanitized
        }
}
