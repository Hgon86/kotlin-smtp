package io.github.kotlinsmtp.server

/**
 * Minimal spooler hook used by core SMTP commands.
 *
 * In engine-only core mode, the spool implementation lives in the host module.
 */
interface SmtpSpooler {
    fun triggerOnce()
}
