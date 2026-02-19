package io.github.kotlinsmtp.relay.api

/**
 * SPI that determines outbound relay route from recipient/request information.
 *
 * Default implementation is based on configuration file (application.yml),
 * and can be replaced with DB/config-server lookup implementation.
 */
public fun interface RelayRouteResolver {
    /**
     * Return transfer route for the given relay request.
     *
     * @param request Relay input context
     * @return Selected relay route
     */
    public fun resolve(request: RelayRequest): RelayRoute
}

/**
 * Outbound relay route model.
 */
public sealed interface RelayRoute {
    /** Direct transfer route based on MX lookup. */
    public data object DirectMx : RelayRoute

    /**
     * Transfer route via specified SMTP server (Smart Host).
     *
     * @property host Target SMTP host
     * @property port Target SMTP port
     * @property username SMTP AUTH username (optional)
     * @property password SMTP AUTH password (optional)
     * @property startTlsEnabled Whether to attempt STARTTLS (optional)
     * @property startTlsRequired Whether STARTTLS is required (optional)
     * @property checkServerIdentity Whether to verify server certificate hostname (optional)
     * @property trustAll Whether trust-all is enabled for dev/test (optional)
     * @property trustHosts List of trusted hosts to allow (optional)
     */
    public data class SmartHost(
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
        val startTlsEnabled: Boolean? = null,
        val startTlsRequired: Boolean? = null,
        val checkServerIdentity: Boolean? = null,
        val trustAll: Boolean? = null,
        val trustHosts: List<String>? = null,
    ) : RelayRoute
}
