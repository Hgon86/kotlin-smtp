package io.github.kotlinsmtp.model

public class SmtpUser(
    private val localPart: String,
    private val domain: String,
    private val username: String? = null,
) {
    public val stringRepresentation: String by lazy {
        if (username != null)
            "$username <$localPart@$domain>"
        else
            "$localPart@$domain"
    }
}
