package com.securityexpert.nexus.ui2.platform;

/**
 * The first-{@code security_admin}-binding bootstrap port (C3 §4.3,
 * adjudication F8). {@code cli}'s allowed dependencies do not change
 * (platform-core, job-engine only): this interface is declared here so
 * {@code cli} can depend on it without reaching {@code persistence}
 * directly; {@code persistence} supplies the only implementation, reached
 * by {@code cli} through a composition helper in {@code job-engine} (the
 * one module allowed to depend on both {@code persistence} and be depended
 * on by {@code cli}).
 *
 * <p>Reachable only from a deployment-controlled CLI invocation, never a
 * running service, never the browser (C1 §2.4's {@code ui2_migrate}
 * posture). Every subsequent binding, including a second
 * {@code security_admin} binding, goes through the normal HTTP path and its
 * four-eyes/self-grant check — this port is for the first binding only.</p>
 */
public interface SecurityAdminBootstrapPort {

    /** The reserved, non-directory-derived actor recorded as this binding's creator. */
    String BOOTSTRAP_ACTOR = "system:bootstrap";

    /**
     * @param groupReferenceEncrypted the directory's own opaque group
     *                                  identifier, already encrypted by the
     *                                  caller (this port never sees plaintext)
     * @param groupReferenceKeyId      which key wrapped it
     * @return the created binding's opaque {@code binding_id}
     */
    String bootstrapFirstSecurityAdminBinding(byte[] groupReferenceEncrypted, String groupReferenceKeyId);
}
