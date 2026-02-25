package io.github.kotlinsmtp.relay.api

/**
 * Optional relay access decision rule for policy chaining.
 *
 * Return null when the rule does not apply and the next rule should be evaluated.
 */
public fun interface RelayAccessPolicyRule {
    /**
     * Evaluates relay access for this rule.
     *
     * @param context Relay access context
     * @return Decision when matched, null otherwise
     */
    public fun evaluateOrNull(context: RelayAccessContext): RelayAccessDecision?
}
