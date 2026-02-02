package io.github.kotlinsmtp.model

class SmtpUser(
    private val localPart: String,
    private val domain: String,
    private val username: String? = null,
) {
    val stringRepresentation by lazy {
        if (username != null)
            "$username <$localPart@$domain>"
        else
            "$localPart@$domain"
    }
}
