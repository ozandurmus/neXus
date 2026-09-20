package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverLeaseToken;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FourEyesValidationRule;
import com.securityexpert.nexus.ui2.jobs.failover.execution.*;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.PreflightVerdict;
import com.securityexpert.nexus.ui2.jobs.failover.pilot.FailoverPilotAllowlist;
import com.securityexpert.nexus.ui2.jobs.failover.plan.FailoverDryRunPlanner;
import com.securityexpert.nexus.ui2.service.failover.FailoverAuthorizationService;
import com.securityexpert.nexus.ui2.service.failover.FailoverExecutionService;
import com.securityexpert.nexus.ui2.service.failover.PreflightService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FailoverExecutionControllerTest {

    private static final byte[] SECRET_KEY = "test-secret-key-at-least-16-bytes!".getBytes(StandardCharsets.UTF_8);

    private StubPreflightService preflightService;
    private FailoverAuthorizationService authzService;
    private FailoverPilotAllowlist pilotAllowlist;
    private FailoverExecutionService executionService;
    private FailoverExecutionController controller;

    @BeforeEach
    void setUp() {
        preflightService = new StubPreflightService();
        FourEyesValidationRule rule = new FourEyesValidationRule(SECRET_KEY);
        FailoverDryRunPlanner planner = new FailoverDryRunPlanner();
        authzService = new FailoverAuthorizationService(preflightService, rule, planner);
        pilotAllowlist = new FailoverPilotAllowlist();
        executionService = new FailoverExecutionService(authzService, preflightService, pilotAllowlist);
        controller = new FailoverExecutionController(executionService);
    }

    private String issueValidToken(String clusterRef, String nonce) {
        preflightService.setVerdict(PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED);
        var authPayload = new FailoverAuthorizationController.AuthorizePayload(
            "operator-bob",
            "Emergency maintenance drill ticket SEC-101",
            "CHG-101",
            "sha256:digest-abc",
            nonce
        );
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "operator-alice");
        FailoverAuthorizationController authzController = new FailoverAuthorizationController(authzService);
        ResponseEntity<Map<String, Object>> resp = authzController.authorize(clusterRef, authPayload, req);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        return (String) resp.getBody().get("token_id");
    }

    @Test
    @DisplayName("executeFailover() rejects request with 403 when actor fingerprint is missing")
    void executeRejectsMissingActor() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        var payload = new FailoverExecutionController.ExecutePayload("tok-1", "nonce-1", "CONTROLLED_FAILOVER");

        ResponseEntity<Map<String, Object>> response = controller.executeFailover("cls-uuid-cp", payload, req);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("ACTOR_FINGERPRINT_MISSING", response.getBody().get("code"));
    }

    @Test
    @DisplayName("executeFailover() rejects unapproved production cluster with 403 per pilot fence")
    void executeRejectsNonPilotCluster() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "operator-alice");

        String tokenId = issueValidToken("cls-uuid-cp", "nonce-001");
        var payload = new FailoverExecutionController.ExecutePayload(tokenId, "nonce-001", "CONTROLLED_FAILOVER");

        // Execute against production / unenrolled cluster
        ResponseEntity<Map<String, Object>> response = controller.executeFailover("prod-perimeter-cls", payload, req);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("ACCESS_DENIED", response.getBody().get("code"));
    }

    @Test
    @DisplayName("executeFailover() executes controlled failover successfully and returns 200 with SUCCEEDED state")
    void executeSucceedsOnPilotCluster() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "operator-alice");

        // Custom executor verifying role inversion
        executionService.registerExecutor(new CheckPointClusterXLExecutor(
            (memberId, cmd) -> FailoverCommandResult.success("Executed " + cmd),
            (clusterId, memberId) -> {
                // Post verification: dev-cp-1 is now STANDBY, dev-cp-2 is now ACTIVE
                String role = "dev-cp-1".equals(memberId) ? "STANDBY" : "ACTIVE";
                return MemberObservation.of(memberId, role, true, true, "OK");
            }
        ));

        String tokenId = issueValidToken("cls-uuid-cp", "nonce-001");
        var payload = new FailoverExecutionController.ExecutePayload(tokenId, "nonce-001", "CONTROLLED_FAILOVER");

        ResponseEntity<Map<String, Object>> response = controller.executeFailover("cls-uuid-cp", payload, req);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SUCCEEDED", response.getBody().get("state"));
        assertNotNull(response.getBody().get("execution_id"));
        assertNotNull(response.getBody().get("boundary_crossed_at"));
        assertEquals(false, response.getBody().get("quarantine_active"));
    }

    @Test
    @DisplayName("Single-use token enforcement: second execution attempt with same token fails with 422 ABORTED_PRE_MUTATION")
    void singleUseEnforcedOnExecution() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "operator-alice");

        executionService.registerExecutor(new CheckPointClusterXLExecutor(
            (memberId, cmd) -> FailoverCommandResult.success("Executed"),
            (clusterId, memberId) -> MemberObservation.of(memberId, "dev-cp-1".equals(memberId) ? "STANDBY" : "ACTIVE", true, true, "OK")
        ));

        String tokenId = issueValidToken("cls-uuid-cp", "nonce-001");
        var payload = new FailoverExecutionController.ExecutePayload(tokenId, "nonce-001", "CONTROLLED_FAILOVER");

        // First attempt succeeds
        ResponseEntity<Map<String, Object>> r1 = controller.executeFailover("cls-uuid-cp", payload, req);
        assertEquals(HttpStatus.OK, r1.getStatusCode());
        assertEquals("SUCCEEDED", r1.getBody().get("state"));

        // Second attempt with same token fails closed before mutation boundary
        ResponseEntity<Map<String, Object>> r2 = controller.executeFailover("cls-uuid-cp", payload, req);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, r2.getStatusCode());
        assertEquals("ABORTED_PRE_MUTATION", r2.getBody().get("state"));
    }

    @Test
    @DisplayName("Sticky quarantine engaged on OUTCOME_UNKNOWN and lifted only with 4-eyes acknowledgment")
    void stickyQuarantineAndAcknowledgment() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "operator-alice");

        // Executor throws across mutation boundary
        executionService.registerExecutor(new CheckPointClusterXLExecutor(
            (memberId, cmd) -> {
                throw new RuntimeException("SSH transport dropped mid-command");
            },
            (clusterId, memberId) -> MemberObservation.failed(memberId, "Unreachable")
        ));

        String tokenId = issueValidToken("cls-uuid-cp", "nonce-001");
        var payload = new FailoverExecutionController.ExecutePayload(tokenId, "nonce-001", "CONTROLLED_FAILOVER");

        ResponseEntity<Map<String, Object>> response = controller.executeFailover("cls-uuid-cp", payload, req);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("OUTCOME_UNKNOWN", response.getBody().get("state"));
        assertEquals(true, response.getBody().get("quarantine_active"));

        // Subsequent executions are blocked by sticky quarantine
        assertTrue(executionService.isClusterQuarantined("cls-uuid-cp"));

        String token2 = issueValidToken("cls-uuid-cp", "nonce-002");
        var payload2 = new FailoverExecutionController.ExecutePayload(token2, "nonce-002", "CONTROLLED_FAILOVER");
        ResponseEntity<Map<String, Object>> blockedResp = controller.executeFailover("cls-uuid-cp", payload2, req);
        assertEquals(HttpStatus.CONFLICT, blockedResp.getStatusCode());

        // Acknowledge quarantine requires 4-eyes (requester != approver)
        var ackPayload = new FailoverExecutionController.AcknowledgeQuarantinePayload("operator-bob", "Verified cluster state manually via out-of-band console");
        ResponseEntity<Map<String, Object>> ackResp = controller.acknowledgeQuarantine("cls-uuid-cp", ackPayload, req);
        assertEquals(HttpStatus.OK, ackResp.getStatusCode());
        assertEquals("QUARANTINE_LIFTED", ackResp.getBody().get("status"));
        assertFalse(executionService.isClusterQuarantined("cls-uuid-cp"));
    }

    /**
     * CF-P0.18. Return to service used to be judged on the restored member alone, so the
     * one state a highly available pair must never reach -- both members active -- was
     * reported as a clean success.
     */
    @Test
    @DisplayName("return to service with both members ACTIVE is quarantined, never reported as success")
    void returnToServiceRefusesSplitBrain() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "operator-alice");

        executionService.registerExecutor(new CheckPointClusterXLExecutor(
            (memberId, cmd) -> FailoverCommandResult.success("Executed " + cmd),
            (clusterId, memberId) -> MemberObservation.of(memberId, "ACTIVE", true, true, "OK")
        ));

        String tokenId = issueValidToken("cls-uuid-cp", "nonce-rts-1");
        var payload = new FailoverExecutionController.ExecutePayload(tokenId, "nonce-rts-1", "RETURN_TO_SERVICE");

        ResponseEntity<Map<String, Object>> response = controller.executeFailover("cls-uuid-cp", payload, req);

        assertEquals("OUTCOME_UNKNOWN", response.getBody().get("state"),
            "both members reporting ACTIVE is split brain and must not be a success");
        assertEquals(true, response.getBody().get("quarantine_active"));
        assertTrue(executionService.isClusterQuarantined("cls-uuid-cp"));
    }

    @Test
    @DisplayName("return to service quarantines when the peer's role is not affirmatively recognized")
    void returnToServiceRefusesUnrecognizedPeerRole() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "operator-alice");

        executionService.registerExecutor(new CheckPointClusterXLExecutor(
            (memberId, cmd) -> FailoverCommandResult.success("Executed " + cmd),
            // The restored member recovers, but the peer answers with a role this build
            // has never proven the meaning of. Silence about the peer is not safety.
            (clusterId, memberId) -> MemberObservation.of(
                memberId, "dev-cp-1".equals(memberId) ? "ACTIVE" : "SOME-NOVEL-VENDOR-ROLE", true, true, "OK")
        ));

        String tokenId = issueValidToken("cls-uuid-cp", "nonce-rts-2");
        var payload = new FailoverExecutionController.ExecutePayload(tokenId, "nonce-rts-2", "RETURN_TO_SERVICE");

        ResponseEntity<Map<String, Object>> response = controller.executeFailover("cls-uuid-cp", payload, req);

        assertEquals("OUTCOME_UNKNOWN", response.getBody().get("state"));
        assertEquals(true, response.getBody().get("quarantine_active"));
    }

    @Test
    @DisplayName("return to service succeeds when the restored member recovers and the peer is a recognized, non-conflicting role")
    void returnToServiceSucceedsOnASafePair() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "operator-alice");

        executionService.registerExecutor(new CheckPointClusterXLExecutor(
            (memberId, cmd) -> FailoverCommandResult.success("Executed " + cmd),
            (clusterId, memberId) -> MemberObservation.of(
                memberId, "dev-cp-1".equals(memberId) ? "STANDBY" : "ACTIVE", true, true, "OK")
        ));

        String tokenId = issueValidToken("cls-uuid-cp", "nonce-rts-3");
        var payload = new FailoverExecutionController.ExecutePayload(tokenId, "nonce-rts-3", "RETURN_TO_SERVICE");

        ResponseEntity<Map<String, Object>> response = controller.executeFailover("cls-uuid-cp", payload, req);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SUCCEEDED", response.getBody().get("state"));
        assertEquals(false, response.getBody().get("quarantine_active"));
    }

    private static class StubPreflightService extends PreflightService {
        private PreflightVerdict verdict = PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED;

        StubPreflightService() {
            super(null, null, null);
        }

        void setVerdict(PreflightVerdict verdict) {
            this.verdict = verdict;
        }

        @Override
        public com.securityexpert.nexus.ui2.jobs.failover.model.PreflightReport getLatestReport(String clusterRef) {
            return new com.securityexpert.nexus.ui2.jobs.failover.model.PreflightReport(
                clusterRef,
                "CLS-ROMEO-01",
                "CHECK_POINT",
                "CLUSTER_XL_HA",
                Instant.now(),
                verdict,
                List.of(),
                0,
                0,
                0,
                0,
                verdict == PreflightVerdict.BLOCKING_CONDITIONS_PRESENT ? 1 : 0
            );
        }

        @Override
        public ClusterEvidenceSnapshot buildSnapshotForCluster(String clusterRef) {
            var memA = new ClusterMemberEvidence(
                "dev-cp-1", "FW-TANGO-01", "ACTIVE", "STANDBY", "CLUSTER_XL_HA", "SYNC_OK",
                0, true, 0, true, List.of(), true, 0, "take_79", "sha256:hash", 20, 40,
                1000, 50000, false, 0, 0, false, true, Instant.now()
            );
            var memB = new ClusterMemberEvidence(
                "dev-cp-2", "FW-JULIET-06", "STANDBY", "ACTIVE", "CLUSTER_XL_HA", "SYNC_OK",
                0, true, 0, true, List.of(), true, 0, "take_79", "sha256:hash", 20, 40,
                1000, 50000, false, 0, 0, false, true, Instant.now()
            );
            return new ClusterEvidenceSnapshot(
                clusterRef, "CLS-ROMEO-01", "CHECK_POINT", "CLUSTER_XL_HA", null, memA, memB, Instant.now()
            );
        }
    }
}
