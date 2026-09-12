package com.securityexpert.nexus.ui2.capability;

import java.util.List;

/**
 * The queryable {@code gate_registry} port (adjudication F2: the table is
 * created by this movement's own {@code V4} migration, implemented in
 * {@code persistence}). {@link GateResolver} calls this at two points, per
 * C4 §3.3: registry/spec-load time (offline) and immediately before device
 * execution (claim time) -- never cached across a sign-off state change, so
 * every call re-queries rather than consulting a snapshot.
 */
public interface GateRegistryPort {

    /**
     * Rows whose {@code canonical_command_key} (and the rest of the
     * composite key) exactly equals {@code key}. Zero, one, or more than
     * one row is a legal return value -- {@link GateResolver} is what
     * interprets the count (C4 §3.3 steps 4-6); this port never filters or
     * ranks on the implementation's own initiative.
     */
    List<GateRow> findByCanonicalKey(CanonicalCommandKey key);
}
