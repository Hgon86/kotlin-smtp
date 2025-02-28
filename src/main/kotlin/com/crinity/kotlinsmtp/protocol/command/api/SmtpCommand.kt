package com.crinity.kotlinsmtp.protocol.command.api

import com.crinity.kotlinsmtp.exception.SmtpSendResponse
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.COMMAND_SYNTAX_ERROR


abstract class SmtpCommand(
    private val name: String,
    val description: String,
    private val expectedSyntax: String? = null
) {
    abstract suspend fun execute(command: ParsedCommand, session: SmtpSession)

    protected fun respondSyntax(message: String = "Syntax error in parameters or arguments"): Nothing {
        val syntaxResponse = expectedSyntax?.let { "$name $it" } ?: name
        throw SmtpSendResponse(COMMAND_SYNTAX_ERROR.code, "$message - Expected syntax: $syntaxResponse")
    }
}
