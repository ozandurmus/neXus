package com.securityexpert.nexus.ui2.service.failover;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.JooqPanConfigSyncRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Server-owned decisions for the Palo Alto HA configuration sync (docs/design/PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md,
 * PO decision 2026-10-24 RATIFIED). HTTP names the unit and the two members the caller saw; the command, its target and
 * every fence are the server's. Check Point never has this action.
 */
@Service
public final class PanConfigSyncService {
    public static final String NOT_SUPPORTED = "CONFIG_SYNC_NOT_SUPPORTED";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * What the screens show for one unit. {@code offered}: the newest readiness recorded check 4 FAIL. {@code canRun}:
     * offered, an administrator, not the masked view (the button). {@code refusal}: the server's reason it would refuse
     * now (the button is then shown disabled with it); null when a request may be made. Opaque member ids only.
     */
    public record View(boolean offered, boolean canRun, String refusal, String sourceRef, String targetRef,
            Instant readinessObservedAt, JooqPanConfigSyncRepository.ConfigSync latest) {}

    private final JooqPanConfigSyncRepository store;
    private final CpFailoverService units;
    private final RbacEvaluator rbac;
    private final FailoverMutationSwitch mutationSwitch;
    private final JobWindowPolicy windows;

    @org.springframework.beans.factory.annotation.Autowired
    public PanConfigSyncService(JooqPanConfigSyncRepository store, CpFailoverService units, RbacEvaluator rbac,
            FailoverMutationSwitch mutationSwitch) {
        this(store, units, rbac, mutationSwitch, JobWindowPolicy.SYSTEM);
    }

    PanConfigSyncService(JooqPanConfigSyncRepository store, CpFailoverService units, RbacEvaluator rbac,
            FailoverMutationSwitch mutationSwitch, JobWindowPolicy windows) {
        this.store = store; this.units = units; this.rbac = rbac;
        this.mutationSwitch = java.util.Objects.requireNonNull(mutationSwitch);
        this.windows = java.util.Objects.requireNonNull(windows);
    }

    private boolean allowed(String actor, String role) {
        return actor != null && rbac.evaluate(actor, Optional.of(role), Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }
    private boolean replayViewer(String actor) {
        if (actor == null) return false;
        var decision = rbac.evaluate(actor, Optional.of(RoleToken.REPLAY_VIEWER), Instant.now());
        return decision.outcome() == AuthzOutcome.PERMITTED
            && decision.authority().filter(RbacEvaluator.ROOT_AUTHORITY::equals).isEmpty();
    }
    /** security_admin or operation_admin (the root administrator included), never a replay viewer. */
    public boolean mayRun(String actor) {
        return !replayViewer(actor) && (allowed(actor, RoleToken.SECURITY_ADMIN) || allowed(actor, RoleToken.OPERATION_ADMIN));
    }
    private void requireReader(String actor) {
        if (!allowed(actor, RoleToken.OPERATOR) && !allowed(actor, RoleToken.OPERATION_ADMIN)
                && !allowed(actor, RoleToken.SECURITY_ADMIN) && !allowed(actor, RoleToken.VIEWER)
                && !allowed(actor, RoleToken.REPLAY_VIEWER)) throw new CpFailoverService.Refusal("WRONG_ROLE");
    }

    public Optional<JooqPanConfigSyncRepository.ConfigSync> latest(CpFailoverService.Unit unit) {
        return store.latest(unit.members().get(0).clusterMemberRef().orElseThrow());
    }

    /** Argon P1-2: an open run or resume, or an unresolved failover write, on the unit (null when none). */
    public String busy(CpFailoverService.Unit unit) {
        return store.unitBusy(unit.members().get(0).clusterMemberRef().orElseThrow(), unit.members().get(0).deviceId(),
            unit.members().get(1).deviceId());
    }

    /**
     * The unit's config-sync view from facts the screen already read (the newest readiness, the unit refusal). Pure: no
     * store read besides {@code latest}, which the caller passes. Null for a Check Point unit or a virtual system.
     */
    public View view(CpFailoverService.Unit unit, String vendor, JooqCpFailoverRepository.ReadinessStatus readiness,
            String unitRefusal, boolean masked, boolean mayRun, Optional<JooqPanConfigSyncRepository.ConfigSync> latest) {
        return view(unit, vendor, readiness, unitRefusal, () -> null, masked, mayRun, latest);
    }

    /** {@code busy}: {@link #busy} for the unit; read only when check 4 failed (the summary stays one read per unit). */
    public View view(CpFailoverService.Unit unit, String vendor, JooqCpFailoverRepository.ReadinessStatus readiness,
            String unitRefusal, java.util.function.Supplier<String> busy, boolean masked, boolean mayRun,
            Optional<JooqPanConfigSyncRepository.ConfigSync> latest) {
        if (!"palo_alto".equals(vendor) || unit.vsId() != null) return null;
        boolean failed = false;
        Map<String, String> roles = new LinkedHashMap<>();
        if (readiness != null && readiness.checks() != null) try {
            for (var check : JSON.readTree(readiness.checks())) {
                int no = check.path("checkNo").asInt();
                if (no == 4 && "FAIL".equals(check.path("status").asText())) failed = true;
                // Argon P1-1: a role counts only from a check 1 row that passed.
                if (no == 1 && "PASS".equals(check.path("status").asText()))
                    roles.put(check.path("memberRef").asText(), check.path("derived").path("role").asText());
            }
        } catch (java.io.IOException invalid) { failed = false; }
        List<String> ids = unit.members().stream().map(DeviceSummaryRecord::deviceId).toList();
        String source = ids.stream().filter(id -> "active".equals(roles.get(id))).findFirst().orElse(null);
        String target = ids.stream().filter(id -> "passive".equals(roles.get(id))).findFirst().orElse(null);
        var open = latest.filter(JooqPanConfigSyncRepository.ConfigSync::open);
        String refusal;
        if (!failed) refusal = "CONFIG_SYNC_NOT_FAILED";
        else if (!mutationSwitch.enabled()) refusal = FailoverMutationSwitch.DISABLED;
        else if (open.isPresent()) refusal = "CONFIG_SYNC_ALREADY_ACTIVE";
        else if ((refusal = busy.get()) != null) { /* RUN_ALREADY_ACTIVE or UNRESOLVED_DISPATCH */ }
        else if ("OPEN_INCIDENT".equals(unitRefusal)) refusal = "OPEN_INCIDENT";
        else if (readiness.observedAt() == null
                || readiness.observedAt().isBefore(Instant.now().minus(JooqPanConfigSyncRepository.READINESS_MAX_AGE)))
            refusal = "READINESS_NOT_FRESH";
        else if (source == null || target == null || source.equals(target)) refusal = "ROLES_CHANGED";
        return new View(failed, failed && mayRun && !masked, refusal, source, target,
            readiness == null ? null : readiness.observedAt(), latest.orElse(null));
    }

    /**
     * Design §3.1: request the sync. The actor is the session; inside business hours the administrator's grant must
     * carry the acknowledgement header (409 BUSINESS_HOURS_CONFIRM_REQUIRED otherwise). The repository rechecks every
     * fence under the admission lock and creates the record and its job together.
     */
    public JooqPanConfigSyncRepository.ConfigSync request(String clusterId, String unitId, String sourceDeviceId,
            String targetDeviceId, String actor, String vendor) {
        if (!"palo_alto".equals(vendor)) throw new CpFailoverService.Refusal(NOT_SUPPORTED);
        if (!mayRun(actor)) throw new CpFailoverService.Refusal("WRONG_ROLE");
        if (!mutationSwitch.enabled()) throw new CpFailoverService.Refusal(FailoverMutationSwitch.DISABLED);
        windows.requireOpenFor(actor);
        var unit = units.unitFor(clusterId, unitId, actor, vendor);
        if (unit.vsId() != null) throw new CpFailoverService.Refusal(NOT_SUPPORTED);
        List<String> ids = unit.members().stream().map(DeviceSummaryRecord::deviceId).toList();
        if (sourceDeviceId == null || targetDeviceId == null || sourceDeviceId.equals(targetDeviceId)
                || !ids.contains(sourceDeviceId) || !ids.contains(targetDeviceId))
            throw new CpFailoverService.Refusal("MEMBER_SET_CHANGED");
        var decision = store.request(unit.members().get(0).clusterMemberRef().orElseThrow(), sourceDeviceId,
            targetDeviceId, actor);
        if (!JooqPanConfigSyncRepository.ADMITTED.equals(decision.code())) throw new CpFailoverService.Refusal(decision.code());
        return store.find(decision.runId()).orElseThrow(() -> new CpFailoverService.Refusal("CONFIG_SYNC_NOT_FOUND"));
    }

    public Optional<JooqPanConfigSyncRepository.ConfigSync> find(String syncId, String actor) {
        requireReader(actor);
        return store.find(syncId);
    }
}
