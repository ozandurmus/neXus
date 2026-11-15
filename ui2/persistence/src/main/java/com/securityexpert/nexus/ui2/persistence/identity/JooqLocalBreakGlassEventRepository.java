package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqLocalBreakGlassEventRepository implements LocalBreakGlassEventRepository {
    private final AuditedTransactionBoundary audited;

    public JooqLocalBreakGlassEventRepository(TransactionBoundary transactions) {
        this.audited = new AuditedTransactionBoundary(Objects.requireNonNull(transactions, "transactions"));
    }

    @Override
    public void recordLoginSucceeded(String actorFingerprint, String kind, boolean alert) {
        audited.inTransaction(actorFingerprint, LOGIN_SUCCEEDED_ACTION, dsl -> dsl.execute(
                "insert into local_break_glass_event(actor_fingerprint, kind, outcome, alert) values ({0}, {1}, 'LOGIN_SUCCEEDED', {2})",
                actorFingerprint, kind, alert));
    }

    @Override
    public void recordRefused(String actorFingerprint) {
        audited.inTransaction(actorFingerprint, REFUSED_ACTION, dsl -> dsl.execute(
                "insert into local_break_glass_event(actor_fingerprint, kind, outcome, alert) values ({0}, 'OTHER', 'REFUSED_NON_BREAK_GLASS', false)",
                actorFingerprint));
    }
}
