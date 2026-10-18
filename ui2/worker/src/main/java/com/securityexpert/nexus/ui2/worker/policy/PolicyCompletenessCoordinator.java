package com.securityexpert.nexus.ui2.worker.policy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

/**
 * Runs after one policy collection (PO approval 2026-10-18, partial-first): optionally reads the Check Point change
 * evidence of the one trial domain (C-1, C-2), then writes the revision rows of every container the collector observed,
 * each stating CHANGED, UNCHANGED or UNKNOWN. The Palo Alto readers (P-1, P-2, P-3, P-4) run inside the Panorama collector,
 * where the job's API key lives.
 *
 * <p>It never changes what was collected and never fails the policy job: a database or reader problem becomes a fixed-code
 * note in the job transcript. Only cancellation and a lost lease propagate. The Check Point read happens only when the
 * published-session marker of the trial domain differs from its baseline, and the whole class is a no-op for the readers
 * unless a setting names a trial target (see {@link PolicyCompletenessSettings}).</p>
 */
public final class PolicyCompletenessCoordinator {
    private final PolicyCompletenessSettings settings;
    private final JobWindowPolicy windows;
    private final PolicyRevisionRecorder recorder;
    private final CpShowChangesReader cpReader;

    PolicyCompletenessCoordinator(PolicyCompletenessSettings settings, JobWindowPolicy windows, PolicyRevisionRecorder recorder,
            CpShowChangesReader cpReader) {
        this.settings = settings; this.windows = windows; this.recorder = recorder; this.cpReader = cpReader;
    }

    /** @param fingerprint the product HMAC key holder, or null in tests (the admin identity is then stored masked); the policy pod always passes it (PO 2026-10-18) */
    public static PolicyCompletenessCoordinator create(PolicyCompletenessSettings settings, JobWindowPolicy windows,
            CheckPointPolicyCollector collector, GateRegistryPort gates, PolicyChangeEvidenceRepository evidence,
            PolicyCollectionRepository policies, PolicyRevisionRecorder recorder, HostnameFingerprint fingerprint) {
        var reader = new CpShowChangesReader(collector, gates, evidence, policies, settings, windows, fingerprint,
            d -> Thread.sleep(d.toMillis()));
        return new PolicyCompletenessCoordinator(settings, windows, recorder, reader);
    }

    public Instant now() { return windows.now(); }

    /**
     * @param runReaders false after a failed collection: revision rows only, no device read
     */
    public void afterCollection(DiscoveryRun run, PolicyCollectionRepository.Request request, PolicyObservations observations,
            Instant jobStart, BooleanSupplier lease, boolean runReaders) {
        if (observations.isEmpty()) return;
        try {
            Map<String, CpShowChangesReader.Result> diffs = new LinkedHashMap<>();
            if (runReaders && cpReader != null && settings.cpShowChangesTrialDomain().isPresent()) {
                for (PolicyObservations.Cp o : observations.cp()) {
                    if (!settings.cpShowChangesTargets(o.container()) || o.markerJson() == null || o.reused() || o.domainName() == null) continue;
                    var baseline = recorder.baseline(request.sourceId(), o.container(), request.jobId(), o.previousMarkerJson());
                    // Only a changed marker asks the MDS for a diff (plan section 6, C-1 field 6).
                    if (baseline.isEmpty() || baseline.get().equals(o.markerJson())) continue;
                    diffs.put(o.container(), cpReader.read(run, request, o.container(), o.domainName(), baseline.get(),
                        o.markerJson(), jobStart, lease));
                }
            }
            int rows = recorder.record(request.sourceId(), request.jobId(), jobStart, observations, diffs);
            JobTranscriptScope.add("job", "note", "POLICY_REVISIONS rows=" + rows);
        } catch (RuntimeException failure) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failure);
            String reason = PolicyCollectionTrace.reason(failure);
            if (reason.endsWith(": LEASE_LOST") || reason.endsWith(": CANCELLED")) throw failure;
            JobTranscriptScope.add("job", "note", "POLICY_REVISIONS outcome=UNKNOWN reason=NOT_RECORDED");
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "POLICY_REVISIONS_NOT_RECORDED");
        }
    }
}
