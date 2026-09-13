package com.securityexpert.nexus.ui2.identity.ldap;

import java.util.Objects;

import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.LdapOperatorBindPort;
import com.securityexpert.nexus.ui2.platform.Mechanism;
import com.securityexpert.nexus.ui2.platform.Result;

/**
 * Wraps {@code C3}'s existing {@link LdapOperatorBindPort} behind the C3A
 * §2.1 {@link Mechanism} interface -- proving the registry shape it
 * describes ("adding ldap is wrapping the existing mechanism, never a line
 * of {@code C3} §2 changed") without altering one line of the bind logic
 * itself. Not wired into any running composition by this movement: no
 * directory host/CA/bind-DN configuration exists yet for this build, and
 * building that configuration is {@code C3}'s scope, not this contract's
 * (§1.2).
 */
public final class LdapMechanism implements Mechanism {

    public static final String MECHANISM_ID = "ldap";

    private final LdapOperatorBindPort bindPort;

    public LdapMechanism(LdapOperatorBindPort bindPort) {
        this.bindPort = Objects.requireNonNull(bindPort, "bindPort");
    }

    @Override
    public String mechanismId() {
        return MECHANISM_ID;
    }

    @Override
    public AttemptOutcome attempt(String identity, char[] credential) {
        Result<LdapOperatorBindPort.OperatorBindOutcome> result = bindPort.bind(identity, credential);
        if (result instanceof Result.Err<LdapOperatorBindPort.OperatorBindOutcome> err) {
            if (LdapOperatorBindPort.FailureCodes.DIRECTORY_UNAVAILABLE.equals(err.code())) {
                return AttemptOutcome.mechanismUnavailable(err.code());
            }
            return AttemptOutcome.refused(err.code());
        }
        LdapOperatorBindPort.OperatorBindOutcome outcome =
                ((Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) result).value();
        return AttemptOutcome.success(outcome.actorFingerprint());
    }
}
