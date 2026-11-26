package com.securityexpert.nexus.ui2.worker.staticroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.staticroute.StaticRoutePreviewRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;

class StaticRoutePreviewJobExecutorTest {

    private static final String PREVIEW_ID = "srp-99999999-8888-4777-8666-555555555555";
    private static final String DEVICE_ID = "11111111-2222-4333-8444-555555555555";

    private GateRegistryPort loadGateRegistry() {
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        return key -> rows.stream().filter(r -> r.key().equals(key)).toList();
    }

    @Test
    void checkPointPreviewExecutesOnlyApprovedClass0ReadsLogsMaskedShapeLinesAndNeverSendsWrites() {
        JobLeaseRepository leases = mock(JobLeaseRepository.class);
        JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
        DeviceRepository devices = mock(DeviceRepository.class);
        DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class);
        DeviceTransport ssh = mock(DeviceTransport.class);
        DeviceTransport pan = mock(DeviceTransport.class);

        when(devices.find(DEVICE_ID)).thenReturn(Optional.of(new DeviceRecord(
                DEVICE_ID, "gateway", "check_point", "manual", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-1")));
        when(devices.findEndpointByDeviceId(DEVICE_ID)).thenReturn(Optional.of(new EndpointRecord(
                "ep-1", DEVICE_ID, "ssh_exec", "192.0.2.2", Instant.now())));
        when(devices.findSummary(anyString())).thenReturn(Optional.empty());
        when(devices.findMembersByClusterRef(anyString())).thenReturn(List.of());

        InventoryInterface eth0 = new InventoryInterface(
                "if-1", "eth0", Optional.empty(), InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP,
                List.of(new InventoryAddress("a-1", "192.0.2.2/25", InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)));
        when(inventory.findLatestRun(DEVICE_ID)).thenReturn(Optional.of(new InventoryRun(
                "run-1", DEVICE_ID, "job-0", Instant.now(), 1,
                List.of(new InventoryContext("physical", List.of(eth0), List.of())), List.of())));

        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), any())).thenReturn(true);
        when(attempts.findByJobAndStep(anyString(), anyInt())).thenReturn(List.of());
        AtomicInteger attemptSeq = new AtomicInteger();
        when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()))
                .thenAnswer(c -> "att-" + attemptSeq.incrementAndGet());
        when(attempts.markBoundaryCrossed(anyString(), anyLong())).thenReturn(true);
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(true);

        when(ssh.connect(any(), any(), any())).thenReturn(new ConnectResult.Authenticated(mock(TransportSession.class)));
        List<String> commandsSent = new ArrayList<>();
        when(ssh.exec(any(), any(), any())).thenAnswer(call -> {
            String cmd = ((ExecSpec) call.getArgument(1)).command();
            commandsSent.add(cmd);
            return switch (cmd) {
                case "cphaprob stat" -> new ExecResult.Completed(
                        "Cluster Mode:   High Availability (Active Up)\n1 (local) 192.0.2.2 100% ACTIVE FW-TANGO-04\n", 0);
                case "clish -c 'show route static'" -> new ExecResult.Completed("", 0);
                case "ip -4 route show table all" -> new ExecResult.Completed(
                        "default via 192.0.2.1 dev eth0 proto 7\n192.0.2.0/25 dev eth0 proto kernel scope link src 192.0.2.2\n", 0);
                case "ip -4 route get 192.0.2.129" -> new ExecResult.Completed(
                        "192.0.2.129 via 192.0.2.1 dev eth0 src 192.0.2.2\n", 0);
                default -> new ExecResult.ChannelFailed("unexpected command: " + cmd);
            };
        });

        List<String> shapeLines = new ArrayList<>();
        InMemoryPreviewStore store = new InMemoryPreviewStore(new StaticRoutePreviewRepository.PreviewRow(
                PREVIEW_ID, "idem-cp-1", "hash-1", "actor-1", Instant.now(),
                DEVICE_ID, DEVICE_ID, "check_point", "gaia-default", "ADD",
                "192.0.2.128/25", "192.0.2.1", Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(2), Optional.empty(), Optional.empty(), "192.0.2.129",
                Optional.of("CHG-100"), Optional.of("Preview test"), Optional.of("job-1"), Optional.of("CLAIMED"),
                "QUEUED", "PENDING", Optional.empty(), false, Optional.empty(), "[]", Optional.empty(), "{}", "[]",
                Optional.empty()));

        new StaticRoutePreviewJobExecutor(
                leases, attempts, devices, inventory, DevicePlatformFactsRepository.NONE,
                ssh, pan, ref -> new PanCredentialMaterial("u", "p".toCharArray()),
                loadGateRegistry(), store, shapeLines::add)
                .execute("job-1", 1L, DEVICE_ID, "192.0.2.2", 22, "cred-1");

        assertEquals(List.of(
                "cphaprob stat",
                "clish -c 'show route static'",
                "ip -4 route show table all",
                "ip -4 route get 192.0.2.129"), commandsSent);
        assertEquals(4, shapeLines.size());
        for (String line : shapeLines) {
            assertFalse(line.contains("FW-TANGO-04"));
            assertFalse(line.contains("192.0.2.129"));
        }
        assertEquals("DONE", store.lastState);
        assertEquals("PASS", store.lastVerdict);
        assertNotNull(store.lastPreRouteSha);
        assertTrue(store.lastBeforePathJson.contains("\"selectedPrefix\":\"0.0.0.0/0\""));
        assertTrue(store.lastCommandPlanJson.contains("\"mutationPossible\":false"));
        assertNotNull(store.lastInversePlanJson);
        assertTrue(store.lastInversePlanJson.startsWith("["), "inverse_plan_json must serialize as a JSON array");
    }

    @Test
    void ciscoAsaMultipleContextSendsChangetoContextAndAbortsImmediatelyOnUnexpectedPrompt() {
        JobLeaseRepository leases = mock(JobLeaseRepository.class);
        JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
        DeviceRepository devices = mock(DeviceRepository.class);
        DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class);
        DeviceTransport ssh = mock(DeviceTransport.class);
        DeviceTransport pan = mock(DeviceTransport.class);

        when(devices.find(DEVICE_ID)).thenReturn(Optional.of(new DeviceRecord(
                DEVICE_ID, "gateway", "cisco_asa", "manual", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-1")));
        when(devices.findEndpointByDeviceId(DEVICE_ID)).thenReturn(Optional.of(new EndpointRecord(
                "ep-1", DEVICE_ID, "ssh_exec", "192.0.2.2", Instant.now())));
        when(devices.findSummary(anyString())).thenReturn(Optional.empty());
        when(devices.findMembersByClusterRef(anyString())).thenReturn(List.of());
        when(inventory.findLatestRun(DEVICE_ID)).thenReturn(Optional.empty());

        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), any())).thenReturn(true);
        when(attempts.findByJobAndStep(anyString(), anyInt())).thenReturn(List.of());
        AtomicInteger attemptSeq = new AtomicInteger();
        when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()))
                .thenAnswer(c -> "att-" + attemptSeq.incrementAndGet());
        when(attempts.markBoundaryCrossed(anyString(), anyLong())).thenReturn(true);
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(true);

        TransportSession session = new TransportSession() {
            @Override public String sessionId() { return "s-1"; }
            @Override public Optional<String> interactivePrompt() { return Optional.of("FW-TANGO-04/wrong-ctx#"); }
        };
        when(ssh.connect(any(), any(), any())).thenReturn(new ConnectResult.Authenticated(session));

        List<String> commandsSent = new ArrayList<>();
        when(ssh.execInteractive(any(), any(), any())).thenAnswer(call -> {
            String cmd = ((ExecSpec) call.getArgument(1)).command();
            commandsSent.add(cmd);
            return switch (cmd) {
                case "show curpriv" -> new ExecResult.Completed("Current privilege level : 15\n", 0);
                case "show mode" -> new ExecResult.Completed("Security context mode: multiple\n", 0);
                case "changeto context ctx-alpha" -> new ExecResult.ChannelFailed("empty output");
                default -> new ExecResult.ChannelFailed("should not be sent after UNEXPECTED_PROMPT: " + cmd);
            };
        });

        InMemoryPreviewStore store = new InMemoryPreviewStore(new StaticRoutePreviewRepository.PreviewRow(
                PREVIEW_ID, "idem-asa-1", "hash-2", "actor-1", Instant.now(),
                DEVICE_ID, DEVICE_ID, "cisco_asa", "ctx-alpha", "ADD",
                "192.0.2.128/25", "192.0.2.1", Optional.of("outside"), Optional.of(1), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), "192.0.2.129",
                Optional.empty(), Optional.empty(), Optional.of("job-2"), Optional.of("CLAIMED"),
                "QUEUED", "PENDING", Optional.empty(), false, Optional.empty(), "[]", Optional.empty(), "{}", "[]",
                Optional.empty()));

        new StaticRoutePreviewJobExecutor(
                leases, attempts, devices, inventory, DevicePlatformFactsRepository.NONE,
                ssh, pan, ref -> new PanCredentialMaterial("u", "p".toCharArray()),
                loadGateRegistry(), store)
                .execute("job-2", 1L, DEVICE_ID, "192.0.2.2", 22, "cred-1");

        // Must stop immediately after changeto context when prompt does not match <host>/ctx-alpha#
        assertEquals(List.of("show curpriv", "show mode", "changeto context ctx-alpha"), commandsSent);
        assertEquals("FAIL", store.lastVerdict);
        assertEquals("UNEXPECTED_PROMPT", store.lastStopCode);
    }

    @Test
    void paloAltoPreviewUsesActionShowNeverActionGetAndEvaluatesPendingChangesAndFibLookup() {
        JobLeaseRepository leases = mock(JobLeaseRepository.class);
        JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
        DeviceRepository devices = mock(DeviceRepository.class);
        DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class);
        DeviceTransport ssh = mock(DeviceTransport.class);
        DeviceTransport pan = mock(DeviceTransport.class);

        when(devices.find(DEVICE_ID)).thenReturn(Optional.of(new DeviceRecord(
                DEVICE_ID, "gateway", "palo_alto", "manual", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-1")));
        when(devices.findEndpointByDeviceId(DEVICE_ID)).thenReturn(Optional.of(new EndpointRecord(
                "ep-1", DEVICE_ID, "pan_xml_api", "192.0.2.2", Instant.now())));
        when(devices.findSummary(anyString())).thenReturn(Optional.empty());
        when(devices.findMembersByClusterRef(anyString())).thenReturn(List.of());

        InventoryInterface eth1 = new InventoryInterface(
                "if-1", "ethernet1/1", Optional.empty(), InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP,
                List.of(new InventoryAddress("a-1", "192.0.2.2/25", InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)),
                Optional.empty(), Optional.empty(), Optional.of("trust"), Optional.of("default"), Optional.of("RUNTIME"));
        when(inventory.findLatestRun(DEVICE_ID)).thenReturn(Optional.of(new InventoryRun(
                "run-1", DEVICE_ID, "job-0", Instant.now(), 1,
                List.of(new InventoryContext("physical", List.of(eth1), List.of())), List.of())));

        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), any())).thenReturn(true);
        when(attempts.findByJobAndStep(anyString(), anyInt())).thenReturn(List.of());
        AtomicInteger attemptSeq = new AtomicInteger();
        when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()))
                .thenAnswer(c -> "att-" + attemptSeq.incrementAndGet());
        when(attempts.markBoundaryCrossed(anyString(), anyLong())).thenReturn(true);
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(true);

        List<String> configActions = new ArrayList<>();
        when(pan.xmlApiCall(any(), any(), any())).thenAnswer(call -> {
            XmlApiSpec spec = call.getArgument(1);
            if ("keygen".equals(spec.type())) {
                return new XmlApiResult.Completed(200, "<response status=\"success\"><result><key>syn-key</key></result></response>");
            }
            if ("config".equals(spec.type())) {
                configActions.add(spec.category());
                return new XmlApiResult.Completed(200, "<response status=\"success\"><result/></response>");
            }
            String cmd = spec.formParams().get("cmd");
            if ("<check><pending-changes/></check>".equals(cmd)) {
                return new XmlApiResult.Completed(200, "<response status=\"success\"><result>no</result></response>");
            }
            if (cmd.contains("<high-availability>")) {
                return new XmlApiResult.Completed(200, "<response status=\"success\"><result><enabled>no</enabled></result></response>");
            }
            if (cmd.contains("<effective-running/>")) {
                return new XmlApiResult.Completed(200, """
                        <response status="success"><result><config><devices><entry name="localhost.localdomain">
                          <network><virtual-router><entry name="default"/></virtual-router></network>
                        </entry></devices></config></result></response>
                        """);
            }
            if (cmd.contains("<routing><route/></routing>")) {
                return new XmlApiResult.Completed(200, """
                        <response status="success"><result>
                          <entry><virtual-router>default</virtual-router><destination>0.0.0.0/0</destination><nexthop>192.0.2.1</nexthop><flags>A S</flags><interface>ethernet1/1</interface></entry>
                          <entry><virtual-router>default</virtual-router><destination>192.0.2.0/25</destination><nexthop>0.0.0.0</nexthop><flags>A C</flags><interface>ethernet1/1</interface></entry>
                        </result></response>
                        """);
            }
            if (cmd.contains("<fib-lookup>")) {
                return new XmlApiResult.Completed(200, """
                        <response status="success"><result><interface>ethernet1/1</interface><ip>192.0.2.1</ip></result></response>
                        """);
            }
            return new XmlApiResult.Completed(500, "");
        });

        InMemoryPreviewStore store = new InMemoryPreviewStore(new StaticRoutePreviewRepository.PreviewRow(
                PREVIEW_ID, "idem-pan-1", "hash-3", "actor-1", Instant.now(),
                DEVICE_ID, DEVICE_ID, "palo_alto", "default", "ADD",
                "192.0.2.128/25", "192.0.2.1", Optional.of("ethernet1/1"), Optional.of(10), Optional.of(10),
                Optional.empty(), Optional.of("nxs-route-1"), Optional.empty(), "192.0.2.129",
                Optional.empty(), Optional.empty(), Optional.of("job-3"), Optional.of("CLAIMED"),
                "QUEUED", "PENDING", Optional.empty(), false, Optional.empty(), "[]", Optional.empty(), "{}", "[]",
                Optional.empty()));

        new StaticRoutePreviewJobExecutor(
                leases, attempts, devices, inventory, DevicePlatformFactsRepository.NONE,
                ssh, pan, ref -> new PanCredentialMaterial("nexus-admin", "p".toCharArray()),
                loadGateRegistry(), store)
                .execute("job-3", 1L, DEVICE_ID, "192.0.2.2", 443, "cred-1");

        assertEquals(List.of("show"), configActions);
        assertEquals("DONE", store.lastState);
        assertEquals("PASS", store.lastVerdict);
    }

    private static final class InMemoryPreviewStore implements StaticRoutePreviewRepository {
        private final PreviewRow row;
        String lastState;
        String lastVerdict;
        String lastStopCode;
        String lastPreRouteSha;
        String lastPreChecksJson;
        String lastBeforePathJson;
        String lastCommandPlanJson;
        String lastInversePlanJson;

        InMemoryPreviewStore(PreviewRow row) {
            this.row = row;
        }

        @Override public Optional<PreviewRow> findByIdempotencyKey(String k) { return Optional.empty(); }
        @Override public void create(CreateSpec spec) { }
        @Override public boolean attachJob(String p, String j) { return true; }
        @Override public Optional<PreviewRow> findByJob(String jobId) { return Optional.of(row); }
        @Override public boolean markRunning(String jobId, long leaseEpoch) { return true; }
        @Override
        public boolean recordOutcome(
                String jobId,
                long leaseEpoch,
                String state,
                String verdict,
                String stopCode,
                String preRouteTableSha256,
                String preChecksJson,
                String beforePathJson,
                String commandPlanJson,
                String inversePlanJson) {
            this.lastState = state;
            this.lastVerdict = verdict;
            this.lastStopCode = stopCode;
            this.lastPreRouteSha = preRouteTableSha256;
            this.lastPreChecksJson = preChecksJson;
            this.lastBeforePathJson = beforePathJson;
            this.lastCommandPlanJson = commandPlanJson;
            this.lastInversePlanJson = inversePlanJson;
            return true;
        }
        @Override public boolean abandonByJob(String jobId, String stopCode) { return true; }
        @Override public void abandonById(String previewId, String stopCode) { }
        @Override public Optional<PreviewRow> find(String previewId) { return Optional.of(row); }
        @Override public List<PreviewRow> listRecent(int limit) { return List.of(row); }
    }
}
