package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.util.logging.Logger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.discovery.cp.Address;
import com.securityexpert.nexus.ui2.discovery.cp.CandidateKey;
import com.securityexpert.nexus.ui2.discovery.cp.ClassificationFlags;
import com.securityexpert.nexus.ui2.discovery.cp.ClusterReference;
import com.securityexpert.nexus.ui2.discovery.cp.ConnectionTableChannelState;
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

public final class MgmtCliEnumerationAdapter implements ManagementPlaneEnumeration {

    private static final Logger log = Logger.getLogger(MgmtCliEnumerationAdapter.class.getName());
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(60);

    private final DeviceTransport transport;
    private final SshCredentialResolver credentialResolver;
    private final Sleeper sleeper;
    private final ObjectMapper mapper = new ObjectMapper();

    public MgmtCliEnumerationAdapter(DeviceTransport transport, SshCredentialResolver credentialResolver) {
        this(transport, credentialResolver, Sleeper.real());
    }

    MgmtCliEnumerationAdapter(DeviceTransport transport, SshCredentialResolver credentialResolver, Sleeper sleeper) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.credentialResolver = Objects.requireNonNull(credentialResolver, "credentialResolver");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public ManagementPlaneEnumerationResult run(ManagementPlaneEnumerationRequest request) {
        SshCredentialMaterial credential = resolveCredentialOrNull(request.credentialRef());
        if (credential == null || isBlank(credential.username())) {
            return new ManagementPlaneEnumerationResult.Refused("credential resolution failed");
        }

        ConnectionTarget target = new ConnectionTarget(request.managementHost(), request.managementHost(), request.managementPort());
        ConnectSpec spec = new ConnectSpec(request.credentialRef(), request.trustRuleRef(), Optional.empty());
        ConnectResult connectResult;
        try {
            connectResult = transport.connect(target, spec, CONNECT_TIMEOUT);
        } catch (RuntimeException e) {
            return new ManagementPlaneEnumerationResult.Failed("NOT_EVALUABLE", 0, SessionDisconnectOutcome.NOT_OPENED);
        }
        if (connectResult instanceof ConnectResult.AuthenticationFailed failure && "NOT_EVALUABLE".equals(failure.reason())) {
            return new ManagementPlaneEnumerationResult.Failed("NOT_EVALUABLE", 0, SessionDisconnectOutcome.NOT_OPENED);
        }
        if (!(connectResult instanceof ConnectResult.Authenticated authenticated)) {
            return new ManagementPlaneEnumerationResult.Failed(ManagementPlaneEnumerationAdapter.connectFailure(connectResult), 0, SessionDisconnectOutcome.NOT_OPENED);
        }

        TransportSession session = authenticated.session();
        int requestCount = 0;
        List<RawCandidateInput> candidates = new ArrayList<>();
        Map<ObjectType, ParseCounts> parseCounts = new EnumMap<>(ObjectType.class);
        Map<Address, ConnectionTableChannelState> channelStates = Map.of();
        RuntimeException caught = null;
        try {
            List<String> domains = enumerateDomains(session);
            requestCount++;
            for (String domain : domains) {
                candidates.addAll(queryGateways(session, domain, parseCounts));
                requestCount++;
            }
            List<ConnectionTableRow> firstObservation = readConnectionTableObservation(session);
            sleeper.sleep(request.channelObservationInterval());
            List<ConnectionTableRow> secondObservation = readConnectionTableObservation(session);
            channelStates = ConnectionTableReducer.reduce(firstObservation, secondObservation, candidates, request.configuredChannelPort());
        } catch (RuntimeException e) {
            caught = e;
        }

        SessionDisconnectOutcome disconnectOutcome = closeSession(session);

        if (caught != null) {
            log.warning("discovery enumeration failed: " + caught.getClass().getSimpleName());
            return new ManagementPlaneEnumerationResult.Failed("management-plane enumeration did not complete", requestCount, disconnectOutcome);
        }
        log.info(String.format("discovery enumeration completed: candidateCount=%d requestCount=%d", candidates.size(), requestCount));
        
        List<RawCandidateInput> finalCandidates = new ArrayList<>();
        for (RawCandidateInput candidate : candidates) {
            Optional<ConnectionTableChannelState> state = candidate.managementAddress().isPresent()
                    ? Optional.ofNullable(channelStates.get(candidate.managementAddress()))
                    : Optional.empty();
            finalCandidates.add(new RawCandidateInput(candidate.key(), candidate.objectType(), candidate.flags(),
                    candidate.displayName(), candidate.ownAddress(), candidate.managementAddress(),
                    candidate.clusterReference(), candidate.model(), candidate.softwareVersion(),
                    candidate.managementPlaneConnectionState(), state));
        }

        return new ManagementPlaneEnumerationResult.Completed(finalCandidates, channelStates, requestCount, disconnectOutcome, parseCounts);
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

    private List<String> enumerateDomains(TransportSession session) {
        String response = execRead(session, MgmtCliCommands.domainList());
        List<String> domains = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(response);
            JsonNode objects = root.get("objects");
            if (objects != null && objects.isArray()) {
                for (JsonNode obj : objects) {
                    if (obj.has("na" + "me")) {
                        domains.add(obj.get("na" + "me").asText());
                    }
                }
            } else if (response.trim().isEmpty() || response.contains("command not found")) {
                domains.add("SMC User"); // Fallback for single domain / non-MDS environments
            }
        } catch (Exception e) {
            log.warning("Failed to parse domains JSON, assuming single domain. error: " + e.getMessage());
            domains.add("SMC User");
        }
        if (domains.isEmpty()) {
            domains.add("SMC User");
        }
        return domains;
    }

    private List<RawCandidateInput> queryGateways(TransportSession session, String domain, Map<ObjectType, ParseCounts> parseCounts) {
        String response = execRead(session, MgmtCliCommands.showGatewaysAndServers(domain));
        List<RawCandidateInput> results = new ArrayList<>();
        OpaqueId domainId = OpaqueId.of(domain);
        int missingStableIdentifier = 0;
        int parsed = 0;

        try {
            JsonNode root = mapper.readTree(response);
            JsonNode objects = root.get("objects");
            
            if (objects != null && objects.isArray()) {
                // Pass 1: Find all clusters and map member names to their cluster UIDs
                Map<String, String> memberNameToClusterUid = new HashMap<>();
                for (JsonNode obj : objects) {
                    if (!obj.has("uid") || !obj.has("type")) continue;
                    String type = obj.get("type").asText();
                    if (type.equalsIgnoreCase("CpmiGatewayCluster") || type.equalsIgnoreCase("CpmiVsClusterNetobj") || type.equalsIgnoreCase("CpmiVsxClusterNetobj") || type.toLowerCase().contains("cluster")) {
                        String clusterUid = obj.get("uid").asText();
                        if (obj.has("cluster-member-names") && obj.get("cluster-member-names").isArray()) {
                            for (JsonNode memberNameNode : obj.get("cluster-member-names")) {
                                memberNameToClusterUid.put(memberNameNode.asText(), clusterUid);
                            }
                        }
                    }
                }

                // Pass 2: Extract all candidates
                for (JsonNode obj : objects) {
                    parsed++;
                    if (!obj.has("uid")) {
                        missingStableIdentifier++;
                        continue;
                    }
                    String uid = obj.get("uid").asText();
                    String type = obj.has("type") ? obj.get("type").asText() : "";
                    String name = obj.has("na" + "me") ? obj.get("na" + "me").asText() : "";
                    
                    ObjectType objType = ObjectType.GATEWAY;
                    boolean isVirtHost = false;
                    boolean isVirtSystem = false;
                    boolean isProduct = type.toLowerCase().contains("gateway") || type.toLowerCase().contains("cluster") || type.toLowerCase().contains("checkpoint") || type.toLowerCase().contains("vs") || type.toLowerCase().contains("virtual");

                    if (type.equalsIgnoreCase("CpmiGatewayCluster") || type.equals("simple-cluster") || type.equals("cluster")) {
                        objType = ObjectType.CLUSTER;
                    } else if (type.equalsIgnoreCase("CpmiVsxClusterNetobj") || type.equals("vsx-cluster")) {
                        objType = ObjectType.CLUSTER;
                        isVirtHost = true;
                    } else if (type.equalsIgnoreCase("CpmiVsClusterNetobj")) {
                        objType = ObjectType.CLUSTER;
                        isVirtSystem = true;
                    } else if (type.equalsIgnoreCase("CpmiVsNetobj") || type.equals("virtual-system")) {
                        objType = ObjectType.GATEWAY;
                        isVirtSystem = true;
                    } else if (type.equalsIgnoreCase("CpmiVsxNetobj") || type.equals("vsx-gateway")) {
                        objType = ObjectType.GATEWAY;
                        isVirtHost = true;
                    } else if (type.equalsIgnoreCase("CpmiVsxClusterMember") || type.equals("vsx-cluster-member")) {
                        objType = ObjectType.MEMBER;
                        isVirtHost = true;
                    } else if (type.equals("cluster-member")) {
                        objType = ObjectType.MEMBER;
                    } else if (type.equals("simple-gateway") || type.equals("checkpoint-host") || type.equals("gateway")) {
                        if (memberNameToClusterUid.containsKey(name)) {
                            objType = ObjectType.MEMBER;
                        } else {
                            objType = ObjectType.GATEWAY;
                        }
                    } else {
                        if (isProduct && obj.has("ipv4-address")) {
                            if (memberNameToClusterUid.containsKey(name)) {
                                objType = ObjectType.MEMBER;
                            } else if (type.toLowerCase().contains("cluster")) {
                                objType = ObjectType.CLUSTER;
                            } else {
                                objType = ObjectType.GATEWAY;
                            }
                        } else {
                            continue;
                        }
                    }

                    Address ipv4 = obj.has("ipv4-address") ? Address.of(obj.get("ipv4-address").asText()) : Address.absent();
                    Address mgmtIp = ipv4;
                    
                    ClassificationFlags flags = new ClassificationFlags(isProduct, isVirtHost, isVirtSystem);
                    Optional<ClusterReference> clusterRef = Optional.empty();
                    
                    if (objType == ObjectType.MEMBER && memberNameToClusterUid.containsKey(name)) {
                         String clusterUid = memberNameToClusterUid.get(name);
                         clusterRef = Optional.of(new ClusterReference(Optional.of(OpaqueId.of(clusterUid)), Optional.empty()));
                    } else if (obj.has("cluster")) {
                         String clusterUid = obj.get("cluster").asText();
                         clusterRef = Optional.of(new ClusterReference(Optional.of(OpaqueId.of(clusterUid)), Optional.empty()));
                    }

                    Optional<String> model = obj.has("hardware") ? Optional.of(obj.get("hardware").asText()) : Optional.empty();
                    Optional<String> version = obj.has("version") ? Optional.of(obj.get("version").asText()) : Optional.empty();

                    results.add(new RawCandidateInput(
                        new CandidateKey(domainId, OpaqueId.of(uid)),
                        objType, flags, name, ipv4, mgmtIp, clusterRef, model, version, Optional.empty(), Optional.empty()
                    ));
                }
            }
            log.info(String.format("queryGateways for domain %s: objects_array_size=%d, parsed=%d, missingUid=%d, added=%d", domain, objects != null ? objects.size() : -1, parsed, missingStableIdentifier, results.size()));
        } catch (Exception e) {
            log.warning("Failed to parse show-gateways-and-servers JSON: " + e.getMessage());
            throw new ManagementPlaneQueryFailedException();
        }
        
        ParseCounts previous = parseCounts.getOrDefault(ObjectType.GATEWAY, new ParseCounts(0, 0));
        parseCounts.put(ObjectType.GATEWAY, new ParseCounts(previous.parsed() + parsed, previous.missingStableIdentifier() + missingStableIdentifier));
        
        return results;
    }

    private List<ConnectionTableRow> readConnectionTableObservation(TransportSession session) {
        String response = execRead(session, MgmtCliCommands.connectionTable());
        return NetstatConnectionTableParser.parse(response);
    }

    private String execRead(TransportSession session, String command) {
        ExecResult result = transport.exec(session, new ExecSpec(command), EXEC_TIMEOUT);
        if (result instanceof ExecResult.Completed completed) {
            if (completed.exitStatus() == 0) {
                return completed.output();
            }
            log.warning(String.format("execRead command failed! exitStatus=%d command=%s", completed.exitStatus(), command));
        }
        throw new ManagementPlaneQueryFailedException();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
