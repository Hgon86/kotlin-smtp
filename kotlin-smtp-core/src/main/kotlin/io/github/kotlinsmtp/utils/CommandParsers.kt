package io.github.kotlinsmtp.utils

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.utils.SmtpStatusCode.RECIPIENT_NOT_RECOGNIZED

/**
 * Parsed result for MAIL FROM command
 * @property address Sender email address (empty string means empty reverse-path)
 * @property parameters ESMTP parameter map (keys normalized to uppercase)
 */
internal data class MailArguments(
    val address: String,
    val parameters: Map<String, String>,
)

/**
 * Parsed result for RCPT TO command
 * @property address Recipient email address
 * @property parameters ESMTP parameter map (keys normalized to uppercase)
 */
internal data class RcptArguments(
    val address: String,
    val parameters: Map<String, String>,
)

/**
 * Parse MAIL FROM command string.
 * Example: "MAIL FROM:<user@example.com> SIZE=1234 BODY=7BIT"
 *
 * @param raw Full raw command string
 * @return Parsed MailArguments
 * @throws IllegalArgumentException When parsing fails
 */
internal fun parseMailArguments(raw: String): MailArguments {
    val trimmed = raw.trim()
    
    // Validate "MAIL FROM:" keyword
    if (!trimmed.uppercase().startsWith("MAIL FROM:")) {
        throw IllegalArgumentException("Missing MAIL FROM keyword")
    }
    
    // Extract address inside brackets
    val address = AddressUtils.extractFromBrackets(trimmed)
        ?: throw IllegalArgumentException("Missing angle brackets around sender")
    
    // Extract parameters after brackets
    val params = extractParameters(trimmed)
    
    return MailArguments(address, params)
}

/**
 * Parse RCPT TO command string.
 * Example: "RCPT TO:<recipient@example.com> NOTIFY=NEVER"
 *
 * @param raw Full raw command string
 * @return Parsed RcptArguments
 * @throws IllegalArgumentException When parsing fails
 */
internal fun parseRcptArguments(raw: String): RcptArguments {
    val trimmed = raw.trim()
    
    // Validate "RCPT TO:" keyword
    if (!trimmed.uppercase().startsWith("RCPT TO:")) {
        throw IllegalArgumentException("Missing RCPT TO keyword")
    }
    
    // Extract address inside brackets
    val address = AddressUtils.extractFromBrackets(trimmed)
        ?: throw IllegalArgumentException("Missing angle brackets around recipient")
    
    // Extract parameters after brackets
    val params = extractParameters(trimmed)
    
    return RcptArguments(address, params)
}

/**
 * Extract ESMTP parameters after closing bracket (>) from command string.
 * Parameter format: KEY=VALUE or KEY (without value)
 *
 * Limits for DoS protection:
 * - Max parameter count: 10
 * - Max parameter length: 1024 bytes
 *
 * @param segment Full command string
 * @return Parameter map (keys uppercase, empty string when no value)
 * @throws IllegalArgumentException When limits are exceeded
 */
private fun extractParameters(segment: String): Map<String, String> {
    val openingIndex = segment.indexOf('<')
    if (openingIndex < 0) return emptyMap()

    val closingIndex = segment.indexOf('>', startIndex = openingIndex + 1)
    if (closingIndex < 0 || closingIndex + 1 >= segment.length) return emptyMap()
    
    val remainder = segment.substring(closingIndex + 1).trim()
    if (remainder.isEmpty()) return emptyMap()
    
    val tokens = remainder.split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    
    // Parameter count limit (DoS prevention)
    if (tokens.size > 10) {
        throw IllegalArgumentException("Too many parameters (max 10)")
    }
    
    val params = LinkedHashMap<String, String>(tokens.size)

    for (token in tokens) {
        // Parameter length limit (DoS prevention)
        if (token.length > 1024) {
            throw IllegalArgumentException("Parameter too long (max 1024 bytes)")
        }

        val equalsIndex = token.indexOf('=')

        val keyRaw = if (equalsIndex >= 0) token.substring(0, equalsIndex) else token
        if (keyRaw.isBlank()) {
            throw IllegalArgumentException("Invalid parameter format")
        }

        val key = keyRaw.uppercase()
        if (!esmtpParameterNameRegex.matches(key)) {
            throw IllegalArgumentException("Invalid parameter name: $key")
        }
        val value = if (equalsIndex >= 0) token.substring(equalsIndex + 1) else ""

        if (params.containsKey(key)) {
            throw IllegalArgumentException("Duplicate parameter: $key")
        }

        params[key] = value
    }

    return params
}

private val esmtpParameterNameRegex = Regex("^[A-Z0-9][A-Z0-9-]*$")

/**
 * Validate whether parameter map contains unsupported parameters.
 *
 * @param disallowed Set of disallowed parameter names (uppercase)
 * @throws SmtpSendResponse Throws 555 error when disallowed parameter is found
 */
internal fun Map<String, String>.ensureNoUnsupportedParams(disallowed: Set<String>) {
    for (key in keys) {
        if (key.uppercase() in disallowed) {
            throw SmtpSendResponse(
                RECIPIENT_NOT_RECOGNIZED.code,
                "$key parameter not supported"
            )
        }
    }
}
