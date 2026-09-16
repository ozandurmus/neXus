package com.securityexpert.nexus.ui2.worker.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.cp.Address;
import com.securityexpert.nexus.ui2.discovery.cp.ConnectionTableChannelState;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult;
import com.securityexpert.nexus.ui2.discovery.cp.RawCandidateInput;
import com.securityexpert.nexus.ui2.discovery.cp.SessionDisconnectOutcome;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialResolver;

/**
 * AC-1, AC-2 (T-4, the decisive test lives in {@link SameShellContextAndQueryTest}),
 * AC-5, AC-7's check-17 readiness and T-7's no-raw-retention property,
 * exercised through a full fixture run with {@link FakeDeviceTransport} --
 * never a real socket, never a real management server.
 */
class ManagementPlaneEnumerationAdapterTest {

    private static final String HOST = "fixture-management-host";
    private static final int PORT = 2222;
    private static final String CREDENTIAL_REF = "fixture-credential-ref";
    private static final String TRUST_RULE_REF = "fixture-trust-rule-ref";

    private static final String DOMAIN_A_GATEWAY_QUERY =
            "mdsenv '" + WorkerFixtures.DOMAIN_A_UID + "'&& cpmiquerybin object \"\" network_objects \"type='gateway'\"";
    private static final String DOMAIN_A_CLUSTER_QUERY =
            "mdsenv '" + WorkerFixtures.DOMAIN_A_UID + "'&& cpmiquerybin object \"\" network_objects \"type='gateway_cluster'\"";
    private static final String DOMAIN_A_MEMBER_QUERY =
            "mdsenv '" + WorkerFixtures.DOMAIN_A_UID + "'&& cpmiquerybin object \"\" network_objects \"type='cluster_member'\"";
    private static final String DOMAIN_B_GATEWAY_QUERY =
            "mdsenv '" + WorkerFixtures.DOMAIN_B_UID + "'&& cpmiquerybin object \"\" network_objects \"type='gateway'\"";
    private static final String DOMAIN_B_CLUSTER_QUERY =
            "mdsenv '" + WorkerFixtures.DOMAIN_B_UID + "'&& cpmiquerybin object \"\" network_objects \"type='gateway_cluster'\"";
    private static final String DOMAIN_B_MEMBER_QUERY =
            "mdsenv '" + WorkerFixtures.DOMAIN_B_UID + "'&& cpmiquerybin object \"\" network_objects \"type='cluster_member'\"";

    private static final SshCredentialResolver ALWAYS_RESOLVES =
            ref -> new SshCredentialMaterial("fixture-user", "fixture-password".toCharArray(), null);

    private ManagementPlaneEnumerationRequest request() {
        return new ManagementPlaneEnumerationRequest(HOST, PORT, CREDENTIAL_REF, TRUST_RULE_REF, Duration.ZERO, Optional.empty());
    }

    @Test
    void connectFailuresMapToClosedValuesWithoutExceptionDisclosure() {
        var missing = new com.securityexpert.nexus.ui2.jobs.transport.ConnectResult.HostKeyRejected("TRUST_ENTRY_MISSING");
        var mismatch = new com.securityexpert.nexus.ui2.jobs.transport.ConnectResult.HostKeyRejected("raw-sensitive-error");
        var auth = new com.securityexpert.nexus.ui2.jobs.transport.ConnectResult.AuthenticationFailed("raw-sensitive-error");
        var timeout = new com.securityexpert.nexus.ui2.jobs.transport.ConnectResult.TimedOut();
        assertEquals(ManagementPlaneEnumerationResult.FailureClass.TRUST_ENTRY_MISSING, ManagementPlaneEnumerationAdapter.connectFailure(missing));
        assertEquals(ManagementPlaneEnumerationResult.FailureClass.TRUST_MISMATCH, ManagementPlaneEnumerationAdapter.connectFailure(mismatch));
        assertEquals(ManagementPlaneEnumerationResult.FailureClass.AUTH_FAILED, ManagementPlaneEnumerationAdapter.connectFailure(auth));
        assertEquals(ManagementPlaneEnumerationResult.FailureClass.CONNECT_TIMEOUT, ManagementPlaneEnumerationAdapter.connectFailure(timeout));
        for (var failure : ManagementPlaneEnumerationResult.FailureClass.values()) {
            assertTrue(DiscoveryRunReport.render(new ManagementPlaneEnumerationResult.Failed(failure, 0,
                    SessionDisconnectOutcome.NOT_OPENED)).contains(failure.name()));
        }
        assertFalse(DiscoveryRunReport.render(new ManagementPlaneEnumerationResult.Failed("raw-sensitive-error", 0,
                SessionDisconnectOutcome.NOT_OPENED)).contains("raw-sensitive-error"));
    }

    /** T-4 -- across a full run, exactly one distinct ConnectionTarget host is ever dialed. */
    @Test
    void acrossAFullRunExactlyOneDistinctHostIsEverDialed() {
        FakeDeviceTransport transport = new FakeDeviceTransport(happyPathHandler());
        ManagementPlaneEnumerationAdapter adapter =
                new ManagementPlaneEnumerationAdapter(transport, ALWAYS_RESOLVES, d -> { });

        ManagementPlaneEnumerationResult result = adapter.run(request());

        assertInstanceOf(ManagementPlaneEnumerationResult.Completed.class, result);
        Set<String> distinctHosts = new HashSet<>();
        for (ConnectionTarget target : transport.connectTargets()) {
            distinctHosts.add(target.host());
        }
        assertEquals(1, distinctHosts.size(), "every ConnectionTarget host dialed: " + distinctHosts);
        assertEquals(Set.of(HOST), distinctHosts);
    }

    /** AC-1/AC-2: exactly one connect(), one disconnect(), and check 17's formula for a two-domain fixture. */
    @Test
    void fullRunConnectsOnceDisconnectsOnceAndCountsRequestsPerCheck17() {
        FakeDeviceTransport transport = new FakeDeviceTransport(happyPathHandler());
        ManagementPlaneEnumerationAdapter adapter =
                new ManagementPlaneEnumerationAdapter(transport, ALWAYS_RESOLVES, d -> { });

        ManagementPlaneEnumerationResult result = adapter.run(request());

        assertEquals(1, transport.connectCount());
        assertEquals(1, transport.disconnectCount());
        ManagementPlaneEnumerationResult.Completed completed = assertInstanceOf(ManagementPlaneEnumerationResult.Completed.class, result);
        // check 17: one session + one domain enumeration + (2 domains * 3 object types * 1 query each) = 8.
        assertEquals(8, completed.managementPlaneRequestCount());
        assertEquals(SessionDisconnectOutcome.CLOSED, completed.disconnectOutcome());
        assertEquals(6, completed.candidates().size());
        // the two connection-table reads are not counted -- exactly two "netstat -an" commands were issued regardless.
        assertEquals(2, transport.commandsIssued().stream().filter("netstat -an"::equals).count());
    }

    /** AC-1: disconnect() is still called, and reported, when a per-domain query throws mid-run. */
    @Test
    void disconnectIsStillCalledWhenAPerDomainQueryFails() {
        Function<String, ExecResult> handler = command -> {
            if (command.equals(DOMAIN_B_MEMBER_QUERY)) {
                return new ExecResult.ChannelFailed("fixture-forced-channel-failure");
            }
            return happyPathHandler().apply(command);
        };
        FakeDeviceTransport transport = new FakeDeviceTransport(handler);
        ManagementPlaneEnumerationAdapter adapter =
                new ManagementPlaneEnumerationAdapter(transport, ALWAYS_RESOLVES, d -> { });

        ManagementPlaneEnumerationResult result = adapter.run(request());

        assertEquals(1, transport.connectCount());
        assertEquals(1, transport.disconnectCount());
        ManagementPlaneEnumerationResult.Failed failed = assertInstanceOf(ManagementPlaneEnumerationResult.Failed.class, result);
        assertEquals(SessionDisconnectOutcome.CLOSED, failed.disconnectOutcome());
        // 1 session + 1 domain enumeration + domain A's 3 + domain B's gateway and cluster queries (2) = 7;
        // the failing member query is never counted because it never returned.
        assertEquals(7, failed.managementPlaneRequestCount());
        assertFalse(failed.reason().contains("fixture-forced-channel-failure"),
                "the transport's own failure reason must never reach the result");
    }

    /** T-1: a disconnect() failure is reported explicitly rather than swallowed. */
    @Test
    void aDisconnectFailureIsReportedExplicitly() {
        FakeDeviceTransport transport = new FakeDeviceTransport(happyPathHandler());
        transport.disconnectThrows(true);
        ManagementPlaneEnumerationAdapter adapter =
                new ManagementPlaneEnumerationAdapter(transport, ALWAYS_RESOLVES, d -> { });

        ManagementPlaneEnumerationResult result = adapter.run(request());

        ManagementPlaneEnumerationResult.Completed completed = assertInstanceOf(ManagementPlaneEnumerationResult.Completed.class, result);
        assertEquals(SessionDisconnectOutcome.FAILED_TO_CLOSE, completed.disconnectOutcome());
    }

    /** AC-5: a credentialRef the resolver cannot resolve produces a typed refusal with zero connect() calls. */
    @Test
    void unresolvableCredentialRefusesBeforeAnyConnectAttempt() {
        SshCredentialResolver throwing = ref -> {
            throw new IllegalStateException("fixture-secret-backend-unreachable: user=fixture-admin host=" + HOST);
        };
        FakeDeviceTransport transport = new FakeDeviceTransport(happyPathHandler());
        ManagementPlaneEnumerationAdapter adapter = new ManagementPlaneEnumerationAdapter(transport, throwing, d -> { });

        ManagementPlaneEnumerationResult result = adapter.run(request());

        assertEquals(0, transport.connectCount());
        ManagementPlaneEnumerationResult.Refused refused = assertInstanceOf(ManagementPlaneEnumerationResult.Refused.class, result);
        assertFalse(refused.reason().contains("fixture-admin"));
        assertFalse(refused.reason().contains("fixture-secret-backend-unreachable"));
    }

    /** AC-5: credential material with no usable username is refused the same way. */
    @Test
    void unusableCredentialMaterialRefusesBeforeAnyConnectAttempt() {
        SshCredentialResolver blankUsername = ref -> new SshCredentialMaterial("", null, null);
        FakeDeviceTransport transport = new FakeDeviceTransport(happyPathHandler());
        ManagementPlaneEnumerationAdapter adapter = new ManagementPlaneEnumerationAdapter(transport, blankUsername, d -> { });

        ManagementPlaneEnumerationResult result = adapter.run(request());

        assertEquals(0, transport.connectCount());
        assertInstanceOf(ManagementPlaneEnumerationResult.Refused.class, result);
    }

    /** CS-3/CS-6a exercised end to end: agreeing, disagreeing, absent and unmatched addresses. */
    @Test
    void connectionTableStatesAreAttachedPerCandidateManagementAddress() {
        FakeDeviceTransport transport = new FakeDeviceTransport(happyPathHandler());
        ManagementPlaneEnumerationAdapter adapter =
                new ManagementPlaneEnumerationAdapter(transport, ALWAYS_RESOLVES, d -> { });

        ManagementPlaneEnumerationResult.Completed result =
                assertInstanceOf(ManagementPlaneEnumerationResult.Completed.class, adapter.run(request()));

        assertEquals(ConnectionTableChannelState.ESTABLISHED,
                result.connectionTableStates().get(Address.of(WorkerFixtures.DOMAIN_A_GATEWAY_MGMT_ADDRESS)));
        assertEquals(ConnectionTableChannelState.FAILING_TO_COMPLETE,
                result.connectionTableStates().get(Address.of(WorkerFixtures.DOMAIN_A_MEMBER_MGMT_ADDRESS)));
        assertEquals(ConnectionTableChannelState.IN_TRANSITION,
                result.connectionTableStates().get(Address.of(WorkerFixtures.DOMAIN_B_GATEWAY_MGMT_ADDRESS)));
        assertEquals(ConnectionTableChannelState.ABSENT,
                result.connectionTableStates().get(Address.of(WorkerFixtures.DOMAIN_B_MEMBER_MGMT_ADDRESS)));
        assertFalse(result.connectionTableStates().containsKey(Address.of(WorkerFixtures.UNMATCHED_TABLE_ADDRESS)),
                "CS-6a: an address with no corresponding object is not reported");

        for (RawCandidateInput candidate : result.candidates()) {
            if (candidate.managementAddress().equals(Address.of(WorkerFixtures.DOMAIN_A_GATEWAY_MGMT_ADDRESS))) {
                assertEquals(Optional.of(ConnectionTableChannelState.ESTABLISHED), candidate.connectionTableChannelState());
            }
        }
    }

    /** AC-7: per object type, how many objects were parsed and how many lacked the stable identifier -- counts only. */
    @Test
    void parseCountsAreReportedPerObjectTypeWithNoneMissingInTheHappyPath() {
        FakeDeviceTransport transport = new FakeDeviceTransport(happyPathHandler());
        ManagementPlaneEnumerationAdapter adapter =
                new ManagementPlaneEnumerationAdapter(transport, ALWAYS_RESOLVES, d -> { });

        ManagementPlaneEnumerationResult.Completed result =
                assertInstanceOf(ManagementPlaneEnumerationResult.Completed.class, adapter.run(request()));

        result.parseCountsByObjectType().values().forEach(counts -> {
            assertEquals(0, counts.missingStableIdentifier());
        });
        int totalParsed = result.parseCountsByObjectType().values().stream()
                .mapToInt(ManagementPlaneEnumerationResult.ParseCounts::parsed).sum();
        assertEquals(6, totalParsed);
    }

    /** T-7: no raw response text -- structural markers only a raw blob or a command string would carry -- is reachable from the result. */
    @Test
    void noRawResponseTextIsReachableFromTheResult() {
        FakeDeviceTransport transport = new FakeDeviceTransport(happyPathHandler());
        ManagementPlaneEnumerationAdapter adapter =
                new ManagementPlaneEnumerationAdapter(transport, ALWAYS_RESOLVES, d -> { });

        ManagementPlaneEnumerationResult result = adapter.run(request());
        String resultAsText = String.valueOf(result);

        for (String rawMarker : List.of("cpmiquerybin", "mdsenv", "AdminInfo", "chkpf_uid", "cluster_object",
                "vsx_netobj", "vs_netobj", "appliance_type", "svn_version_name")) {
            assertFalse(resultAsText.contains(rawMarker), "raw field/command literal \"" + rawMarker + "\" leaked into the result");
        }
    }

    static Function<String, ExecResult> happyPathHandler() {
        int[] tableObservationCount = {0};
        return command -> {
            if (command.equals(ManagementShellCommands.domainList())) {
                return new ExecResult.Completed(WorkerFixtures.twoDomainsResponse(), 0);
            }
            if (command.equals(DOMAIN_A_GATEWAY_QUERY)) {
                return new ExecResult.Completed(WorkerFixtures.objectDump(
                        WorkerFixtures.gatewayObject("gw-a-1", "fixture-gw-a-1", WorkerFixtures.DOMAIN_A_GATEWAY_MGMT_ADDRESS)), 0);
            }
            if (command.equals(DOMAIN_A_CLUSTER_QUERY)) {
                return new ExecResult.Completed(WorkerFixtures.objectDump(
                        WorkerFixtures.clusterObject("cl-a-1", "fixture-cluster-a-1")), 0);
            }
            if (command.equals(DOMAIN_A_MEMBER_QUERY)) {
                return new ExecResult.Completed(WorkerFixtures.objectDump(WorkerFixtures.memberObject(
                        "mem-a-1", "fixture-member-a-1", WorkerFixtures.DOMAIN_A_MEMBER_MGMT_ADDRESS,
                        "cl-a-1", "fixture-cluster-a-1")), 0);
            }
            if (command.equals(DOMAIN_B_GATEWAY_QUERY)) {
                return new ExecResult.Completed(WorkerFixtures.objectDump(
                        WorkerFixtures.gatewayObject("gw-b-1", "fixture-gw-b-1", WorkerFixtures.DOMAIN_B_GATEWAY_MGMT_ADDRESS)), 0);
            }
            if (command.equals(DOMAIN_B_CLUSTER_QUERY)) {
                return new ExecResult.Completed(WorkerFixtures.objectDump(
                        WorkerFixtures.clusterObject("cl-b-1", "fixture-cluster-b-1")), 0);
            }
            if (command.equals(DOMAIN_B_MEMBER_QUERY)) {
                return new ExecResult.Completed(WorkerFixtures.objectDump(WorkerFixtures.memberObject(
                        "mem-b-1", "fixture-member-b-1", WorkerFixtures.DOMAIN_B_MEMBER_MGMT_ADDRESS,
                        "cl-b-1", "fixture-cluster-b-1")), 0);
            }
            if (command.equals(ManagementShellCommands.connectionTable())) {
                tableObservationCount[0]++;
                if (tableObservationCount[0] == 1) {
                    return new ExecResult.Completed(WorkerFixtures.connectionTable(
                            WorkerFixtures.netstatRow(WorkerFixtures.DOMAIN_A_GATEWAY_MGMT_ADDRESS, 18190, true),
                            WorkerFixtures.netstatRow(WorkerFixtures.DOMAIN_A_MEMBER_MGMT_ADDRESS, 18190, false),
                            WorkerFixtures.netstatRow(WorkerFixtures.DOMAIN_B_GATEWAY_MGMT_ADDRESS, 18190, true),
                            WorkerFixtures.netstatRow(WorkerFixtures.UNMATCHED_TABLE_ADDRESS, 18190, true)), 0);
                }
                return new ExecResult.Completed(WorkerFixtures.connectionTable(
                        WorkerFixtures.netstatRow(WorkerFixtures.DOMAIN_A_GATEWAY_MGMT_ADDRESS, 18190, true),
                        WorkerFixtures.netstatRow(WorkerFixtures.DOMAIN_A_MEMBER_MGMT_ADDRESS, 18190, false),
                        WorkerFixtures.netstatRow(WorkerFixtures.DOMAIN_B_GATEWAY_MGMT_ADDRESS, 18190, false),
                        WorkerFixtures.netstatRow(WorkerFixtures.UNMATCHED_TABLE_ADDRESS, 18190, true)), 0);
            }
            throw new AssertionError("unscripted fixture command: " + command);
        };
    }
}
