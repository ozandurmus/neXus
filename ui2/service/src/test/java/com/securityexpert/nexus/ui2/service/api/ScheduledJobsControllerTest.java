package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.persistence.schedule.ScheduledJobFamilyRepository;
import com.securityexpert.nexus.ui2.persistence.schedule.ScheduledJobFamilyRepository.Change;
import com.securityexpert.nexus.ui2.persistence.schedule.ScheduledJobFamilyRepository.Row;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.schedule.ScheduledJobFamily;
import com.securityexpert.nexus.ui2.service.schedule.ScheduledJobSwitches;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/** V203: the controller's own decisions -- who may write, the acknowledgement, the 409 body, and aiview masking. */
class ScheduledJobsControllerTest {
    private static final Instant MORNING = Instant.parse("2026-11-10T07:00:00Z"); // 10:00 GMT+3, business hours
    private static final Instant EVENING = Instant.parse("2026-11-10T19:00:00Z"); // 22:00 GMT+3
    private static final String ACTOR = "synthetic-admin";

    private final ScheduledJobFamilyRepository repository = mock(ScheduledJobFamilyRepository.class);
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);

    @AfterEach
    void closeGrant() {
        ManualAdmission.close();
    }

    private ScheduledJobsController controller(Instant now, boolean mayWrite) {
        Clock clock = Clock.fixed(now, JobWindowPolicy.ZONE_ID);
        var switches = new ScheduledJobSwitches(repository, new ScheduledJobSwitches.Deployment(true, true, false),
                new JobWindowPolicy(clock, 60), new BusinessHours(LocalTime.of(8, 0), LocalTime.of(20, 0)), clock,
                () -> Optional.of(true));
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty());
        when(rbac.evaluateAny(anyString(), anySet(), any())).thenReturn(mayWrite ? permitted : denied);
        return new ScheduledJobsController(switches, new ActionRegistry(), rbac, names);
    }

    private static MockHttpServletRequest request(boolean replayViewer) {
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR);
        if (replayViewer) request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        return request;
    }

    private Map<String, Row> rows() {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (ScheduledJobFamily f : ScheduledJobFamily.values())
            rows.put(f.key(), new Row(f.key(), true, "Free text reason", "alice.admin", EVENING));
        return rows;
    }

    @Test
    void aiviewReadsMaskedAndNeverGetsTheReasonOrTheRawPerson() {
        var controller = controller(EVENING, true);
        when(repository.readAll()).thenReturn(rows());
        when(repository.lastSubmitted(any())).thenReturn(Map.of());
        var body = controller.read(request(true)).getBody();
        assertEquals(true, body.get("masked"));
        assertEquals(false, body.get("can_write"), "an aiview session with an admin role still cannot write");
        @SuppressWarnings("unchecked")
        var families = (java.util.List<Map<String, Object>>) body.get("families");
        for (Map<String, Object> f : families) {
            assertNull(f.get("reason"));
            assertTrue(String.valueOf(f.get("changed_by")).startsWith("USR-"), String.valueOf(f.get("changed_by")));
        }
    }

    @Test
    void anAdministratorReadsThePlainViewWithCanWrite() {
        var controller = controller(EVENING, true);
        when(repository.readAll()).thenReturn(rows());
        when(repository.lastSubmitted(any())).thenReturn(Map.of());
        var body = controller.read(request(false)).getBody();
        assertEquals(true, body.get("can_write"));
        assertEquals(false, body.get("masked"));
    }

    @Test
    void aiviewAndNonAdministratorsGet403OnTheWriteAndNothingIsStored() {
        var denied = controller(EVENING, false).set("nightly_inventory",
                new ScheduledJobsController.SetRequest(false, "A good reason"), request(false));
        assertEquals(403, denied.getStatusCode().value());
        assertEquals("SCHEDULED_JOBS_NOT_PERMITTED", denied.getBody().get("code"));
        var aiview = controller(EVENING, true).set("nightly_inventory",
                new ScheduledJobsController.SetRequest(false, "A good reason"), request(true));
        assertEquals(403, aiview.getStatusCode().value());
        verify(repository, never()).set(anyString(), anyBoolean(), anyString(), anyString(), anyList());
    }

    @Test
    void insideBusinessHoursWithoutTheGrantAcknowledgementAnswers409WithTheAckNames() {
        var controller = controller(MORNING, true);
        ManualAdmission.open(new ManualAdmission.Grant(ACTOR, ManualAdmission.OPERATION_ADMIN, false));
        var response = controller.set("nightly_inventory", new ScheduledJobsController.SetRequest(false, "A good reason"), request(false));
        assertEquals(409, response.getStatusCode().value());
        assertEquals("BUSINESS_HOURS_CONFIRM_REQUIRED", response.getBody().get("code"));
        assertEquals(ManualAdmission.ACK_HEADER, response.getBody().get("ack_header"));
        assertEquals(ManualAdmission.ACK_PARAMETER, response.getBody().get("ack_parameter"));
        verify(repository, never()).set(anyString(), anyBoolean(), anyString(), anyString(), anyList());
    }

    @Test
    void anAcknowledgedChangeIsStoredAndTheAnswerCarriesTheCleanupCounts() {
        var controller = controller(MORNING, true);
        ManualAdmission.open(new ManualAdmission.Grant(ACTOR, ManualAdmission.SECURITY_ADMIN, true));
        when(repository.set(anyString(), anyBoolean(), anyString(), anyString(), anyList())).thenReturn(Optional.of(
                new Change(new Row("discovery_refresh", false, "A good reason", ACTOR, MORNING), 3, 1)));
        when(repository.lastSubmitted(any())).thenReturn(Map.of());
        var response = controller.set("discovery_refresh", new ScheduledJobsController.SetRequest(false, "A good reason"), request(false));
        assertEquals(200, response.getStatusCode().value());
        assertEquals(3, response.getBody().get("cancelled_jobs"));
        assertEquals(1, response.getBody().get("closed_runs"));
        assertEquals("OFF", response.getBody().get("state"));
        assertFalse(response.getBody().containsKey("error"));
    }

    @Test
    void anUnknownFamilyAnswers404AndAMissingBodyAnswers400() {
        var controller = controller(EVENING, true);
        assertEquals(404, controller.set("cluster_diff", new ScheduledJobsController.SetRequest(false, "A good reason"), request(false))
                .getStatusCode().value());
        assertEquals(400, controller.set("nightly_inventory", null, request(false)).getStatusCode().value());
    }
}
