package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.utils.SmtpStatusCode
import io.github.kotlinsmtp.utils.Values
import java.net.InetSocketAddress

/**
 * Masks sensitive information in SMTP input logs.
 */
internal class SmtpSessionLogSanitizer {
    /**
     * Converts to a safe string suitable for logging.
     *
     * @param line Raw input line
     * @param inDataMode Whether DATA body is being received
     * @param dataModeFramingHint Whether DATA mode transition hint is active
     * @return Masked log string
     */
    fun sanitize(line: String, inDataMode: Boolean, dataModeFramingHint: Boolean): String {
        if (inDataMode || dataModeFramingHint) {
            return if (line == ".") "<DATA:END>" else "<DATA:${line.length} chars>"
        }

        val trimmed = line.trimStart()
        if (!trimmed.regionMatches(0, "AUTH", 0, 4, ignoreCase = true)) return line

        val parts = trimmed.split(Values.whitespaceRegex, limit = 3)
        return when {
            parts.size >= 2 && parts[1].equals("PLAIN", ignoreCase = true) -> "AUTH PLAIN ***"
            parts.size >= 2 -> "AUTH ${parts[1]} ***"
            else -> "AUTH ***"
        }
    }
}

/**
 * Builds SMTP response format in standard form.
 */
internal class SmtpResponseFormatter {
    /**
     * Build a single response line.
     *
     * @param code SMTP status code
     * @param message Response message
     * @return RFC-formatted response line
     */
    fun formatLine(code: Int, message: String?): String {
        if (message != null && enhancedStatusRegex.containsMatchIn(message.trimStart())) {
            return "$code $message"
        }

        val statusCode = SmtpStatusCode.fromCode(code)
        return when {
            statusCode != null -> statusCode.formatResponse(message)
            message != null -> "$code $message"
            else -> code.toString()
        }
    }

    /**
     * Build multiline response lines.
     *
     * @param code SMTP status code
     * @param lines Response line list
     * @return RFC-formatted multiline response
     */
    fun formatMultiline(code: Int, lines: List<String>): List<String> {
        val statusCode = SmtpStatusCode.fromCode(code)
        val enhancedPrefix = statusCode?.enhancedCode?.let { "$it " } ?: ""

        return lines.mapIndexed { index, line ->
            if (index != lines.lastIndex) "$code-$enhancedPrefix$line"
            else "$code $enhancedPrefix$line"
        }
    }
}

/**
 * Converts remote peer address to string for logging/metadata.
 */
internal object SmtpPeerAddressResolver {
    /**
     * Convert address to standard string format.
     *
     * @param address Remote address object
     * @return `host:port` or `[ipv6]:port` formatted string
     */
    fun resolve(address: Any?): String? {
        val inet = (address as? InetSocketAddress) ?: return address?.toString()
        val ipOrHost = inet.address?.hostAddress ?: inet.hostString
        return if (ipOrHost.contains(':')) "[$ipOrHost]:${inet.port}" else "$ipOrHost:${inet.port}"
    }
}

// Detect Enhanced Status Code form like "5.7.1 ...".
private val enhancedStatusRegex = Regex("^\\d\\.\\d\\.\\d\\b")
