package com.securityexpert.nexus.ui2.service.failover;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;
import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/** Server-owned approval and admission decisions; HTTP never supplies a command or device target. */
@Service
public final class CpFailoverService {
    public record Unit(String id, String clusterId, String label, String vsId, List<DeviceSummaryRecord> members) {}
    public record Summary(Unit unit, String vendor, boolean activeWindow, String lastRunState,
            String lastRunOutcome, Instant lastRunAt) {}
    private record StatusKey(String clusterRef, String vsId, String vendor) {}
    public static final class Refusal extends RuntimeException {
        private final String code;
        public Refusal(String code) { super(code); this.code=code; }
        public String code() { return code; }
    }

    private final DeviceRepository devices;
    private final DeviceInventoryRepository inventory;
    private final JooqCpFailoverRepository store;
    private final RbacEvaluator rbac;
    private final ManagementEndpointSshTrustRepository trust;

    public CpFailoverService(DeviceRepository devices, DeviceInventoryRepository inventory,
            JooqCpFailoverRepository store, RbacEvaluator rbac, ManagementEndpointSshTrustRepository trust) {
        this.devices=devices; this.inventory=inventory; this.store=store; this.rbac=rbac;
        this.trust=trust;
    }

    private static String opaque(String source) {
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }
    private boolean allowed(String actor, String role) {
        return actor != null && rbac.evaluate(actor, Optional.of(role), Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }
    private void requireAdmin(String actor) {
        if (!allowed(actor, RoleToken.SECURITY_ADMIN)) throw new Refusal("WRONG_ROLE");
    }
    private void requireReader(String actor) {
        if (!allowed(actor, RoleToken.OPERATOR) && !allowed(actor, RoleToken.SECURITY_ADMIN)
                && !allowed(actor, RoleToken.VIEWER))
            throw new Refusal("WRONG_ROLE");
    }

    private void requireOperator(String actor) {
        if (!allowed(actor, RoleToken.OPERATOR) && !allowed(actor, RoleToken.SECURITY_ADMIN))
            throw new Refusal("WRONG_ROLE");
    }

    public boolean mayApprove(String actor) { return allowed(actor, RoleToken.SECURITY_ADMIN); }
    public boolean mayStart(String actor) {
        return allowed(actor, RoleToken.OPERATOR) || mayApprove(actor);
    }
    public List<Summary> summary(String actor) {
        requireReader(actor);
        Map<String, List<JooqCpFailoverRepository.SummaryMember>> facts = store.summaryMembers().stream()
            .collect(Collectors.groupingBy(JooqCpFailoverRepository.SummaryMember::deviceId));
        Map<StatusKey, JooqCpFailoverRepository.SummaryStatus> statuses = store.summaryStatuses().stream()
            .collect(Collectors.toMap(s -> new StatusKey(s.clusterRef(), s.vsId(), s.vendor()), s -> s));
        Map<String, List<DeviceSummaryRecord>> clusters = devices.listAll().stream()
            .filter(d -> d.clusterMemberRef().isPresent() && ("check_point".equals(d.vendorHint())
                || "palo_alto".equals(d.vendorHint())))
            .collect(Collectors.groupingBy(d -> d.clusterMemberRef().orElseThrow()));
        return clusters.entrySet().stream().filter(e -> e.getValue().size() == 2)
            .flatMap(e -> {
                List<DeviceSummaryRecord> pair = e.getValue().stream()
                    .sorted(Comparator.comparing(DeviceSummaryRecord::deviceId)).toList();
                String vendor = pair.get(0).vendorHint();
                if (!vendor.equals(pair.get(1).vendorHint()) || pair.stream().anyMatch(m -> {
                    List<JooqCpFailoverRepository.SummaryMember> rows = facts.get(m.deviceId());
                    return rows == null || rows.isEmpty() || !rows.get(0).readable()
                        || rows.get(0).transportKind() == null || !rows.get(0).inventoryPresent()
                        || ("palo_alto".equals(vendor) && (!"pan_xml_api".equalsIgnoreCase(rows.get(0).transportKind())
                            || !rows.get(0).panSupported()));
                })) return java.util.stream.Stream.<Summary>empty();
                List<String> common = facts.get(pair.get(0).deviceId()).stream()
                    .map(JooqCpFailoverRepository.SummaryMember::context)
                    .filter(c -> c != null && !"physical".equals(c) && c.matches("[0-9]{1,10}"))
                    .filter(c -> facts.get(pair.get(1).deviceId()).stream().anyMatch(r -> c.equals(r.context())))
                    .distinct().sorted().toList();
                boolean vsx = "check_point".equals(vendor) && pair.stream().anyMatch(m ->
                    m.virtualSystems().filter(v -> !v.isBlank()).isPresent() || facts.get(m.deviceId()).stream()
                        .anyMatch(r -> r.context() != null && !"physical".equals(r.context())));
                if (vsx && common.isEmpty()) return java.util.stream.Stream.<Summary>empty();
                List<Unit> units = "palo_alto".equals(vendor)
                    ? List.of(new Unit(opaque(e.getKey()), opaque(e.getKey()), e.getKey(), null, pair))
                    : namedUnits(e.getKey(), pair, common);
                return units.stream().map(u -> {
                    var status = statuses.get(new StatusKey(e.getKey(), u.vsId(), vendor));
                    return new Summary(u, vendor, status != null && status.activeWindow(),
                        status == null ? null : status.state(), status == null ? null : status.outcome(),
                        status == null ? null : status.scheduledFor());
                });
            }).toList();
    }
    public List<Unit> unitsForRef(String clusterRef, String actor) {
        return unitsForRef(clusterRef,actor,"check_point");
    }
    public List<Unit> unitsForRef(String clusterRef, String actor, String vendor) {
        requireReader(actor);
        if (clusterRef == null || devices.listAll().stream().noneMatch(d -> d.clusterMemberRef().filter(clusterRef::equals).isPresent()))
            throw new Refusal("CLUSTER_NOT_FOUND");
        return units(opaque(clusterRef), actor, vendor);
    }

    /** By a member's opaque device id. */
    public List<Unit> unitsForMember(String memberDeviceId, String actor) {
        return unitsForMember(memberDeviceId,actor,"check_point");
    }
    public List<Unit> unitsForMember(String memberDeviceId, String actor, String vendor) {
        requireReader(actor);
        String ref = memberDeviceId == null ? null : devices.listAll().stream()
            .filter(d -> d.deviceId().equals(memberDeviceId)).findFirst()
            .flatMap(DeviceSummaryRecord::clusterMemberRef).orElse(null);
        if (ref == null) throw new Refusal("CLUSTER_NOT_FOUND");
        return units(opaque(ref), actor, vendor);
    }

    public List<Unit> units(String clusterId, String actor) {
        return units(clusterId,actor,"check_point");
    }
    public List<Unit> units(String clusterId, String actor, String vendor) {
        requireReader(actor);
        List<String> clusters = devices.listAll().stream().map(DeviceSummaryRecord::clusterMemberRef)
            .flatMap(Optional::stream).distinct().filter(ref -> opaque(ref).equals(clusterId)).toList();
        if (clusters.size() != 1) throw new Refusal("CLUSTER_NOT_FOUND");
        String cluster=clusters.get(0);
        List<DeviceSummaryRecord> members=devices.findMembersByClusterRef(cluster);
        if (members.size()!=2 || members.stream().anyMatch(m -> !vendor.equals(m.vendorHint())
                || devices.find(m.deviceId()).filter(d -> d.permitsReadCollection()).isEmpty()
                || devices.findEndpointByDeviceId(m.deviceId()).isEmpty()))
            throw new Refusal("CLUSTER_NOT_ELIGIBLE");
        members=members.stream().sorted(Comparator.comparing(DeviceSummaryRecord::deviceId)).toList();
        List<DeviceSummaryRecord> pair=members;
        if (pair.stream().anyMatch(m -> inventory.findLatestRun(m.deviceId()).isEmpty()))
            throw new Refusal("INVENTORY_REQUIRED");
        if ("palo_alto".equals(vendor)) {
            if (pair.stream().anyMatch(m -> devices.findEndpointByDeviceId(m.deviceId())
                    .filter(e -> "pan_xml_api".equalsIgnoreCase(e.transportKind())).isEmpty()))
                throw new Refusal("CLUSTER_NOT_ELIGIBLE");
            boolean supported=pair.stream().allMatch(m -> inventory.findLatestRun(m.deviceId()).stream()
                .flatMap(r -> r.haFacts().stream())
                .anyMatch(f -> InventoryHaFact.SOURCE_PAN_HIGH_AVAILABILITY_STATE.equals(f.source())
                    && f.clusterMode().map(mode -> "active-passive".equalsIgnoreCase(mode)).orElse(false)));
            if (!supported) throw new Refusal("UNSUPPORTED_HA_MODE");
            return List.of(new Unit(clusterId,clusterId,cluster,null,pair));
        }
        List<String> common=inventory.findLatestRun(pair.get(0).deviceId()).stream()
            .flatMap(r -> r.contexts().stream()).map(c -> c.context())
            .filter(c -> !"physical".equals(c) && c.matches("[0-9]{1,10}"))
            .filter(vs -> inventory.findLatestRun(pair.get(1).deviceId()).stream()
                .flatMap(r -> r.contexts().stream()).anyMatch(c -> vs.equals(c.context())))
            .distinct().sorted().toList();
        boolean vsx=pair.stream().anyMatch(m -> m.virtualSystems().filter(v -> !v.isBlank()).isPresent()
            || inventory.findLatestRun(m.deviceId()).stream().flatMap(r -> r.contexts().stream())
                .anyMatch(c -> !"physical".equals(c.context())));
        if (vsx && common.isEmpty()) throw new Refusal("VSX_CONTEXT_INCOMPLETE");
        return namedUnits(cluster, pair, common);
    }
    private static List<Unit> namedUnits(String cluster, List<DeviceSummaryRecord> pair, List<String> common) {
        String clusterId = opaque(cluster);
        List<String> vsNames=pair.stream().flatMap(m -> m.virtualSystems().stream())
            .flatMap(names -> java.util.Arrays.stream(names.split(",\\s*")))
            .filter(name -> !name.isBlank()).distinct().sorted().toList();
        java.util.Map<String,String> namesByVs=new java.util.HashMap<>();
        for (String vs:common) vsNames.stream()
            .filter(name -> name.endsWith("(VSID " + vs + ")") || name.equals("VSID " + vs))
            .findFirst().ifPresent(name -> namesByVs.put(vs,name));
        List<String> unnamed=common.stream().filter(vs -> !namesByVs.containsKey(vs)).toList();
        List<String> remaining=vsNames.stream().filter(name -> !namesByVs.containsValue(name)).toList();
        if (unnamed.size()==remaining.size())
            for (int i=0;i<unnamed.size();i++) namesByVs.put(unnamed.get(i),remaining.get(i));
        if (!common.isEmpty()) return common.stream().map(vs -> new Unit(opaque(cluster+":"+vs), clusterId,
            namesByVs.getOrDefault(vs,vs), vs, pair)).toList();
        return List.of(new Unit(clusterId, clusterId, cluster, null, pair));
    }
    private Unit unit(String clusterId, String unitId, String actor) {
        return unit(clusterId,unitId,actor,"check_point");
    }
    private Unit unit(String clusterId, String unitId, String actor, String vendor) {
        return units(clusterId,actor,vendor).stream().filter(u -> u.id().equals(unitId)).findFirst()
            .orElseThrow(() -> new Refusal("UNIT_NOT_FOUND"));
    }

    public JooqCpFailoverRepository.Approval approve(String clusterId, String unitId, Instant from,
            Instant until, String reason, String actor) {
        return approve(clusterId,unitId,from,until,reason,actor,"check_point");
    }
    public JooqCpFailoverRepository.Approval approve(String clusterId, String unitId, Instant from,
            Instant until, String reason, String actor, String vendor) {
        requireAdmin(actor);
        Unit unit=unit(clusterId,unitId,actor,vendor);
        if (from==null || until==null || !until.isAfter(from) || !until.isAfter(Instant.now())
                || reason==null || reason.isBlank() || reason.length()>500) throw new Refusal("INVALID_WINDOW");
        if ("check_point".equals(vendor)) return store.createApproval(unit.members().get(0).clusterMemberRef().orElseThrow(),
            unit.vsId(),from,until,reason,actor);
        return store.createApproval(unit.members().get(0).clusterMemberRef().orElseThrow(), unit.vsId(),
            from,until,reason,actor,vendor);
    }
    public List<JooqCpFailoverRepository.Approval> approvals(String clusterId, String unitId, String actor) {
        return approvals(clusterId,unitId,actor,"check_point");
    }
    public List<JooqCpFailoverRepository.Approval> approvals(String clusterId, String unitId, String actor,String vendor) {
        Unit u=unit(clusterId,unitId,actor,vendor);
        return "check_point".equals(vendor)
            ?store.approvals(u.members().get(0).clusterMemberRef().orElseThrow(),u.vsId())
            :store.approvals(u.members().get(0).clusterMemberRef().orElseThrow(),u.vsId(),vendor);
    }
    public boolean revoke(String approvalId,String actor) { requireAdmin(actor); return store.revoke(approvalId,actor); }

    /** Latest independent member observations are only an admission filter; the worker rechecks live. */
    public String request(String clusterId,String unitId,Instant scheduledFor,String actor) {
        return request(clusterId,unitId,scheduledFor,actor,"check_point");
    }
    public String request(String clusterId,String unitId,Instant scheduledFor,String actor,String vendor) {
        requireOperator(actor);
        Unit u=unit(clusterId,unitId,actor,vendor);
        Instant now=Instant.now();
        Instant when=scheduledFor==null ? now : scheduledFor;
        if (when.isBefore(now.minusSeconds(2))) throw new Refusal("OUTSIDE_WINDOW");
        String a=observedRole(u.members().get(0),u.vsId(),vendor);
        String b=observedRole(u.members().get(1),u.vsId(),vendor);
        String passive="palo_alto".equals(vendor)?"PASSIVE":"STANDBY";
        if (!("ACTIVE".equals(a) && passive.equals(b)
                || passive.equals(a) && "ACTIVE".equals(b))) throw new Refusal("CLUSTER_STATE_NOT_READY");
        if ("check_point".equals(vendor)) for (DeviceSummaryRecord member:u.members()) requireTrusted(member);
        try {
            var decision="check_point".equals(vendor)
                ?store.request(u.members().get(0).clusterMemberRef().orElseThrow(),u.vsId(),
                    when,actor,u.members().get(0).deviceId(),scheduledFor==null)
                :store.request(u.members().get(0).clusterMemberRef().orElseThrow(),u.vsId(),
                    when,actor,u.members().get(0).deviceId(),scheduledFor==null,vendor);
            if (!"ADMITTED".equals(decision.code())) throw new Refusal(decision.code());
            return decision.runId();
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new Refusal("RUN_ALREADY_ACTIVE");
        } catch (org.jooq.exception.DataAccessException duplicate) {
            if ("23505".equals(duplicate.sqlState())) throw new Refusal("RUN_ALREADY_ACTIVE");
            throw duplicate;
        }
    }
    private String observedRole(DeviceSummaryRecord member,String vsId,String vendor) {
        return inventory.findLatestRun(member.deviceId()).stream().flatMap(r -> r.haFacts().stream())
            .filter(f -> (vsId==null ? "physical" : vsId).equals(f.context()))
            .filter(f -> ("palo_alto".equals(vendor) ? InventoryHaFact.SOURCE_PAN_HIGH_AVAILABILITY_STATE
                : InventoryHaFact.SOURCE_CP_CPHAPROB_STAT).equals(f.source()))
            .map(f -> f.role().toUpperCase(java.util.Locale.ROOT)).findFirst().orElse("UNKNOWN");
    }
    private void requireTrusted(DeviceSummaryRecord member) {
        var endpoint=devices.findEndpointByDeviceId(member.deviceId())
            .orElseThrow(() -> new Refusal("ENDPOINT_MISSING"));
        String address=endpoint.addressRef();
        int colon=address.lastIndexOf(':');
        String host=colon<0?address:address.substring(0,colon);
        int port=22;
        if(colon>=0) try { port=Integer.parseInt(address.substring(colon+1)); }
            catch(NumberFormatException invalid) { throw new Refusal("ENDPOINT_INVALID"); }
        try {
            if(trust.findActiveAlgorithms(host,port).isEmpty()) throw new Refusal("TRUSTED_HOST_KEY_REQUIRED");
        } catch(Refusal refusal) { throw refusal; }
        catch(RuntimeException unavailable) { throw new Refusal("TRUST_NOT_EVALUABLE"); }
    }
    public List<JooqCpFailoverRepository.Run> runs(String clusterId,String unitId,String actor) {
        return runs(clusterId,unitId,actor,"check_point");
    }
    public List<JooqCpFailoverRepository.Run> runs(String clusterId,String unitId,String actor,String vendor) {
        Unit u=unit(clusterId,unitId,actor,vendor);
        return "check_point".equals(vendor)
            ?store.runs(u.members().get(0).clusterMemberRef().orElseThrow(),u.vsId())
            :store.runs(u.members().get(0).clusterMemberRef().orElseThrow(),u.vsId(),vendor);
    }
    public Optional<JooqCpFailoverRepository.Detail> detail(String runId,String actor) {
        requireReader(actor);
        return store.detail(runId);
    }
    public Optional<String> memberName(String memberId) {
        return devices.findSummary(memberId).flatMap(DeviceSummaryRecord::observedHostname);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public void startDue() {
        for (var run: store.due()) {
            List<DeviceSummaryRecord> members=devices.findMembersByClusterRef(run.clusterRef());
            String vendor=run.vendor();
            String passive="palo_alto".equals(vendor)?"PASSIVE":"STANDBY";
            if (members.size()!=2 || members.stream().anyMatch(m -> !vendor.equals(m.vendorHint()))
                    || !("ACTIVE".equals(observedRole(members.get(0),run.vsId(),vendor))
                    && passive.equals(observedRole(members.get(1),run.vsId(),vendor))
                    || passive.equals(observedRole(members.get(0),run.vsId(),vendor))
                    && "ACTIVE".equals(observedRole(members.get(1),run.vsId(),vendor)))) {
                store.stopPlanned(run.id(),"CLUSTER_STATE_NOT_READY");
                continue;
            }
            try { if ("check_point".equals(vendor)) members.forEach(this::requireTrusted); }
            catch (Refusal refused) {
                store.stopPlanned(run.id(),refused.code());
                continue;
            }
            store.startDue(run.id(),members.get(0).deviceId());
        }
    }
}
