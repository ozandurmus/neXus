package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverAuthorizationRequest;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverLeaseToken;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FourEyesAuthorizationResult;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FourEyesValidationRule;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.PreflightReport;
import com.securityexpert.nexus.ui2.jobs.failover.model.PreflightVerdict;
import com.securityexpert.nexus.ui2.jobs.failover.plan.FailoverDryRunPlanner;
import com.securityexpert.nexus.ui2.jobs.failover.plan.FailoverExecutionPlan;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service coordinating 4-eyes authorization and dry-run plan compilation for cluster failovers.
 * Guarantees that:
 * 1. An authorization lease is ONLY issued if the pre-flight readiness verdict has ZERO blocking conditions.
 * 2. Requester and approver are distinct authenticated identities.
 * 3. Lease tokens are single-use and expire within 15 minutes.
 * 4. Dry-run plan disclosure does NOT consume the execution lease token (Astra & Fable review fix).
 *    Lease consumption occurs strictly when crossing the durable mutation boundary in execution.
 * 5. Error messages do NOT leak raw cluster references (F-6).
 */
@Service
public class FailoverAuthorizationService {

    private final PreflightService preflightService;
    private final FourEyesValidationRule fourEyesRule;
    private final FailoverDryRunPlanner dryRunPlanner;

    private final Map<String, FailoverLeaseToken> activeLeaseTokens = new ConcurrentHashMap<>();
    private final Set<String> consumedTokenIds = ConcurrentHashMap.newKeySet();

    public FailoverAuthorizationService(PreflightService preflightService) {
        this.preflightService = Objects.requireNonNull(preflightService, "preflightService must not be null");
        byte[] secretKey = new byte[32];
        new SecureRandom().nextBytes(secretKey);
        this.fourEyesRule = new FourEyesValidationRule(secretKey);
        this.dryRunPlanner = new FailoverDryRunPlanner();
    }

    // Constructor for testing with deterministic key/rule
    public FailoverAuthorizationService(
        PreflightService preflightService,
        FourEyesValidationRule fourEyesRule,
        FailoverDryRunPlanner dryRunPlanner
    ) {
        this.preflightService = Objects.requireNonNull(preflightService, "preflightService must not be null");
        this.fourEyesRule = Objects.requireNonNull(fourEyesRule, "fourEyesRule must not be null");
        this.dryRunPlanner = Objects.requireNonNull(dryRunPlanner, "dryRunPlanner must not be null");
    }

    public FourEyesAuthorizationResult authorizeFailover(FailoverAuthorizationRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // 1. Verify that cluster readiness assessment has ZERO blocking conditions
        PreflightReport report = preflightService.getLatestReport(request.clusterRef());
        if (report.overallVerdict() != PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED) {
            return new FourEyesAuthorizationResult.Refused(
                "PREFLIGHT_BLOCKING_CONDITIONS_PRESENT",
                "Cannot authorize failover when pre-flight checks report blocking conditions (" +
                    report.blockingFailureCount() + " blocking failure(s) observed)"
            );
        }

        // 2. Evaluate 4-eyes rule (requester != approver, reason length, maintenance window, nonce)
        FourEyesAuthorizationResult result = fourEyesRule.authorize(request);
        if (result instanceof FourEyesAuthorizationResult.Authorized authorized) {
            FailoverLeaseToken token = authorized.leaseToken();
            activeLeaseTokens.put(token.tokenId(), token);
        }
        return result;
    }

    public FailoverExecutionPlan executeDryRun(String clusterRef, String tokenId, String clientNonce) {
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(tokenId, "tokenId must not be null");
        Objects.requireNonNull(clientNonce, "clientNonce must not be null");

        if (consumedTokenIds.contains(tokenId)) {
            throw new IllegalStateException("Failover lease token has already been consumed");
        }

        FailoverLeaseToken token = activeLeaseTokens.get(tokenId);
        if (token == null) {
            throw new IllegalArgumentException("No active failover lease token found with ID: " + tokenId);
        }

        // F-6: Do not leak raw cluster reference in error message
        if (!token.clusterRef().equals(clusterRef)) {
            throw new IllegalArgumentException("Lease token cluster mismatch: token not bound to requested cluster");
        }

        if (!fourEyesRule.verifyTokenSignature(token, clientNonce)) {
            throw new SecurityException("Failover lease token signature is invalid, expired, or nonce mismatched");
        }

        // Note: Dry-run discloses the plan WITHOUT consuming the single-use execution lease.
        ClusterEvidenceSnapshot snapshot = preflightService.buildSnapshotForCluster(clusterRef);
        return dryRunPlanner.compileDryRunPlan(snapshot, token);
    }

    public synchronized FailoverLeaseToken consumeLeaseForExecution(String clusterRef, String tokenId, String clientNonce) {
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(tokenId, "tokenId must not be null");
        Objects.requireNonNull(clientNonce, "clientNonce must not be null");

        if (consumedTokenIds.contains(tokenId)) {
            throw new IllegalStateException("Failover lease token has already been consumed (single-use lease invariant)");
        }

        FailoverLeaseToken token = activeLeaseTokens.get(tokenId);
        if (token == null) {
            throw new IllegalArgumentException("No active failover lease token found with ID: " + tokenId);
        }

        if (!token.clusterRef().equals(clusterRef)) {
            throw new IllegalArgumentException("Lease token cluster mismatch: token not bound to requested cluster");
        }

        if (!fourEyesRule.verifyTokenSignature(token, clientNonce)) {
            throw new SecurityException("Failover lease token signature is invalid, expired, or nonce mismatched");
        }

        // Atomically consume the token upon crossing mutation boundary
        consumedTokenIds.add(tokenId);
        activeLeaseTokens.remove(tokenId);
        return token;
    }
}
