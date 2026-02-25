package io.github.kotlinsmtp.relay.api

/**
 * Optional route decision rule for relay route chaining.
 *
 * Return null when the rule does not apply and the next rule should be evaluated.
 */
public fun interface RelayRouteRule {
    /**
     * Resolves route if this rule applies.
     *
     * @param request Relay input context
     * @return Route when matched, null otherwise
     */
    public fun resolveOrNull(request: RelayRequest): RelayRoute?
}
