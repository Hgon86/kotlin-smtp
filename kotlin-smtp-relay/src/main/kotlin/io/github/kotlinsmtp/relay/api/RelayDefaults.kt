package io.github.kotlinsmtp.relay.api

/**
 * relay 기본 정책을 구성할 때 사용하는 유틸리티.
 */
public object RelayDefaults {
    public fun requireAuthPolicy(): RelayAccessPolicy = RelayAccessPolicy { ctx: RelayAccessContext ->
        if (!ctx.authenticated) RelayAccessDecision.Denied(RelayDeniedReason.AUTH_REQUIRED)
        else RelayAccessDecision.Allowed
    }
}
