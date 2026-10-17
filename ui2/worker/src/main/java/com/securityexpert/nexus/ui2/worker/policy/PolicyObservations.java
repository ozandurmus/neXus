package com.securityexpert.nexus.ui2.worker.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * What one policy collection observed per container, handed to the completeness coordinator after the run so it can write
 * the revision rows (V163 {@code policy_revision}). Opaque references, a marker token and counts only. Thread-safe: the
 * Check Point collector reads domains from several sessions.
 */
public final class PolicyObservations {
    /**
     * A Check Point domain. {@code markerJson} is the published-session marker ({uid, publish_time, posix}) or null when the
     * signal was unavailable; {@code previousMarkerJson} is the earlier marker the collector itself held (null when none or
     * when the job is a resumed one); {@code reused} says the previous run's snapshots were republished.
     */
    public record Cp(String container, String domainName, String markerJson, String previousMarkerJson, boolean reused,
            boolean complete, int rules) {
        @Override public String toString() { return "Cp[masked]"; }
    }

    /**
     * A Palo Alto device group. {@code contentVersion} is the SHA-256 of the lineage XML (null when the group was not
     * evaluable); {@code failureReason} is a fixed code when the group could not be collected.
     */
    public record Pan(String container, String contentVersion, boolean complete, String failureReason, int rules) {
        @Override public String toString() { return "Pan[masked]"; }
    }

    private final List<Cp> cp = new ArrayList<>();
    private final List<Pan> pan = new ArrayList<>();

    public synchronized void add(Cp observation) { cp.add(observation); }
    public synchronized void add(Pan observation) { pan.add(observation); }
    public synchronized List<Cp> cp() { return List.copyOf(cp); }
    public synchronized List<Pan> pan() { return List.copyOf(pan); }
    public synchronized boolean isEmpty() { return cp.isEmpty() && pan.isEmpty(); }
}
