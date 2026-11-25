package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstall;
import com.securityexpert.nexus.ui2.persistence.device.DevicePolicyInstallRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDevicePolicyInstallRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicySnapshotRepository;
import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;

/**
 * Operations &gt; Policy installation — Phase 1 (P1: Read-Only Monitoring) service
 * ({@code docs/design/OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md}, FROZEN).
 *
 * <p>Consumes only data already stored by existing collections from {@code SIGNED_OFF} command-gate
 * reads. Issues zero network-device commands and exposes zero write paths.</p>
 */
@Service
public final class PolicyInstallMonitoringService {

    /**
     * The newest backup of a device (outer alias {@code d}) with its saved-state relation. The relation is keyed by the
     * manifest's {@code backup_artefact.artefact_id} (V253); public so the integration test runs the very same join.
     */
    public static final String ASA_LATEST_BACKUP_RELATION_SQL = """
            select a.created_at, rel.relation
            from backup_artefact a
            left join asa_config_relation rel on rel.artefact_id = a.artefact_id
            where a.device_id = d.device_id::text
              and a.artefact_class = 'backup'
            order by a.created_at desc, a.artefact_id desc
            limit 1""";


    public static final String CONTRACT_REF =
            "docs/design/OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md";

    public static final String STATE_INSTALLED = "INSTALLED";
    public static final String STATE_OUT_OF_DATE = "OUT_OF_DATE";
    public static final String STATE_NOT_INSTALLED = "NOT_INSTALLED";
    public static final String STATE_SAVED = "SAVED";
    public static final String STATE_UNSAVED = "UNSAVED";
    public static final String STATE_NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String STATE_UNKNOWN = "UNKNOWN";

    public static final List<String> CLOSED_STATES = List.of(
            STATE_INSTALLED,
            STATE_OUT_OF_DATE,
            STATE_NOT_INSTALLED,
            STATE_SAVED,
            STATE_UNSAVED,
            STATE_NOT_APPLICABLE,
            STATE_UNKNOWN
    );

    public static final String PARITY_EQUAL = "EQUAL";
    public static final String PARITY_DIFFER = "DIFFER";
    public static final String PARITY_UNKNOWN = "UNKNOWN";

    public static final String VENDOR_CHECK_POINT = "check_point";
    public static final String VENDOR_PALO_ALTO = "palo_alto";
    public static final String VENDOR_CISCO_ASA = "cisco_asa";
    public static final String VENDOR_FORTINET = "fortinet";

    public static final Set<String> SUPPORTED_VENDORS = Set.of(
            VENDOR_CHECK_POINT,
            VENDOR_PALO_ALTO,
            VENDOR_CISCO_ASA,
            VENDOR_FORTINET
    );

    public static final String REASON_MANAGEMENT_ONLY = "MANAGEMENT_ONLY";
    public static final String REASON_NEEDS_UNAPPROVED_READ_GI1 =
            "needs a read not approved yet (GI-1: per-VS cpstat policy read)";
    public static final String REASON_NEEDS_UNAPPROVED_READ_GI2 =
            "needs a read not approved yet (GI-2: Panorama commit-all job history read)";
    public static final String REASON_NEEDS_UNAPPROVED_READ_GI3 =
            "needs a read not approved yet (GI-3: FortiManager package status not measured yet)";

    public static final int PAGE_SIZE = 200;

    public record Request(String vendor, String state, String q, int page) {
        public Request {
            vendor = vendor == null ? "" : vendor.trim().toLowerCase(Locale.ROOT);
            state = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
            q = q == null ? "" : q.trim();
        }
    }

    public record DerivedStatus(String state, String haParity, String reason) {
        public DerivedStatus {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
            if (!CLOSED_STATES.contains(state)) {
                throw new IllegalArgumentException("State outside closed vocabulary: " + state);
            }
        }
    }

    public record AsaInput(
            String deviceId,
            String hostname,
            String relation,
            Instant backupCreatedAt,
            Instant latestChangedConfigAt
    ) {}

    public record FortiGateInput(
            String deviceId,
            String hostname,
            String role,
            String checkStatus,
            String derivedChecksums,
            String derivedReason,
            Instant observedAt
    ) {}

    public record DomainGatewayInventory(
            List<CpObjectInventory.Item> gateways,
            Map<String, String> linksByObjectUid
    ) {}

    public record StoredEvidence(
            List<PolicySnapshot.Metadata> snapshots,
            Map<String, DomainGatewayInventory> cpDomainGateways,
            Map<String, Instant> packageLastChangeAt,
            Map<String, DevicePolicyInstall> deviceInstalls,
            List<AsaInput> asaGateways,
            List<FortiGateInput> fortinetDevices
    ) {}

    private final TransactionBoundary transactions;
    private final PolicySnapshotRepository snapshotRepository;
    private final ObjectMapper mapper;
    private final TopologyNamePseudonymizer names;
    private final Supplier<StoredEvidence> evidenceSupplier;
    private volatile DevicePolicyInstallRepository installRepository;
    private volatile DeviceRepository devices;
    private volatile PolicyFirewallNames firewalls;

    @Autowired
    public PolicyInstallMonitoringService(
            TransactionBoundary transactions,
            ObjectMapper mapper,
            TopologyNamePseudonymizer names
    ) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.snapshotRepository = new PolicySnapshotRepository(transactions);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.installRepository = new JooqDevicePolicyInstallRepository(transactions);
        this.names = Objects.requireNonNull(names, "names");
        this.evidenceSupplier = this::loadFromDatabase;
    }

    /** Package-private / test constructor with an explicit evidence supplier. */
    public PolicyInstallMonitoringService(Supplier<StoredEvidence> evidenceSupplier, TopologyNamePseudonymizer names) {
        this.transactions = null;
        this.snapshotRepository = null;
        this.mapper = new ObjectMapper();
        this.installRepository = DevicePolicyInstallRepository.NONE;
        this.names = Objects.requireNonNull(names, "names");
        this.evidenceSupplier = Objects.requireNonNull(evidenceSupplier, "evidenceSupplier");
    }

    @Autowired(required = false)
    public void setDeviceRepository(DeviceRepository devices) {
        this.devices = devices;
        this.firewalls = devices == null ? null : new PolicyFirewallNames(devices);
    }

    @Autowired(required = false)
    public void setPolicyInstallRepository(DevicePolicyInstallRepository installRepository) {
        if (installRepository != null) {
            this.installRepository = installRepository;
        }
    }

    public void validate(Request request) {
        if (request.page() < 0) {
            throw new IllegalArgumentException("page must be non-negative");
        }
        if (!request.vendor().isEmpty() && !SUPPORTED_VENDORS.contains(request.vendor())) {
            throw new IllegalArgumentException("Unsupported vendor filter: " + request.vendor());
        }
        if (!request.state().isEmpty() && !CLOSED_STATES.contains(request.state())) {
            throw new IllegalArgumentException("Unsupported state filter: " + request.state());
        }
    }

    /**
     * Council Fix (a) &amp; (c): Check Point state derivation is computed strictly PER PACKAGE
     * (comparing the package's own {@code packageLastChangeAt} and {@code packageName} against the
     * gateway's direct {@code cpstat} read), never from the MDS domain's last-published session.
     * Non-VS0 virtual system contexts require candidate read {@code GI-1} and remain {@code UNKNOWN}
     * with {@link #REASON_NEEDS_UNAPPROVED_READ_GI1}.
     */
    public static DerivedStatus deriveCheckPointState(
            String packageName,
            String targetContext,
            Boolean managementClaimedInstalled,
            DevicePolicyInstall directInstall,
            Instant packageLastChangeAt
    ) {
        if (requiresCheckPointPerVsRead(targetContext)) {
            return new DerivedStatus(STATE_UNKNOWN, null, REASON_NEEDS_UNAPPROVED_READ_GI1);
        }
        if (directInstall == null) {
            if (Boolean.TRUE.equals(managementClaimedInstalled)) {
                return new DerivedStatus(STATE_UNKNOWN, null, REASON_MANAGEMENT_ONLY);
            }
            return new DerivedStatus(STATE_UNKNOWN, null, "Direct gateway cpstat policy read not collected yet");
        }
        String reportedPolicy = directInstall.policyName().map(String::trim).orElse("");
        if (reportedPolicy.isEmpty() || "-".equals(reportedPolicy) || "none".equalsIgnoreCase(reportedPolicy)) {
            return new DerivedStatus(STATE_NOT_INSTALLED, null, "Gateway reports no installed policy");
        }
        if (packageName == null || !reportedPolicy.equals(packageName)) {
            return new DerivedStatus(
                    STATE_NOT_INSTALLED,
                    null,
                    "Gateway reports a different installed policy name than this package"
            );
        }
        if (directInstall.installedAt().isEmpty()) {
            return new DerivedStatus(STATE_UNKNOWN, null, "Gateway cpstat policy read has no parseable install timestamp");
        }
        if (packageLastChangeAt == null) {
            return new DerivedStatus(STATE_UNKNOWN, null, "Package last change timestamp is unknown");
        }
        Instant installedAt = directInstall.installedAt().get();
        if (packageLastChangeAt.isAfter(installedAt)) {
            return new DerivedStatus(STATE_OUT_OF_DATE, null, "Package changed after gateway installation timestamp");
        }
        return new DerivedStatus(STATE_INSTALLED, null, "Direct gateway cpstat corroborates package name and install timestamp");
    }

    public static boolean requiresCheckPointPerVsRead(String targetContext) {
        if (targetContext == null) {
            return false;
        }
        String normalized = targetContext.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        return !("0".equals(normalized)
                || "vs0".equalsIgnoreCase(normalized)
                || "vs_0".equalsIgnoreCase(normalized)
                || "physical".equalsIgnoreCase(normalized));
    }

    /**
     * Palo Alto Panorama state derivation: management {@code IN_SYNC} never produces {@code INSTALLED}
     * without direct firewall runtime commit corroboration ({@code MANAGEMENT_ONLY}).
     */
    public static DerivedStatus derivePaloAltoState(
            String dgName,
            String panoramaSyncStatus,
            boolean targetEnrolled,
            DevicePolicyInstall directInstall,
            Instant dgLastChangeAt
    ) {
        String sync = panoramaSyncStatus == null ? "" : panoramaSyncStatus.trim().toUpperCase(Locale.ROOT);
        if ("OUT_OF_SYNC".equals(sync)) {
            return new DerivedStatus(STATE_OUT_OF_DATE, null, "Panorama device-group shared policy status is OUT_OF_SYNC");
        }
        if ("IN_SYNC".equals(sync)) {
            if (!targetEnrolled || directInstall == null || directInstall.installedAt().isEmpty()) {
                return new DerivedStatus(STATE_UNKNOWN, null, REASON_MANAGEMENT_ONLY);
            }
            if (directInstall.policyName().isPresent()) {
                String reported = directInstall.policyName().get().trim();
                if (!reported.isEmpty() && dgName != null && !dgName.isEmpty() && !reported.equals(dgName)) {
                    return new DerivedStatus(STATE_NOT_INSTALLED, null, "Firewall reports a different policy container");
                }
            }
            Instant installedAt = directInstall.installedAt().get();
            if (dgLastChangeAt != null && dgLastChangeAt.isAfter(installedAt)) {
                return new DerivedStatus(STATE_OUT_OF_DATE, null, "Device group rules changed after firewall's latest commit timestamp");
            }
            return new DerivedStatus(STATE_INSTALLED, null, "Panorama IN_SYNC corroborated by direct firewall commit job timestamp");
        }
        return new DerivedStatus(STATE_UNKNOWN, null, REASON_NEEDS_UNAPPROVED_READ_GI2);
    }

    /**
     * Council Fix (b): Cisco ASA rows display {@code SAVED} or {@code UNSAVED}, never {@code INSTALLED}. The verdict is the
     * worker's normalised running-vs-startup relation of the latest backup (contract section 3.3, amendment 2026-11-18):
     * {@code MATCH}, {@code MISMATCH} or {@code NOT_EVALUABLE}; null when that backup has none.
     */
    public static DerivedStatus deriveCiscoAsaState(String relation, boolean newerChangedConfigurationRunExists) {
        String r = relation == null ? "" : relation.trim().toUpperCase(java.util.Locale.ROOT);
        if (r.isEmpty()) {
            return new DerivedStatus(STATE_UNKNOWN, null,
                    "Running vs startup comparison not measured for the latest backup yet");
        }
        if ("NOT_EVALUABLE".equals(r)) {
            return new DerivedStatus(STATE_UNKNOWN, null,
                    "Running vs startup comparison not possible: the backup lacks one of the two configuration texts");
        }
        if (newerChangedConfigurationRunExists) {
            return new DerivedStatus(STATE_UNKNOWN, null,
                    "Configuration changed after the last backup; saved state not measured since");
        }
        if ("MISMATCH".equals(r)) {
            return new DerivedStatus(STATE_UNSAVED, null,
                    "running-config differs from startup-config (unsaved running configuration changes)");
        }
        if ("MATCH".equals(r)) {
            return new DerivedStatus(STATE_SAVED, null, "running-config matches startup-config");
        }
        return new DerivedStatus(STATE_UNKNOWN, null, "Running vs startup comparison has an unrecognised result");
    }

    /**
     * Council Fix (b): Directly managed FortiGate rows display {@code NOT_APPLICABLE} plus an HA
     * parity indicator ({@code EQUAL}, {@code DIFFER}, or {@code UNKNOWN}) derived from
     * {@code fgt_failover_checksum_cluster}.
     */
    public static DerivedStatus deriveFortiGateDirectState(
            String checkStatus,
            String derivedChecksums,
            String derivedReason
    ) {
        if ("MATCH".equalsIgnoreCase(derivedChecksums)
                || ("PASS".equalsIgnoreCase(checkStatus) && (derivedChecksums == null || derivedChecksums.isBlank()))) {
            return new DerivedStatus(
                    STATE_NOT_APPLICABLE,
                    PARITY_EQUAL,
                    "FortiOS applies changes immediately; HA cluster checksums equal"
            );
        }
        if ("MISMATCH".equalsIgnoreCase(derivedChecksums) || "CHECKSUM_DIFFERS".equalsIgnoreCase(derivedReason)) {
            return new DerivedStatus(
                    STATE_NOT_APPLICABLE,
                    PARITY_DIFFER,
                    "FortiOS applies changes immediately; HA cluster checksums differ"
            );
        }
        return new DerivedStatus(
                STATE_NOT_APPLICABLE,
                PARITY_UNKNOWN,
                "FortiOS applies changes immediately; HA cluster checksum parity unknown"
        );
    }

    /**
     * Council Fix (b) &amp; (c): FortiManager-managed policy status remains {@code UNKNOWN} until
     * measured and approved ({@code GI-3}).
     */
    public static DerivedStatus deriveFortiManagerState() {
        return new DerivedStatus(STATE_UNKNOWN, null, REASON_NEEDS_UNAPPROVED_READ_GI3);
    }

    public Map<String, Object> status(Request request, boolean masked) {
        validate(request);
        StoredEvidence evidence = evidenceSupplier.get();
        List<Map<String, Object>> allRows = buildRows(evidence, masked);

        String needle = request.q().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> vendorAndQueryFiltered = allRows.stream()
                .filter(row -> request.vendor().isEmpty() || request.vendor().equals(row.get("vendor")))
                .filter(row -> needle.isEmpty() || matchesSearch(row, needle))
                .toList();

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String state : CLOSED_STATES) {
            counts.put(state, 0);
        }
        for (Map<String, Object> row : vendorAndQueryFiltered) {
            String rowState = String.valueOf(row.get("state"));
            counts.computeIfPresent(rowState, (k, v) -> v + 1);
        }

        List<Map<String, Object>> filtered = vendorAndQueryFiltered.stream()
                .filter(row -> request.state().isEmpty() || request.state().equals(row.get("state")))
                .toList();

        List<Map<String, Object>> paged = filtered.stream()
                .skip((long) request.page() * PAGE_SIZE)
                .limit(PAGE_SIZE)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("counts", Collections.unmodifiableMap(counts));
        response.put("total", filtered.size());
        response.put("page", request.page());
        response.put("page_size", PAGE_SIZE);
        response.put("masked", masked);
        response.put("contract_ref", CONTRACT_REF);
        response.put("rows", paged);
        return Collections.unmodifiableMap(response);
    }

    private static boolean matchesSearch(Map<String, Object> row, String needle) {
        for (String key : List.of("hostname", "container_name", "package_name", "policy_name", "reason", "vendor", "state")) {
            Object val = row.get(key);
            if (val != null && String.valueOf(val).toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> buildRows(StoredEvidence evidence, boolean masked) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Instant> lastChanges = evidence.packageLastChangeAt() == null ? Map.of() : evidence.packageLastChangeAt();
        Map<String, DevicePolicyInstall> installs = evidence.deviceInstalls() == null ? Map.of() : evidence.deviceInstalls();
        Map<String, DomainGatewayInventory> cpDomains = evidence.cpDomainGateways() == null ? Map.of() : evidence.cpDomainGateways();

        for (PolicySnapshot.Metadata snapshot : evidence.snapshots() == null ? List.<PolicySnapshot.Metadata>of() : evidence.snapshots()) {
            String snapshotVendor = snapshot.vendor() == null ? "" : snapshot.vendor();
            Instant pkgLastChange = lastChanges.get(snapshot.id());
            if ("CP".equalsIgnoreCase(snapshotVendor)) {
                buildCheckPointRowsForPackage(snapshot, cpDomains, installs, pkgLastChange, masked, rows);
            } else if ("PAN".equalsIgnoreCase(snapshotVendor)) {
                buildPaloAltoRowsForDeviceGroup(snapshot, installs, pkgLastChange, masked, rows);
            }
        }

        for (AsaInput asa : evidence.asaGateways() == null ? List.<AsaInput>of() : evidence.asaGateways()) {
            boolean newerChanged = asa.latestChangedConfigAt() != null
                    && (asa.backupCreatedAt() == null || asa.latestChangedConfigAt().isAfter(asa.backupCreatedAt()));
            DerivedStatus derived = deriveCiscoAsaState(asa.relation(), newerChanged);
            Instant lastChange = asa.latestChangedConfigAt() != null ? asa.latestChangedConfigAt() : asa.backupCreatedAt();
            rows.add(toRowMap(
                    "asa:" + asa.deviceId(),
                    asa.deviceId(),
                    asa.hostname(),
                    "",
                    VENDOR_CISCO_ASA,
                    asa.deviceId(),
                    "running-config vs startup-config",
                    "asa-config:" + asa.deviceId(),
                    "running-config / startup-config",
                    derived,
                    null,
                    asa.backupCreatedAt(),
                    lastChange,
                    masked
            ));
        }

        for (FortiGateInput fgt : evidence.fortinetDevices() == null ? List.<FortiGateInput>of() : evidence.fortinetDevices()) {
            boolean isManager = "management_server".equalsIgnoreCase(fgt.role());
            DerivedStatus derived = isManager
                    ? deriveFortiManagerState()
                    : deriveFortiGateDirectState(fgt.checkStatus(), fgt.derivedChecksums(), fgt.derivedReason());
            String containerLabel = isManager ? "FortiManager ADOM" : "Direct FortiOS (cfg-save automatic)";
            String pkgLabel = isManager ? "FortiManager policy package" : "Local firewall policy";
            rows.add(toRowMap(
                    "fgt:" + fgt.deviceId(),
                    fgt.deviceId(),
                    fgt.hostname(),
                    "",
                    VENDOR_FORTINET,
                    fgt.deviceId(),
                    containerLabel,
                    "fgt-policy:" + fgt.deviceId(),
                    pkgLabel,
                    derived,
                    null,
                    fgt.observedAt(),
                    null,
                    masked
            ));
        }

        rows.sort(Comparator
                .comparing((Map<String, Object> r) -> String.valueOf(r.getOrDefault("vendor", "")))
                .thenComparing(r -> String.valueOf(r.getOrDefault("container_name", "")))
                .thenComparing(r -> String.valueOf(r.getOrDefault("package_name", "")))
                .thenComparing(r -> String.valueOf(r.getOrDefault("hostname", "")))
                .thenComparing(r -> String.valueOf(r.getOrDefault("row_id", ""))));
        return rows;
    }

    private void buildCheckPointRowsForPackage(
            PolicySnapshot.Metadata snapshot,
            Map<String, DomainGatewayInventory> cpDomains,
            Map<String, DevicePolicyInstall> installs,
            Instant pkgLastChange,
            boolean masked,
            List<Map<String, Object>> out
    ) {
        String domainKey = snapshot.sourceId() + ":" + snapshot.containerId();
        DomainGatewayInventory domainInv = cpDomains.get(domainKey);
        Map<String, PolicySnapshot.Target> targetsByDeviceId = new LinkedHashMap<>();
        Map<String, PolicySnapshot.Target> targetsByName = new LinkedHashMap<>();
        for (PolicySnapshot.Target target : snapshot.targets() == null ? List.<PolicySnapshot.Target>of() : snapshot.targets()) {
            if (target.deviceId() != null && !target.deviceId().isBlank()) {
                targetsByDeviceId.putIfAbsent(target.deviceId(), target);
            }
            if (target.name() != null && !target.name().isBlank()) {
                targetsByName.putIfAbsent(target.name(), target);
            }
        }

        Set<String> emittedKeys = new HashSet<>();
        if (domainInv != null && domainInv.gateways() != null) {
            Map<String, String> links = domainInv.linksByObjectUid() == null ? Map.of() : domainInv.linksByObjectUid();
            for (CpObjectInventory.Item gw : domainInv.gateways()) {
                for (CpObjectInventory.Installation inst : gw.policyInstallations()) {
                    if (!Objects.equals(inst.policyName(), snapshot.name())) {
                        continue;
                    }
                    String deviceId = links.get(gw.uid());
                    PolicySnapshot.Target matchedTarget = (deviceId != null ? targetsByDeviceId.get(deviceId) : null);
                    if (matchedTarget == null) {
                        matchedTarget = targetsByName.get(gw.name());
                    }
                    if (deviceId == null && matchedTarget != null && matchedTarget.deviceId() != null && !matchedTarget.deviceId().isBlank()) {
                        deviceId = matchedTarget.deviceId();
                    }
                    String context = matchedTarget != null && matchedTarget.context() != null ? matchedTarget.context() : "";
                    DevicePolicyInstall direct = deviceId == null ? null : installs.get(deviceId);
                    DerivedStatus derived = deriveCheckPointState(
                            snapshot.name(),
                            context,
                            inst.installed(),
                            direct,
                            pkgLastChange
                    );
                    String targetKey = deviceId != null && !deviceId.isBlank() ? deviceId : gw.uid();
                    emittedKeys.add(targetKey + ":" + context);
                    if (matchedTarget != null && matchedTarget.name() != null) {
                        emittedKeys.add(matchedTarget.name() + ":" + context);
                    }
                    out.add(toRowMap(
                            "cp:" + snapshot.id() + ":" + targetKey + (context.isEmpty() ? "" : ":" + context),
                            deviceId,
                            gw.name(),
                            context,
                            VENDOR_CHECK_POINT,
                            snapshot.containerId(),
                            snapshot.containerName(),
                            snapshot.id(),
                            snapshot.name(),
                            derived,
                            direct == null ? null : direct.policyName().orElse(null),
                            direct == null ? null : direct.installedAt().orElse(null),
                            pkgLastChange,
                            masked
                    ));
                }
            }
        }

        for (PolicySnapshot.Target target : snapshot.targets() == null ? List.<PolicySnapshot.Target>of() : snapshot.targets()) {
            String context = target.context() == null ? "" : target.context();
            String deviceId = target.deviceId() != null && !target.deviceId().isBlank() ? target.deviceId() : null;
            String dedupById = (deviceId == null ? "" : deviceId) + ":" + context;
            String dedupByName = (target.name() == null ? "" : target.name()) + ":" + context;
            if ((deviceId != null && emittedKeys.contains(dedupById)) || emittedKeys.contains(dedupByName)) {
                continue;
            }
            Boolean mgmtClaimed = isManagementClaimedInstalled(target.syncStatus());
            DevicePolicyInstall direct = deviceId == null ? null : installs.get(deviceId);
            DerivedStatus derived = deriveCheckPointState(
                    snapshot.name(),
                    context,
                    mgmtClaimed,
                    direct,
                    pkgLastChange
            );
            String targetKey = deviceId != null ? deviceId : (target.name() == null ? "unknown" : target.name());
            out.add(toRowMap(
                    "cp:" + snapshot.id() + ":" + targetKey + (context.isEmpty() ? "" : ":" + context),
                    deviceId,
                    target.name() == null || target.name().isBlank() ? targetKey : target.name(),
                    context,
                    VENDOR_CHECK_POINT,
                    snapshot.containerId(),
                    snapshot.containerName(),
                    snapshot.id(),
                    snapshot.name(),
                    derived,
                    direct == null ? null : direct.policyName().orElse(null),
                    direct == null ? null : direct.installedAt().orElse(null),
                    pkgLastChange,
                    masked
            ));
        }
    }

    private static Boolean isManagementClaimedInstalled(String syncStatus) {
        if (syncStatus == null) {
            return null;
        }
        String normalized = syncStatus.trim().toUpperCase(Locale.ROOT);
        if ("INSTALLED".equals(normalized) || "IN_SYNC".equals(normalized)
                || "TRUE".equals(normalized) || "MANAGEMENT_ONLY".equals(normalized)) {
            return Boolean.TRUE;
        }
        return null;
    }

    private void buildPaloAltoRowsForDeviceGroup(
            PolicySnapshot.Metadata snapshot,
            Map<String, DevicePolicyInstall> installs,
            Instant dgLastChange,
            boolean masked,
            List<Map<String, Object>> out
    ) {
        PolicyFirewallNames fwNames = this.firewalls;
        for (PolicySnapshot.Target target : snapshot.targets() == null ? List.<PolicySnapshot.Target>of() : snapshot.targets()) {
            String deviceId = target.deviceId() != null && !target.deviceId().isBlank() ? target.deviceId() : null;
            boolean enrolled = deviceId != null;
            String rawTargetName = fwNames != null
                    ? fwNames.targetName(deviceId, target.name())
                    : (target.name() == null ? "" : target.name());
            DevicePolicyInstall direct = enrolled ? installs.get(deviceId) : null;
            DerivedStatus derived = derivePaloAltoState(
                    snapshot.name(),
                    target.syncStatus(),
                    enrolled,
                    direct,
                    dgLastChange
            );
            String context = target.context() == null ? "" : target.context();
            String targetKey = enrolled ? deviceId : (rawTargetName.isEmpty() ? "unknown" : rawTargetName);
            String policyName = direct != null && direct.policyName().isPresent()
                    ? direct.policyName().get()
                    : snapshot.name();
            out.add(toRowMap(
                    "pan:" + snapshot.id() + ":" + targetKey + (context.isEmpty() ? "" : ":" + context),
                    deviceId,
                    rawTargetName.isEmpty() ? targetKey : rawTargetName,
                    context,
                    VENDOR_PALO_ALTO,
                    snapshot.containerId(),
                    snapshot.containerName(),
                    snapshot.id(),
                    snapshot.name(),
                    derived,
                    policyName,
                    direct == null ? null : direct.installedAt().orElse(null),
                    dgLastChange,
                    masked
            ));
        }
    }

    private Map<String, Object> toRowMap(
            String rowId,
            String deviceId,
            String rawHostname,
            String context,
            String vendor,
            String containerId,
            String rawContainerName,
            String packageId,
            String rawPackageName,
            DerivedStatus derived,
            String rawPolicyName,
            Instant installedAt,
            Instant lastChangeAt,
            boolean masked
    ) {
        String targetTokenSeed = (deviceId != null && !deviceId.isBlank())
                ? deviceId
                : (rawHostname == null || rawHostname.isBlank() ? rowId : rawHostname);
        String displayHostname = masked
                ? names.maskDeviceName(targetTokenSeed, null, deviceId)
                : (rawHostname == null ? "" : rawHostname);

        String displayContainerName;
        String displayPackageName;
        if (VENDOR_CISCO_ASA.equals(vendor) || VENDOR_FORTINET.equals(vendor)) {
            displayContainerName = rawContainerName == null ? "" : rawContainerName;
            displayPackageName = rawPackageName == null ? "" : rawPackageName;
        } else {
            String domainSeed = (containerId != null && !containerId.isBlank()) ? containerId : rawContainerName;
            displayContainerName = masked
                    ? names.maskDomainName(domainSeed == null || domainSeed.isBlank() ? rowId : domainSeed)
                    : (rawContainerName == null ? "" : rawContainerName);
            String pkgSeed = (rawPackageName != null && !rawPackageName.isBlank()) ? rawPackageName : packageId;
            displayPackageName = masked
                    ? names.maskPolicyName("policy", pkgSeed == null || pkgSeed.isBlank() ? rowId : pkgSeed)
                    : (rawPackageName == null ? "" : rawPackageName);
        }

        String displayPolicyName = null;
        if (rawPolicyName != null && !rawPolicyName.isBlank()) {
            displayPolicyName = masked ? names.maskPolicyName("policy", rawPolicyName) : rawPolicyName;
        }

        // A virtual-system or VDOM name is configuration text: the replay viewer sees the same pseudonym the other
        // screens use (PolicyLookupController, ZoneMatrixService), in the context column and in the row id built from it.
        String rawContext = context == null ? "" : context;
        String displayContext = masked && !rawContext.isEmpty() ? names.maskVirtualSystem(rawContext, null) : rawContext;
        String displayRowId = masked && !rawContext.isEmpty() && rowId.endsWith(":" + rawContext)
                ? rowId.substring(0, rowId.length() - rawContext.length()) + displayContext : rowId;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("row_id", displayRowId);
        row.put("device_id", deviceId);
        row.put("hostname", displayHostname);
        row.put("context", displayContext);
        row.put("vendor", vendor);
        row.put("container_id", containerId == null ? "" : containerId);
        row.put("container_name", displayContainerName);
        row.put("package_id", packageId == null ? "" : packageId);
        row.put("package_name", displayPackageName);
        row.put("state", derived.state());
        row.put("ha_parity", derived.haParity());
        row.put("policy_name", displayPolicyName);
        row.put("installed_at", installedAt == null ? null : installedAt.toString());
        row.put("last_change_at", lastChangeAt == null ? null : lastChangeAt.toString());
        row.put("reason", derived.reason());
        return Collections.unmodifiableMap(row);
    }

    private StoredEvidence loadFromDatabase() {
        List<PolicySnapshot.Metadata> snapshots = new ArrayList<>();
        for (String json : snapshotRepository.catalog()) {
            try {
                snapshots.add(mapper.readValue(json, PolicySnapshot.Metadata.class));
            } catch (IOException ignored) {
            }
        }
        Map<String, DomainGatewayInventory> cpDomains = new LinkedHashMap<>();

        for (PolicySnapshot.Metadata m : snapshots) {
            if ("CP".equalsIgnoreCase(m.vendor())) {
                String domainKey = m.sourceId() + ":" + m.containerId();
                if (!cpDomains.containsKey(domainKey)) {
                    List<CpObjectInventory.Item> gateways = new ArrayList<>();
                    for (String invJson : snapshotRepository.inventories(m.sourceId(), m.containerId())) {
                        try {
                            CpObjectInventory inv = mapper.readValue(invJson, CpObjectInventory.class);
                            if ("gateways-and-servers".equals(inv.type()) && inv.objects() != null) {
                                gateways.addAll(inv.objects());
                            }
                        } catch (IOException ignored) {
                        }
                    }
                    Map<String, String> links = new LinkedHashMap<>();
                    for (PolicySnapshotRepository.InventoryLink link : snapshotRepository.inventoryLinks(m.sourceId(), m.containerId())) {
                        if (link.uid() != null && !link.uid().isEmpty() && link.deviceId() != null) {
                            links.putIfAbsent(link.uid(), link.deviceId());
                        }
                        if (link.name() != null && !link.name().isEmpty() && link.deviceId() != null) {
                            links.putIfAbsent(link.name(), link.deviceId());
                        }
                    }
                    cpDomains.put(domainKey, new DomainGatewayInventory(gateways, links));
                }
            }
        }

        Map<String, DevicePolicyInstall> installs = installRepository.findAll();

        return transactions.inTransaction(dsl -> {
            Map<String, Instant> packageLastChanges = new LinkedHashMap<>();
            dsl.fetch("""
                    select policy_id,
                           max(case when not initial_capture then coalesce(fn_policy_change_time(changed_on), collected_at)
                                    else fn_policy_change_time(changed_on) end) as last_change_at
                    from policy_rule_history
                    group by policy_id
                    """).forEach(r -> {
                String policyId = r.get("policy_id", String.class);
                Instant at = toInstant(r.get("last_change_at"));
                if (policyId != null && at != null) {
                    packageLastChanges.put(policyId, at);
                }
            });

            List<AsaInput> asaGateways = dsl.fetch("""
                    select d.device_id::text as device_id,
                           coalesce(nullif(d.observed_hostname, ''), d.device_id::text) as hostname,
                           b.created_at as backup_created_at,
                           b.relation,
                           cfg.latest_changed_at
                    from devices d
                    left join lateral (""" + ASA_LATEST_BACKUP_RELATION_SQL + """
                    ) b on true
                    left join lateral (
                        select max(r.collected_at) as latest_changed_at
                        from device_configuration_run r
                        where r.device_id = d.device_id::text
                          and r.read_kind = 'show_configuration'
                          and r.change_state = 'changed'
                    ) cfg on true
                    where d.vendor_hint = 'cisco_asa'
                      and d.role in ('gateway', 'firewall')
                      and not d.disabled
                      and d.enrollment_state in ('ENROLLED', 'DEGRADED')
                    order by d.device_id
                    """).map(r -> new AsaInput(
                    r.get("device_id", String.class),
                    r.get("hostname", String.class),
                    r.get("relation", String.class),
                    toInstant(r.get("backup_created_at")),
                    toInstant(r.get("latest_changed_at"))
            ));

            List<FortiGateInput> fortinetDevices = dsl.fetch("""
                    select d.device_id::text as device_id,
                           coalesce(nullif(d.observed_hostname, ''), d.device_id::text) as hostname,
                           d.role,
                           chk.status as check_status,
                           chk.checksums as derived_checksums,
                           chk.reason as derived_reason,
                           chk.observed_at
                    from devices d
                    left join lateral (
                        select c.status,
                               c.derived->>'checksums' as checksums,
                               c.derived->>'reason' as reason,
                               c.observed_at
                        from failover_check_result c
                        join failover_run r on r.run_id = c.run_id
                        where r.vendor = 'fortinet'
                          and r.run_kind = 'READINESS'
                          and r.state in ('DONE', 'STOPPED')
                          and c.phase = 'pre'
                          and c.check_no = 4
                          and c.member_ref = d.device_id::text
                        order by c.observed_at desc, c.result_id desc
                        limit 1
                    ) chk on true
                    where d.vendor_hint = 'fortinet'
                      and d.role in ('gateway', 'firewall', 'management_server')
                      and not d.disabled
                      and d.enrollment_state in ('ENROLLED', 'DEGRADED')
                    order by d.device_id
                    """).map(r -> new FortiGateInput(
                    r.get("device_id", String.class),
                    r.get("hostname", String.class),
                    r.get("role", String.class),
                    r.get("check_status", String.class),
                    r.get("derived_checksums", String.class),
                    r.get("derived_reason", String.class),
                    toInstant(r.get("observed_at"))
            ));

            return new StoredEvidence(
                    snapshots,
                    cpDomains,
                    packageLastChanges,
                    installs,
                    asaGateways,
                    fortinetDevices
            );
        });
    }

    private static Instant toInstant(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        if (raw instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (raw instanceof Timestamp ts) {
            return ts.toInstant();
        }
        return Instant.parse(String.valueOf(raw));
    }
}
