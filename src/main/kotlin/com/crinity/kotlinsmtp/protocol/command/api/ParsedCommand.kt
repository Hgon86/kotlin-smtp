package com.crinity.kotlinsmtp.protocol.command.api

import com.crinity.kotlinsmtp.utils.Values


class ParsedCommand(val rawCommand: String) {
    val commandName = rawCommand.takeWhile { it != ' ' }.uppercase()
    val parts by lazy { rawCommand.split(Values.whitespaceRegex) }
    val rawWithoutCommand by lazy { rawCommand.removePrefix(rawCommand.takeWhile { it != ' ' } + ' ') }
}
