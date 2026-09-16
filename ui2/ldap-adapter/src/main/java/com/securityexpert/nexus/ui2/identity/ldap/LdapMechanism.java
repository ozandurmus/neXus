package com.securityexpert.nexus.ui2.identity.ldap;

import java.util.Objects;

import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.LdapOperatorBindPort;
import com.securityexpert.nexus.ui2.platform.Mechanism;
import com.securityexpert.nexus.ui2.platform.Result;

/** Existing registry mechanism; configured corporate admission remains disabled pending approval. */
public final class LdapMechanism implements Mechanism {

    public static final String MECHANISM_ID = "ldap";

    private final LdapOperatorBindPort bindPort;
    private final boolean admissionEnabled;

    public LdapMechanism(LdapOperatorBindPort bindPort) {
        this(bindPort, true);
    }

    public LdapMechanism(LdapOperatorBindPort bindPort, boolean admissionEnabled) {
        this.bindPort = Objects.requireNonNull(bindPort, "bindPort");
        this.admissionEnabled = admissionEnabled;
    }

    @Override
    public String mechanismId() {
        return MECHANISM_ID;
    }

    @Override
    public AttemptOutcome attempt(String identity, char[] credential) {
        if (!admissionEnabled) {
            if (credential != null) java.util.Arrays.fill(credential, '\0');
            return AttemptOutcome.mechanismUnavailable("directory_posture_disabled");
        }
        Result<LdapOperatorBindPort.OperatorBindOutcome> result = bindPort.bind(identity, credential);
        if (result instanceof Result.Err<LdapOperatorBindPort.OperatorBindOutcome> err) {
            if (LdapOperatorBindPort.FailureCodes.DIRECTORY_UNAVAILABLE.equals(err.code())) {
                return AttemptOutcome.mechanismUnavailable(err.code());
            }
            return AttemptOutcome.refused(err.code());
        }
        LdapOperatorBindPort.OperatorBindOutcome outcome =
                ((Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) result).value();
        if (outcome.observation() == null) return AttemptOutcome.mechanismUnavailable("directory_identity_not_proven");
        return new AttemptOutcome.Success(outcome.actorFingerprint(), outcome.observation());
    }
}
