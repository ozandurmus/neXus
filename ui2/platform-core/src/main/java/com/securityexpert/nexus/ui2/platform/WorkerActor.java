package com.securityexpert.nexus.ui2.platform;

/**
 * <b>The reserved worker actor (adjudication F3, part two).</b> C1 §3.5's
 * audit trigger fails closed ({@code audit_context_missing}) unless
 * {@code app.actor_fingerprint}/{@code app.action_id} are set before a
 * mutation. Every write the collection engine's executor makes -- a job
 * claim, a heartbeat, a step-attempt insert, a terminal-state write, a
 * device-state transition -- is caused by a system process, never a human
 * session, so it carries this reserved, non-directory-derived fingerprint
 * instead of a session's {@code actor_fingerprint}.
 *
 * <p>Modelled directly on {@link SecurityAdminBootstrapPort#BOOTSTRAP_ACTOR}
 * ("system:bootstrap") and C3 §3.3's {@code system:session_reconciler} /
 * {@code system:revalidation_adapter} precedent -- the same reserved-actor
 * pattern, one more reserved name, not a second mechanism. Declared in
 * {@code platform-core} (no project dependencies, {@code DIR-1}) so both
 * {@code job-engine} (the executor) and {@code persistence} (the audited
 * transaction call sites) can share the one literal without either
 * depending on the other for it.</p>
 */
public final class WorkerActor {

    /** The reserved actor fingerprint recorded for every executor-caused mutation. */
    public static final String RESERVED_ACTOR_FINGERPRINT = "system:worker";

    private WorkerActor() {
    }
}
