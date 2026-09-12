package com.securityexpert.nexus.ui2.jobs.transport;

/**
 * An opaque handle to one authenticated connection (contract §5: "one
 * authenticated connection per connect"). The executor never inspects its
 * internals; only the adapter that issued it knows what it wraps.
 */
public interface TransportSession {

    String sessionId();
}
