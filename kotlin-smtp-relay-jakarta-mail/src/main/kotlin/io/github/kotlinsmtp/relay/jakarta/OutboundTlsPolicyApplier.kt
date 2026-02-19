package io.github.kotlinsmtp.relay.jakarta

import io.github.kotlinsmtp.relay.api.OutboundRelayPolicy
import io.github.kotlinsmtp.relay.api.RelayPermanentException
import io.github.kotlinsmtp.relay.api.RelayRoute

/**
 * Applies policy hints (MTA-STS/DANE/custom) to outbound TLS settings.
 */
internal object OutboundTlsPolicyApplier {
    private data class TlsParams(
        val startTlsEnabled: Boolean,
        val startTlsRequired: Boolean,
        val checkServerIdentity: Boolean,
        val trustAll: Boolean,
        val trustHosts: List<String>,
    )

    internal data class ResolvedTls(
        val startTlsEnabled: Boolean,
        val startTlsRequired: Boolean,
        val checkServerIdentity: Boolean,
        val trustAll: Boolean,
        val trustHosts: List<String>,
    )

    fun forMx(base: OutboundTlsConfig, policy: OutboundRelayPolicy?): ResolvedTls =
        resolve(base.asParams(), policy)

    fun forSmartHost(
        base: OutboundTlsConfig,
        route: RelayRoute.SmartHost,
        policy: OutboundRelayPolicy?,
    ): ResolvedTls {
        return resolve(base.merge(route), policy)
    }

    private fun resolve(params: TlsParams, policy: OutboundRelayPolicy?): ResolvedTls {
        val requireTls = policy?.requireTls == true
        val requireValidCert = policy?.requireValidCertificate == true
        val resolved = ResolvedTls(
            startTlsEnabled = params.startTlsEnabled || requireTls,
            startTlsRequired = params.startTlsRequired || requireTls,
            checkServerIdentity = params.checkServerIdentity || requireValidCert,
            trustAll = params.trustAll,
            trustHosts = params.trustHosts,
        )
        validatePolicyCompatibility(resolved, policy)
        return resolved
    }

    private fun OutboundTlsConfig.asParams(): TlsParams = TlsParams(
        startTlsEnabled = startTlsEnabled,
        startTlsRequired = startTlsRequired,
        checkServerIdentity = checkServerIdentity,
        trustAll = trustAll,
        trustHosts = trustHosts,
    )

    private fun OutboundTlsConfig.merge(route: RelayRoute.SmartHost): TlsParams = TlsParams(
        startTlsEnabled = route.startTlsEnabled ?: startTlsEnabled,
        startTlsRequired = route.startTlsRequired ?: startTlsRequired,
        checkServerIdentity = route.checkServerIdentity ?: checkServerIdentity,
        trustAll = route.trustAll ?: trustAll,
        trustHosts = route.trustHosts ?: trustHosts,
    )

    private fun validatePolicyCompatibility(resolved: ResolvedTls, policy: OutboundRelayPolicy?) {
        if (policy == null) return

        if (policy.requireTls && !resolved.startTlsEnabled) {
            throw RelayPermanentException("550 5.7.1 Outbound policy requires TLS but STARTTLS is disabled")
        }
        if (policy.requireValidCertificate && resolved.trustAll) {
            throw RelayPermanentException("550 5.7.1 Outbound policy requires certificate validation but trustAll is enabled")
        }
    }
}
