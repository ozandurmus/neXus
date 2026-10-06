package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverLeaseToken;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FourEyesValidationRule;
import com.securityexpert.nexus.ui2.jobs.failover.model.CheckResult;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterEvidenceSnapshot;
import com.securityexpert.nexus.ui2.jobs.failover.model.ClusterMemberEvidence;
import com.securityexpert.nexus.ui2.jobs.failover.model.PreflightReport;
import com.securityexpert.nexus.ui2.jobs.failover.model.PreflightVerdict;
import com.securityexpert.nexus.ui2.jobs.failover.plan.FailoverDryRunPlanner;
import com.securityexpert.nexus.ui2.service.failover.FailoverAuthorizationService;
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

import static org.junit.jupiter.api.Assertions.*;

class FailoverAuthorizationControllerTest {

    private static final byte[] SECRET_KEY = "test-secret-key-at-least-16-bytes!".getBytes(StandardCharsets.UTF_8);

    private StubPreflightService preflightService;
    private FailoverAuthorizationService authzService;
    private FailoverAuthorizationController controller;

    @BeforeEach
    void setUp() {
        preflightService = new StubPreflightService();
        FourEyesValidationRule rule = new FourEyesValidationRule(SECRET_KEY);
        FailoverDryRunPlanner planner = new FailoverDryRunPlanner();
        authzService = new FailoverAuthorizationService(preflightService, rule, planner);
        controller = new FailoverAuthorizationController(authzService);
    }

    @Test
    void callerSuppliedApproverNeverAuthorizesEvenWithAnAuthenticatedCaller() {
        var mocked=org.mockito.Mockito.mock(FailoverAuthorizationService.class);
        var fenced=new FailoverAuthorizationController(mocked);
        for (String actor:new String[]{null,"synthetic-initiator"}) {
            var req=new MockHttpServletRequest();
            req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,actor);
            var payload=new FailoverAuthorizationController.AuthorizePayload(
                "forged-approver","Synthetic request","window-a","digest-a","nonce-a");
            var response=fenced.authorize("unit-a",payload,req);
            assertEquals(HttpStatus.FORBIDDEN,response.getStatusCode());
            assertEquals("REQUEST_BOUND_APPROVAL_REQUIRED",response.getBody().get("code"));
        }
        org.mockito.Mockito.verifyNoInteractions(mocked);
    }

    @Test
    @DisplayName("compileDryRun() compiles plan with DRY_RUN type and does NOT consume token; execution consumes lease")
    void compileDryRunAndEnforceSingleUse() {
        preflightService.setVerdict(PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "operator-alice");

        // The legacy internal dry-run mechanism remains testable; HTTP cannot mint its lease.
        var result=authzService.authorizeFailover(new com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverAuthorizationRequest(
            "cls-01","operator-alice","operator-bob","Synthetic dry-run test","CHG-101","sha256:digest-abc","nonce-001"));
        String tokenId=((com.securityexpert.nexus.ui2.jobs.failover.authz.FourEyesAuthorizationResult.Authorized)result).leaseToken().tokenId();

        // 2. Compile dry-run
        var dryRunPayload = new FailoverAuthorizationController.DryRunPayload(tokenId, "nonce-001");
        ResponseEntity<Map<String, Object>> dryRunResp = controller.compileDryRun("cls-01", dryRunPayload, req);

        assertEquals(HttpStatus.OK, dryRunResp.getStatusCode());
        assertEquals("DRY_RUN", dryRunResp.getBody().get("plan_type"));
        assertEquals(false, dryRunResp.getBody().get("mutation_authorized"));
        assertNotNull(dryRunResp.getBody().get("transition_steps"));
        assertNotNull(dryRunResp.getBody().get("reversal_steps"));

        // 3. Dry-run can be inspected again without consuming the execution lease (Astra & Fable review fix)
        ResponseEntity<Map<String, Object>> secondDryRunResp = controller.compileDryRun("cls-01", dryRunPayload, req);
        assertEquals(HttpStatus.OK, secondDryRunResp.getStatusCode());

        // 4. Consume lease via execution boundary
        FailoverLeaseToken consumed = authzService.consumeLeaseForExecution("cls-01", tokenId, "nonce-001");
        assertNotNull(consumed);

        // 5. Subsequent dry-run or execution with same token must fail (single-use invariant)
        ResponseEntity<Map<String, Object>> replayResp = controller.compileDryRun("cls-01", dryRunPayload, req);
        assertEquals(HttpStatus.CONFLICT, replayResp.getStatusCode());
        assertEquals("TOKEN_ALREADY_CONSUMED", replayResp.getBody().get("code"));

        assertThrows(IllegalStateException.class,
            () -> authzService.consumeLeaseForExecution("cls-01", tokenId, "nonce-001"));
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
        public PreflightReport getLatestReport(String clusterRef) {
            int pass = verdict == PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED ? 10 : 7;
            int fail = verdict == PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED ? 0 : 3;
            int blockingFails = verdict == PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED ? 0 : 3;
            return new PreflightReport(
                "cls-uuid-001",
                "CLS-ROMEO-01",
                "CHECK_POINT",
                "CLUSTER_XL_HA",
                Instant.now(),
                verdict,
                List.of(),
                10,
                pass,
                fail,
                0,
                blockingFails
            );
        }

        @Override
        public ClusterEvidenceSnapshot buildSnapshotForCluster(String clusterRef) {
            var memA = new ClusterMemberEvidence(
                "dev-01", "FW-TANGO-01", "ACTIVE", "STANDBY", "CLUSTER_XL_HA", "SYNC_OK",
                0, true, 0, true, List.of(), true, 0, "take_79", "sha256:h", 20, 40, 1000, 50000,
                false, 0, 0, false, true, Instant.now()
            );
            var memB = new ClusterMemberEvidence(
                "dev-02", "FW-JULIET-06", "STANDBY", "ACTIVE", "CLUSTER_XL_HA", "SYNC_OK",
                0, true, 0, true, List.of(), true, 0, "take_79", "sha256:h", 20, 40, 1000, 50000,
                false, 0, 0, false, true, Instant.now()
            );
            return new ClusterEvidenceSnapshot(
                "cls-uuid-001", "CLS-ROMEO-01", "CHECK_POINT", "CLUSTER_XL_HA", null, memA, memB, Instant.now()
            );
        }
    }
}
