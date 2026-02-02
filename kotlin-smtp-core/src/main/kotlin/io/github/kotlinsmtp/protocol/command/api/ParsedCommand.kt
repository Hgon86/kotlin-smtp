package io.github.kotlinsmtp.protocol.command.api

import io.github.kotlinsmtp.utils.Values


class ParsedCommand(val rawCommand: String) {
    private val trimmed by lazy { rawCommand.trimStart() }

    // SMTP는 SP 뿐 아니라 HTAB 등도 공백으로 취급될 수 있으므로 "공백 전체"를 고려합니다.
    val commandName: String by lazy {
        trimmed.takeWhile { !it.isWhitespace() }.uppercase()
    }

    val parts: List<String> by lazy { trimmed.split(Values.whitespaceRegex).filter { it.isNotBlank() } }

    // HELP/VRFY 등에서 나머지 raw 문자열이 필요할 때 사용합니다.
    val rawWithoutCommand: String by lazy {
        val split = trimmed.split(Values.whitespaceRegex, limit = 2)
        split.getOrNull(1).orEmpty()
    }
}
