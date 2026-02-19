package io.github.kotlinsmtp.relay.api

/**
 * Utility used to configure relay default policies.
 */
public object RelayDefaults {
    public fun requireAuthPolicy(): RelayAccessPolicy = RelayAccessPolicy { ctx: RelayAccessContext ->
        if (!ctx.authenticated) RelayAccessDecision.Denied(RelayDeniedReason.AUTH_REQUIRED)
        else RelayAccessDecision.Allowed
    }
}
