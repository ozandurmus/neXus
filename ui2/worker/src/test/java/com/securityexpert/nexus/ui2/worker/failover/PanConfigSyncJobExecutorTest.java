package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.JooqPanConfigSyncRepository;
import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;

/**
 * PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md §3.2-§4 with a scripted XML API: no socket, host or device contact. Every
 * identity, serial and address is synthetic and built at runtime.
 */
class PanConfigSyncJobExecutorTest {
    private static final String CLUSTER = "CLS-" + "TEST-" + 1, SOURCE = "member-" + "src", TARGET = "member-" + "dst";
    private static final String SYNC_ID = "sync-" + 1, JOB = "job-" + 1;
    private static String serial(String member) { return "SN" + Math.abs(member.hashCode() % 100000); }
    private static String address(String member) { return "192.0.2." + (SOURCE.equals(member) ? 11 : 12); }

    /** What the scripted firewalls report and how the write is answered. */
    private static final class Script {
        String sourceRole = "active", targetRole = "passive", runningSync = "not synchronized";
        String reply = "<response status=\"success\"><result>synthetic</result></response>";
        int replyStatus = 200;
        boolean replyThrows;
        Runnable afterPrepare = () -> {};
        final List<String> calls = new ArrayList<>();
        int syncs;
        DeviceTransport transport() {
            return (DeviceTransport) Proxy.newProxyInstance(DeviceTransport.class.getClassLoader(),
                new Class<?>[]{DeviceTransport.class}, (proxy, method, args) -> {
                    if (!"xmlApiCall".equals(method.getName())) throw new AssertionError(method.getName());
                    ApiTarget target = (ApiTarget) args[0];
                    XmlApiSpec spec = (XmlApiSpec) args[1];
                    String member = target.endpointId();
                    assertEquals(address(member), target.baseUrl());
                    if ("keygen".equals(spec.type())) {
                        calls.add("keygen:" + member);
                        return new XmlApiResult.Completed(200, "<response status=\"success\"><result><key>synthetic-key</key></result></response>");
                    }
                    String cmd = spec.formParams().get("cmd");
                    if (PanConfigSyncJobExecutor.SYNC.equals(cmd)) {
                        calls.add("sync:" + member); syncs++;
                        assertEquals("op", spec.type());
                        if (replyThrows) throw new IllegalStateException("SYNTHETIC_TRANSPORT_FAILURE");
                        return new XmlApiResult.Completed(replyStatus, reply);
                    }
                    assertEquals(PanConfigSyncJobExecutor.STATE, cmd);
                    calls.add("state:" + member);
                    boolean source = SOURCE.equals(member);
                    String local = source ? sourceRole : targetRole, peer = source ? targetRole : sourceRole;
                    String other = source ? TARGET : SOURCE;
                    return new XmlApiResult.Completed(200, "<response status=\"success\"><result><group><mode>Active-Passive</mode>"
                        + "<running-sync>" + runningSync + "</running-sync><running-sync-enabled>yes</running-sync-enabled>"
                        + "<local-info><state>" + local + "</state><serial-num>" + serial(member) + "</serial-num></local-info>"
                        + "<peer-info><state>" + peer + "</state><serial-num>" + serial(other) + "</serial-num>"
                        + "<conn-status>up</conn-status></peer-info></group></result></response>");
                });
        }
    }

    private static DeviceSummaryRecord summary(String id) {
        return new DeviceSummaryRecord(id, "gateway", "palo_alto", DeviceEnrollmentState.ENROLLED,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(CLUSTER));
    }
    private static DeviceRepository devices() {
        return (DeviceRepository) Proxy.newProxyInstance(DeviceRepository.class.getClassLoader(),
            new Class<?>[]{DeviceRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                case "findMembersByClusterRef" -> List.of(summary(SOURCE), summary(TARGET));
                case "find" -> Optional.of(new DeviceRecord((String) args[0], "gateway", "palo_alto", "manual",
                    Instant.EPOCH, false, DeviceEnrollmentState.ENROLLED, false, "credential-ref"));
                case "findEndpointByDeviceId" -> Optional.of(new EndpointRecord((String) args[0], (String) args[0],
                    "pan_xml_api", address((String) args[0]), Instant.EPOCH));
                case "findConfirmFacts" -> Optional.of(new DeviceConfirmFacts(Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of(serial((String) args[0])), Optional.empty(),
                    DeviceConfirmFacts.IDENTITY_MISMATCH_NONE, Optional.empty(), Optional.empty(), Optional.of(CLUSTER),
                    Optional.empty(), DeviceConfirmFacts.PEER_FOLLOW_CORROBORATED, Optional.empty()));
                default -> throw new AssertionError(method.getName());
            });
    }
    private static JobStepAttemptRepository attempts() {
        AtomicInteger counter = new AtomicInteger();
        return (JobStepAttemptRepository) Proxy.newProxyInstance(JobStepAttemptRepository.class.getClassLoader(),
            new Class<?>[]{JobStepAttemptRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                case "findByJobAndStep" -> List.of();
                case "insertPreContact" -> "attempt-" + counter.incrementAndGet();
                case "markBoundaryCrossed", "writeOutcome" -> true;
                default -> throw new AssertionError(method.getName());
            });
    }
    private static GateRegistryPort gates() {
        var rows = GateRegistryFixtureLoader.loadFromStream(PanConfigSyncJobExecutorTest.class
            .getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }
    private static JooqPanConfigSyncRepository.ConfigSync record() {
        return new JooqPanConfigSyncRepository.ConfigSync(SYNC_ID, CLUSTER, SOURCE, TARGET, "run-0", "synthetic-actor",
            "security_admin", Instant.now(), false, false, JOB, "QUEUED", null, null, false, null, null, null, null);
    }

    private record Result(JooqPanConfigSyncRepository store, Script script, JobLeaseRepository leases) {}

    private static Result run(Script script, FailoverMutationSwitch mutationSwitch, String admission) {
        var store = mock(JooqPanConfigSyncRepository.class);
        when(store.byJob(JOB)).thenReturn(Optional.of(record()));
        when(store.admission(SYNC_ID, JOB, 1L)).thenReturn(admission);
        when(store.prepareSend(eq(SYNC_ID), eq(JOB), eq(1L), eq(2), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            script.afterPrepare.run();
            return new JooqPanConfigSyncRepository.Prepared("ADMITTED", "attempt-write");
        });
        when(store.recordReply(anyString(), anyString(), anyLong(), anyString(), nullable(String.class), anyString())).thenReturn(true);
        when(store.finish(anyString(), anyString(), anyLong(), anyString(), anyBoolean())).thenReturn(true);
        var leases = mock(JobLeaseRepository.class);
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        new PanConfigSyncJobExecutor(store, devices(), leases, attempts(), script.transport(),
            ref -> new PanCredentialMaterial("synthetic-user", "synthetic-password".toCharArray()), gates(), d -> {},
            Duration.ZERO, mutationSwitch).execute(JOB, 1);
        return new Result(store, script, leases);
    }
    private static Result run(Script script) { return run(script, new FailoverMutationSwitch(true), "ADMITTED"); }

    @Test void sendsOnceToTheActiveOnlyAfterAFreshReadOfBothMembersThenReRunsReadiness() {
        var result = run(new Script());
        assertEquals(List.of("keygen:" + SOURCE, "keygen:" + TARGET, "state:" + SOURCE, "state:" + TARGET, "sync:" + SOURCE),
            result.script().calls);
        verify(result.store()).prepareSend(SYNC_ID, JOB, 1L, 2, SOURCE, "pan_failover_config_sync", "configuration-write");
        verify(result.store()).recordReply(SYNC_ID, JOB, 1L, "attempt-write", "success", "MATCHED");
        verify(result.store()).finish(SYNC_ID, JOB, 1L, JooqPanConfigSyncRepository.REQUESTED, true);
    }

    @Test void refusesWhenTheRolesFlippedAndSendsNothing() {
        for (String[] roles : new String[][]{{"passive", "active"}, {"active", "active"}, {"suspended", "active"}}) {
            var script = new Script(); script.sourceRole = roles[0]; script.targetRole = roles[1];
            var result = run(script);
            assertEquals(0, script.syncs, String.join("/", roles));
            verify(result.store(), never()).prepareSend(anyString(), anyString(), anyLong(), anyInt(), anyString(), anyString(), anyString());
            verify(result.store()).finish(eq(SYNC_ID), eq(JOB), eq(1L), argThat(code -> "ROLES_CHANGED".equals(code)
                || "HA_STATE_UNKNOWN".equals(code)), eq(true));
        }
        var script = new Script(); script.sourceRole = "passive"; script.targetRole = "active";
        verify(run(script).store()).finish(SYNC_ID, JOB, 1L, "ROLES_CHANGED", true);
    }

    @Test void refusesWhenTheMutationSwitchIsOffBeforeAnyContact() {
        var script = new Script();
        var result = run(script, new FailoverMutationSwitch(false), "ADMITTED");
        assertTrue(script.calls.isEmpty());
        verify(result.store(), never()).admission(anyString(), anyString(), anyLong());
        verify(result.store()).finish(SYNC_ID, JOB, 1L, FailoverMutationSwitch.DISABLED, false);
    }

    @Test void aSwitchTurnedOffAfterThePreparationRecordsNotSentAndSendsNothing() {
        var enabled = new AtomicBoolean(true);
        var mutationSwitch = mock(FailoverMutationSwitch.class);
        when(mutationSwitch.enabled()).thenAnswer(inv -> enabled.get());
        var script = new Script(); script.afterPrepare = () -> enabled.set(false);
        var result = run(script, mutationSwitch, "ADMITTED");
        assertEquals(0, script.syncs);
        verify(result.store()).recordReply(SYNC_ID, JOB, 1L, "attempt-write", null, "NOT_SENT");
        verify(result.store()).finish(SYNC_ID, JOB, 1L, "MUTATION_DISABLED_BEFORE_SEND", true);
    }

    @Test void anAdmissionRefusalEndsTheRecordWithItsCodeBeforeContact() {
        for (String code : List.of("OPEN_INCIDENT", "RUN_ALREADY_ACTIVE", JooqPanConfigSyncRepository.EXPIRED,
                "BUSINESS_HOURS_CONFIRM_REQUIRED", "MEMBER_SET_CHANGED")) {
            var script = new Script();
            var result = run(script, new FailoverMutationSwitch(true), code);
            assertTrue(script.calls.isEmpty(), code);
            verify(result.store()).finish(SYNC_ID, JOB, 1L, code, false);
        }
    }

    @Test void aSynchronizedOrUnknownOrInProgressPairIsNeverSent() {
        for (String[] sync : new String[][]{{"synchronized", "CONFIG_ALREADY_SYNCHRONIZED"},
                {"", "CONFIG_SYNC_STATE_UNKNOWN"}, {"synchronization in progress", "CONFIG_SYNC_IN_PROGRESS"}}) {
            var script = new Script(); script.runningSync = sync[0];
            var result = run(script);
            assertEquals(0, script.syncs, sync[1]);
            verify(result.store()).finish(SYNC_ID, JOB, 1L, sync[1], true);
        }
    }

    @Test void theReplyStatusIsParsedAndNeverRetried() {
        var error = new Script(); error.reply = "<response status=\"error\"><msg>synthetic</msg></response>";
        var result = run(error);
        assertEquals(1, error.syncs);
        verify(result.store()).recordReply(SYNC_ID, JOB, 1L, "attempt-write", "error", "FAILED");
        verify(result.store()).finish(SYNC_ID, JOB, 1L, JooqPanConfigSyncRepository.DEVICE_REPORTED_ERROR, true);
        var lost = new Script(); lost.replyStatus = 504;
        var thrown = new Script(); thrown.replyThrows = true;
        var garbled = new Script(); garbled.reply = "<html>status=\"success\"</html>";
        for (Script uncertain : List.of(lost, thrown, garbled)) {
            var outcome = run(uncertain);
            assertEquals(1, uncertain.syncs);
            verify(outcome.store()).recordReply(SYNC_ID, JOB, 1L, "attempt-write", null, "OUTCOME_UNKNOWN");
            verify(outcome.store()).finish(SYNC_ID, JOB, 1L, JooqPanConfigSyncRepository.OUTCOME_UNKNOWN, true);
        }
    }

    @Test void deviceStatusReadsTheRootElementOnly() {
        assertEquals("success", PanConfigSyncJobExecutor.deviceStatus(new XmlApiResult.Completed(200,
            "<?xml version=\"1.0\"?>\n<response status='success' code=\"19\"><result/></response>")));
        assertEquals("error", PanConfigSyncJobExecutor.deviceStatus(new XmlApiResult.Completed(200, "<response status=\"error\"/>")));
        assertNull(PanConfigSyncJobExecutor.deviceStatus(new XmlApiResult.Completed(200, "<other><response status=\"success\"/></other>")));
        assertNull(PanConfigSyncJobExecutor.deviceStatus(new XmlApiResult.Completed(500, "<response status=\"success\"/>")));
        assertNull(PanConfigSyncJobExecutor.deviceStatus(new XmlApiResult.Completed(200, null)));
        assertNull(PanConfigSyncJobExecutor.deviceStatus(new XmlApiResult.Completed(200,
            "<response status=\"success\">" + "x".repeat(262145) + "</response>")));
        assertNull(PanConfigSyncJobExecutor.deviceStatus(new XmlApiResult.Failed("synthetic")));
    }

    @Test void aMissingRecordFailsTheJobWithoutContact() {
        var store = mock(JooqPanConfigSyncRepository.class);
        when(store.byJob(JOB)).thenReturn(Optional.empty());
        var leases = mock(JobLeaseRepository.class);
        var script = new Script();
        new PanConfigSyncJobExecutor(store, devices(), leases, attempts(), script.transport(), ref -> null, gates(),
            d -> {}, Duration.ZERO, new FailoverMutationSwitch(true)).execute(JOB, 1);
        assertTrue(script.calls.isEmpty());
        verify(leases).transitionState(eq(JOB), eq(1L), any(), eq(com.securityexpert.nexus.ui2.jobs.JobState.FAILED),
            anyString(), anyString(), eq("CONFIG_SYNC_NOT_FOUND"));
    }
}
