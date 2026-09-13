package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.discovery.cp.Address;
import com.securityexpert.nexus.ui2.discovery.cp.CandidateKey;
import com.securityexpert.nexus.ui2.discovery.cp.ClassificationFlags;
import com.securityexpert.nexus.ui2.discovery.cp.ClusterReference;
import com.securityexpert.nexus.ui2.discovery.cp.ConnectionTableChannelState;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementApiFieldBinding;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementApiFieldBinding.Role;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumeration;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult;
import com.securityexpert.nexus.ui2.discovery.cp.ObjectType;
import com.securityexpert.nexus.ui2.discovery.cp.RawCandidateInput;
import com.securityexpert.nexus.ui2.discovery.cp.SessionDisconnectOutcome;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialResolver;

/**
 * T-1/T-2/T-4/T-5/T-6/T-7 and §7.4 CS-1..CS-6c: the {@link
 * ManagementPlaneEnumeration} port implemented over the existing {@link
 * DeviceTransport} port (contract §3, §7.4). One {@code connect()}, one
 * authenticated session used for the domain enumeration, every per-domain
 * object query and both connection-table observations, and one {@code
 * disconnect()} in a {@code finally}-equivalent path (T-1). Every command
 * this class can send comes from {@link ManagementShellCommands} (T-2/T-3);
 * every field name it reads comes from {@link ManagementApiFieldBinding}
 * (FB-2); the only host it ever passes to {@code connect()} is {@code
 * request.managementHost()} (T-4) -- no address parsed out of a response is
 * ever a connection target.
 */
public final class ManagementPlaneEnumerationAdapter implements ManagementPlaneEnumeration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(30);

    private final DeviceTransport transport;
    private final SshCredentialResolver credentialResolver;
    private final Sleeper sleeper;

    public ManagementPlaneEnumerationAdapter(DeviceTransport transport, SshCredentialResolver credentialResolver) {
        this(transport, credentialResolver, Sleeper.real());
    }

    ManagementPlaneEnumerationAdapter(DeviceTransport transport, SshCredentialResolver credentialResolver, Sleeper sleeper) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.credentialResolver = Objects.requireNonNull(credentialResolver, "credentialResolver");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** T-5/SB-16 (AC-5): credential resolution happens, and can refuse the run, before {@code connect()} is ever called. */
    @Override
    public ManagementPlaneEnumerationResult run(ManagementPlaneEnumerationRequest request) {
        SshCredentialMaterial credential = resolveCredentialOrNull(request.credentialRef());
        if (credential == null || isBlank(credential.username())) {
            return new ManagementPlaneEnumerationResult.Refused(
                    "credential resolution did not produce usable material for the supplied credentialRef");
        }

        // T-4: the ONLY ConnectionTarget this run ever builds -- request.managementHost(), nothing parsed later.
        ConnectionTarget target = new ConnectionTarget(request.managementHost(), request.managementHost(), request.managementPort());
        ConnectSpec spec = new ConnectSpec(request.credentialRef(), request.trustRuleRef(), Optional.empty());
        ConnectResult connectResult = transport.connect(target, spec, CONNECT_TIMEOUT);
        if (!(connectResult instanceof ConnectResult.Authenticated authenticated)) {
            return new ManagementPlaneEnumerationResult.Failed(
                    "management-plane session could not be authenticated", 0, SessionDisconnectOutcome.NOT_OPENED);
        }

        TransportSession session = authenticated.session();
        RequestCounter counter = new RequestCounter();
        List<RawCandidateInput> candidates = new ArrayList<>();
        Map<Address, ConnectionTableChannelState> channelStates = Map.of();
        RuntimeException caught = null;
        try {
            String topSessionId = login(session);
            List<OpaqueId> domains = enumerateDomains(session, topSessionId, counter);
            // T-6: sequential -- one domain, one object type at a time, no executor, no parallel stream.
            for (OpaqueId domainId : domains) {
                String domainSessionId = loginToDomain(session, topSessionId, domainId);
                for (ObjectType objectType : ObjectType.values()) {
                    candidates.addAll(queryObjects(session, domainSessionId, domainId, objectType, counter));
                }
            }
            List<ConnectionTableRow> firstObservation = readConnectionTableObservation(session, topSessionId);
            sleeper.sleep(request.channelObservationInterval());
            List<ConnectionTableRow> secondObservation = readConnectionTableObservation(session, topSessionId);
            channelStates = ConnectionTableReducer.reduce(
                    firstObservation, secondObservation, candidates, request.configuredChannelPort());
        } catch (RuntimeException e) {
            caught = e;
        }
        // T-1: disconnect is attempted whether or not the try block above failed.
        SessionDisconnectOutcome disconnectOutcome = closeSession(session);
        int managementPlaneRequestCount = 1 + counter.count();

        if (caught != null) {
            return new ManagementPlaneEnumerationResult.Failed(
                    "management-plane enumeration did not complete", managementPlaneRequestCount, disconnectOutcome);
        }
        return new ManagementPlaneEnumerationResult.Completed(
                attachChannelStates(candidates, channelStates), channelStates, managementPlaneRequestCount, disconnectOutcome);
    }

    private SshCredentialMaterial resolveCredentialOrNull(String credentialRef) {
        try {
            return credentialResolver.resolve(credentialRef);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private SessionDisconnectOutcome closeSession(TransportSession session) {
        try {
            transport.disconnect(session);
            return SessionDisconnectOutcome.CLOSED;
        } catch (RuntimeException e) {
            return SessionDisconnectOutcome.FAILED_TO_CLOSE;
        }
    }

    /** T-2: one login-shaped command; not counted by check 17's formula (session/context, not a data query). */
    private String login(TransportSession session) {
        Map<String, Object> response = asMap(MinimalJson.parse(execRead(session, ManagementShellCommands.login())));
        return requireString(response, Role.SESSION_IDENTIFIER);
    }

    /** T-2 context switch; not counted by check 17's formula. */
    private String loginToDomain(TransportSession session, String topSessionId, OpaqueId domainId) {
        String command = ManagementShellCommands.loginToDomain(topSessionId, domainId.value());
        Map<String, Object> response = asMap(MinimalJson.parse(execRead(session, command)));
        return requireString(response, Role.SESSION_IDENTIFIER);
    }

    /** T-2 domain enumeration, paginated: check 17 counts one request per page actually issued. */
    private List<OpaqueId> enumerateDomains(TransportSession session, String sessionId, RequestCounter counter) {
        List<OpaqueId> domains = new ArrayList<>();
        int offset = 0;
        while (true) {
            String command = ManagementShellCommands.showDomains(sessionId, offset);
            List<Object> page = asList(MinimalJson.parse(execRead(session, command)));
            counter.increment();
            for (Object item : page) {
                domains.add(OpaqueId.of(requireString(asMap(item), Role.DOMAIN_IDENTIFIER)));
            }
            if (page.size() < ManagementShellCommands.PAGE_SIZE) {
                break;
            }
            offset += page.size();
        }
        return domains;
    }

    /** T-2 per-domain, per-object-type query, paginated: check 17 counts one request per page actually issued. */
    private List<RawCandidateInput> queryObjects(TransportSession session, String domainSessionId, OpaqueId domainId,
            ObjectType objectType, RequestCounter counter) {
        List<RawCandidateInput> results = new ArrayList<>();
        int offset = 0;
        while (true) {
            String command = ManagementShellCommands.showObjects(objectType, domainSessionId, offset);
            List<Object> page = asList(MinimalJson.parse(execRead(session, command)));
            counter.increment();
            for (Object item : page) {
                results.add(parseCandidate(domainId, objectType, asMap(item)));
            }
            if (page.size() < ManagementShellCommands.PAGE_SIZE) {
                break;
            }
            offset += page.size();
        }
        return results;
    }

    /** §7.4: not counted by check 17's formula -- the connection table is a separate, unpaginated read. */
    private List<ConnectionTableRow> readConnectionTableObservation(TransportSession session, String sessionId) {
        List<Object> rows = asList(MinimalJson.parse(execRead(session, ManagementShellCommands.showConnectionTable(sessionId))));
        List<ConnectionTableRow> result = new ArrayList<>();
        for (Object item : rows) {
            Map<String, Object> row = asMap(item);
            Address address = optionalAddress(row, Role.CONNECTION_TABLE_ADDRESS);
            int port = requireInt(row, Role.CONNECTION_TABLE_PORT);
            boolean established = "established".equals(requireString(row, Role.CONNECTION_TABLE_STATE));
            result.add(new ConnectionTableRow(address, port, established));
        }
        return result;
    }

    /** §6: every field read through {@link ManagementApiFieldBinding} (FB-2) -- no field-name literal here. */
    private RawCandidateInput parseCandidate(OpaqueId domainId, ObjectType objectType, Map<String, Object> obj) {
        CandidateKey key = new CandidateKey(domainId, OpaqueId.of(requireString(obj, Role.STABLE_IDENTIFIER)));
        String displayName = requireString(obj, Role.DISPLAY_NAME);
        Address ownAddress = optionalAddress(obj, Role.OWN_ADDRESS);
        Address managementAddress = optionalAddress(obj, Role.MANAGEMENT_ADDRESS);
        ClassificationFlags flags = new ClassificationFlags(
                optionalBoolean(obj, Role.PRODUCT_FLAG),
                optionalBoolean(obj, Role.VIRT_HOST_FLAG),
                optionalBoolean(obj, Role.VIRT_SYSTEM_FLAG));
        Optional<ClusterReference> clusterReference = optionalClusterReference(obj);
        Optional<String> model = optionalString(obj, Role.MODEL);
        Optional<String> softwareVersion = optionalString(obj, Role.SOFTWARE_VERSION);
        Optional<String> managementPlaneConnectionState = optionalString(obj, Role.MANAGEMENT_PLANE_CONNECTION_STATE);
        // connectionTableChannelState is attached in a second pass (attachChannelStates), once both
        // connection-table observations exist -- CR-5-style: supplied by the run, not by this object.
        return new RawCandidateInput(key, objectType, flags, displayName, ownAddress, managementAddress,
                clusterReference, model, softwareVersion, managementPlaneConnectionState, Optional.empty());
    }

    private static List<RawCandidateInput> attachChannelStates(
            List<RawCandidateInput> candidates, Map<Address, ConnectionTableChannelState> states) {
        List<RawCandidateInput> result = new ArrayList<>(candidates.size());
        for (RawCandidateInput candidate : candidates) {
            Optional<ConnectionTableChannelState> state = candidate.managementAddress().isPresent()
                    ? Optional.ofNullable(states.get(candidate.managementAddress()))
                    : Optional.empty();
            result.add(new RawCandidateInput(candidate.key(), candidate.objectType(), candidate.flags(),
                    candidate.displayName(), candidate.ownAddress(), candidate.managementAddress(),
                    candidate.clusterReference(), candidate.model(), candidate.softwareVersion(),
                    candidate.managementPlaneConnectionState(), state));
        }
        return result;
    }

    /** T-7: the raw response lives only inside this call's return value on its way to being parsed, then discarded. */
    private String execRead(TransportSession session, String command) {
        ExecResult result = transport.exec(session, new ExecSpec(command), EXEC_TIMEOUT);
        if (result instanceof ExecResult.Completed completed && completed.exitStatus() == 0) {
            return completed.output();
        }
        throw new ManagementPlaneQueryFailedException();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new ManagementPlaneQueryFailedException();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new ManagementPlaneQueryFailedException();
    }

    private static String fieldName(Role role) {
        return ManagementApiFieldBinding.forRole(role).apiField()
                .orElseThrow(ManagementPlaneQueryFailedException::new);
    }

    private static Optional<String> optionalString(Map<String, Object> obj, Role role) {
        Object value = obj.get(fieldName(role));
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    private static String requireString(Map<String, Object> obj, Role role) {
        return optionalString(obj, role).orElseThrow(ManagementPlaneQueryFailedException::new);
    }

    private static int requireInt(Map<String, Object> obj, Role role) {
        Object value = obj.get(fieldName(role));
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            return Integer.parseInt(string);
        }
        throw new ManagementPlaneQueryFailedException();
    }

    private static Address optionalAddress(Map<String, Object> obj, Role role) {
        return optionalString(obj, role).map(Address::of).orElse(Address.absent());
    }

    private static boolean optionalBoolean(Map<String, Object> obj, Role role) {
        Object value = obj.get(fieldName(role));
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            return Boolean.parseBoolean(string);
        }
        return false;
    }

    private static Optional<ClusterReference> optionalClusterReference(Map<String, Object> obj) {
        Optional<String> identifier = optionalString(obj, Role.CLUSTER_REFERENCE_IDENTIFIER);
        Optional<String> displayName = optionalString(obj, Role.CLUSTER_REFERENCE_DISPLAY_NAME);
        if (identifier.isEmpty() && displayName.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ClusterReference(identifier.map(OpaqueId::of), displayName));
    }

    private static final class RequestCounter {
        private int count;

        void increment() {
            count++;
        }

        int count() {
            return count;
        }
    }
}
