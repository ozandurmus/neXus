package com.securityexpert.nexus.ui2.persistence;

import java.util.function.Function;

import org.jooq.DSLContext;

/**
 * Transaction boundary port over jOOQ (contract §2 persistence row).
 * Application-facing modules never obtain a {@link DSLContext} directly;
 * they call {@link #inTransaction(Function)} against this port.
 */
public interface TransactionBoundary {

    <T> T inTransaction(Function<DSLContext, T> work);
}
