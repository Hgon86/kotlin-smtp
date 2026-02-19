package io.github.kotlinsmtp.protocol.command.api

import io.github.kotlinsmtp.utils.Values


internal class ParsedCommand(val rawCommand: String) {
    private val trimmed by lazy { rawCommand.trimStart() }

    // SMTP may treat HTAB, etc. as whitespace in addition to SP, so consider "all whitespace".
    val commandName: String by lazy {
        trimmed.takeWhile { !it.isWhitespace() }.uppercase()
    }

    val parts: List<String> by lazy { trimmed.split(Values.whitespaceRegex).filter { it.isNotBlank() } }

    // Used when the remaining raw string is needed in HELP/VRFY, etc.
    val rawWithoutCommand: String by lazy {
        val split = trimmed.split(Values.whitespaceRegex, limit = 2)
        split.getOrNull(1).orEmpty()
    }
}
