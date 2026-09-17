package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
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
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult.ParseCounts;
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
 * DeviceTransport} port (contract §3, §7.4), aligned to the method the
 * Product Owner measured against a live multi-domain management server
 * (record {@code docs/design/
 * CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md}, DRAFT --
 * evidence, not authority; the contract above it is what this class
 * implements). One {@code connect()}, one shell session used for the domain
 * enumeration, every per-domain per-object-type query and both
 * connection-table observations, and one {@code disconnect()} in a {@code
 * finally}-equivalent path (T-1). Every command this class can send comes
 * from {@link ManagementShellCommands} (T-2/T-3); every field name it reads
 * comes from {@link ManagementApiFieldBinding} (FB-2); the only host it ever
 * passes to {@code connect()} is {@code request.managementHost()} (T-4) --
 * no address parsed out of a response is ever a connection target.
 *
 * <p>record §3 row 2 / §10: the domain context switch and its query are
 * always built and sent as ONE {@link ExecSpec} command string -- {@link
 * ManagementShellCommands#contextSwitchAndObjectQuery} is the only place
 * either half is ever produced, so a context switch alone or a query alone
 * is structurally unreachable, not merely untested.</p>
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
        ConnectResult connectResult;
        try {
            connectResult = transport.connect(target, spec, CONNECT_TIMEOUT);
        } catch (RuntimeException e) {
            return new ManagementPlaneEnumerationResult.Failed("NOT_EVALUABLE", 0, SessionDisconnectOutcome.NOT_OPENED);
        }
        if (connectResult instanceof ConnectResult.AuthenticationFailed failure
                && "NOT_EVALUABLE".equals(failure.reason())) {
            return new ManagementPlaneEnumerationResult.Failed("NOT_EVALUABLE", 0, SessionDisconnectOutcome.NOT_OPENED);
        }
        if (!(connectResult instanceof ConnectResult.Authenticated authenticated)) {
            return new ManagementPlaneEnumerationResult.Failed(
                    connectFailure(connectResult), 0, SessionDisconnectOutcome.NOT_OPENED);
        }

        TransportSession session = authenticated.session();
        RequestCounter counter = new RequestCounter();
        List<RawCandidateInput> candidates = new ArrayList<>();
        Map<ObjectType, ParseCounts> parseCounts = new EnumMap<>(ObjectType.class);
        Map<Address, ConnectionTableChannelState> channelStates = Map.of();
        RuntimeException caught = null;
        try {
            List<String> domains = enumerateDomains(session, counter);
            // T-6: sequential -- one domain, one object type at a time, no executor, no parallel stream.
            for (String domain : domains) {
                for (ObjectType objectType : ObjectType.values()) {
                    candidates.addAll(queryObjects(session, domain, objectType, counter, parseCounts));
                }
            }
            List<ConnectionTableRow> firstObservation = readConnectionTableObservation(session);
            sleeper.sleep(request.channelObservationInterval());
            List<ConnectionTableRow> secondObservation = readConnectionTableObservation(session);
            channelStates = ConnectionTableReducer.reduce(
                    firstObservation, secondObservation, candidates, request.configuredChannelPort());
        } catch (RuntimeException e) {
            caught = e;
        }
        // T-1: disconnect is attempted whether or not the try block above failed.
        SessionDisconnectOutcome disconnectOutcome = closeSession(session);
        // record §10: one session + one domain enumeration + one object query per domain per object type.
        int managementPlaneRequestCount = 1 + counter.count();

        if (caught != null) {
            String failureReason = caught.getMessage();
            return new ManagementPlaneEnumerationResult.Failed(
                    failureReason == null ? caught.getClass().getSimpleName() : failureReason,
                    managementPlaneRequestCount, disconnectOutcome);
        }
        return new ManagementPlaneEnumerationResult.Completed(
                attachChannelStates(candidates, channelStates), channelStates, managementPlaneRequestCount,
                disconnectOutcome, parseCounts);
    }

    static ManagementPlaneEnumerationResult.FailureClass connectFailure(ConnectResult result) {
        if (result instanceof ConnectResult.HostKeyRejected rejected) {
            return "TRUST_ENTRY_MISSING".equals(rejected.reason())
                    ? ManagementPlaneEnumerationResult.FailureClass.TRUST_ENTRY_MISSING
                    : ManagementPlaneEnumerationResult.FailureClass.TRUST_MISMATCH;
        }
        return result instanceof ConnectResult.AuthenticationFailed
                ? ManagementPlaneEnumerationResult.FailureClass.AUTH_FAILED
                : ManagementPlaneEnumerationResult.FailureClass.CONNECT_TIMEOUT;
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

    /** record §4 item 1: one call, one line per domain (record §10) -- never paginated. Counted once by check 17's formula. */
    private List<String> enumerateDomains(TransportSession session, RequestCounter counter) {
        String response = execRead(session, ManagementShellCommands.domainList());
        counter.increment();
        List<String> domains = new ArrayList<>();
        for (String line : response.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                domains.add(trimmed);
            }
        }
        return domains;
    }

    /**
     * record §4 item 5 / §10 row 4: one call per domain per object type,
     * context switch and query on the same shell line, never paginated. An
     * object without a stable identifier (AdminInfo/chkpf_uid) is counted
     * but never turned into a candidate -- {@link CandidateKey} has no
     * meaningful value to hold for it.
     */
    private List<RawCandidateInput> queryObjects(TransportSession session, String domain, ObjectType objectType,
            RequestCounter counter, Map<ObjectType, ParseCounts> parseCounts) {
        String command = ManagementShellCommands.contextSwitchAndObjectQuery(domain, objectType);
        String response = execRead(session, command);
        counter.increment();
        List<Map<String, Object>> objects = CpObjectDumpParser.parseObjects(response);

        List<RawCandidateInput> results = new ArrayList<>();
        int missingStableIdentifier = 0;
        OpaqueId domainId = OpaqueId.of(domain);
        for (Map<String, Object> object : objects) {
            Optional<String> stableIdentifier = optionalString(object, Role.STABLE_IDENTIFIER);
            if (stableIdentifier.isEmpty() || stableIdentifier.get().isBlank()) {
                missingStableIdentifier++;
                continue;
            }
            results.add(parseCandidate(domainId, objectType, OpaqueId.of(stableIdentifier.get()), object));
        }
        ParseCounts previous = parseCounts.getOrDefault(objectType, new ParseCounts(0, 0));
        parseCounts.put(objectType, new ParseCounts(
                previous.parsed() + objects.size(), previous.missingStableIdentifier() + missingStableIdentifier));
        return results;
    }

    /** §7.4: not counted by check 17's formula -- the connection table is a separate, unpaginated read. */
    private List<ConnectionTableRow> readConnectionTableObservation(TransportSession session) {
        String response = execRead(session, ManagementShellCommands.connectionTable());
        return NetstatConnectionTableParser.parse(response);
    }

    /** §6: every field read through {@link ManagementApiFieldBinding} (FB-2) -- no field-name literal here. */
    private RawCandidateInput parseCandidate(OpaqueId domainId, ObjectType objectType, OpaqueId stableIdentifier,
            Map<String, Object> obj) {
        CandidateKey key = new CandidateKey(domainId, stableIdentifier);
        // record §5: present on every object -- a run that cannot find it fails closed rather than guessing "".
        String displayName = optionalString(obj, Role.DISPLAY_NAME).orElseThrow(ManagementPlaneQueryFailedException::new);
        Address ownAddress = optionalAddress(obj, Role.OWN_ADDRESS);
        Address managementAddress = optionalAddress(obj, Role.MANAGEMENT_ADDRESS);
        ClassificationFlags flags = new ClassificationFlags(
                optionalBoolean(obj, Role.PRODUCT_FLAG),
                optionalBoolean(obj, Role.VIRT_HOST_FLAG, objectType),
                optionalBoolean(obj, Role.VIRT_SYSTEM_FLAG, objectType));
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
        if (result instanceof ExecResult.Completed completed) {
            throw new IllegalStateException("management command exited with status " + completed.exitStatus());
        }
        throw new IllegalStateException("management command failed: " + result);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * A dotted role path (block then leaf, e.g. {@link Role#STABLE_IDENTIFIER}'s
     * bound path) names a nested block then a leaf inside it; an undotted
     * path names a top-level leaf. Either way a present-but-empty value
     * stays present-but-empty (HR-2); an absent key -- missing at any level
     * -- is simply not returned.
     */
    private static Optional<String> lookupPath(Map<String, Object> obj, String path) {
        int slash = path.indexOf('/');
        Map<String, Object> scope = obj;
        String leaf = path;
        if (slash >= 0) {
            Object nested = obj.get(path.substring(0, slash));
            if (!(nested instanceof Map<?, ?> map)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> nestedMap = (Map<String, Object>) map;
            scope = nestedMap;
            leaf = path.substring(slash + 1);
        }
        Object value = scope.get(leaf);
        return value instanceof String string ? Optional.of(string) : Optional.empty();
    }

    private static Optional<String> optionalString(Map<String, Object> obj, Role role) {
        return lookupPath(obj, ManagementApiFieldBinding.forRole(role).apiField().orElseThrow(ManagementPlaneQueryFailedException::new));
    }

    private static Address optionalAddress(Map<String, Object> obj, Role role) {
        return optionalString(obj, role).map(Address::of).orElse(Address.absent());
    }

    private static boolean optionalBoolean(Map<String, Object> obj, Role role) {
        return optionalString(obj, role).map(Boolean::parseBoolean).orElse(false);
    }

    private static boolean optionalBoolean(Map<String, Object> obj, Role role, ObjectType objectType) {
        String path = ManagementApiFieldBinding.forRole(role, objectType).apiField()
                .orElseThrow(ManagementPlaneQueryFailedException::new);
        return lookupPath(obj, path).map(Boolean::parseBoolean).orElse(false);
    }

    private static Optional<ClusterReference> optionalClusterReference(Map<String, Object> obj) {
        Optional<String> identifier = optionalString(obj, Role.CLUSTER_REFERENCE_IDENTIFIER).filter(s -> !s.isBlank());
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
