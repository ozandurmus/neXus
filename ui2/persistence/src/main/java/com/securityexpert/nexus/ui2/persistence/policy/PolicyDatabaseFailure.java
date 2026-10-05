package com.securityexpert.nexus.ui2.persistence.policy;

/** Safe unit failure; deliberately excludes JDBC messages, SQL and parameter values. */
public final class PolicyDatabaseFailure extends RuntimeException {
    public PolicyDatabaseFailure(String method, String kind, long payloadBytes) {
        super("POLICY_DB_WRITE_FAILED");
        System.getLogger(PolicyDatabaseFailure.class.getName()).log(System.Logger.Level.WARNING,
            "POLICY_DB_WRITE_FAILED repository=" + method + " sqlKind=" + kind + " payloadBytes=" + payloadBytes);
    }
}
