package com.securityexpert.nexus.ui2.service.failover;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private void requireOperator(String actor) {
        if (!allowed(actor, RoleToken.OPERATOR) && !allowed(actor, RoleToken.SECURITY_ADMIN))
            throw new Refusal("WRONG_ROLE");
    }

    public boolean mayApprove(String actor) { return allowed(actor, RoleToken.SECURITY_ADMIN); }
    public boolean mayStart(String actor) {
        return allowed(actor, RoleToken.OPERATOR) || mayApprove(actor);
    }
    public List<Unit> unitsForRef(String clusterRef, String actor) {
        requireOperator(actor);
        if (clusterRef == null || devices.listAll().stream().noneMatch(d -> d.clusterMemberRef().filter(clusterRef::equals).isPresent()))
            throw new Refusal("CLUSTER_NOT_FOUND");
        return units(opaque(clusterRef), actor);
    }

    /** By a member's opaque device id. */
    public List<Unit> unitsForMember(String memberDeviceId, String actor) {
        requireOperator(actor);
        String ref = memberDeviceId == null ? null : devices.listAll().stream()
            .filter(d -> d.deviceId().equals(memberDeviceId)).findFirst()
            .flatMap(DeviceSummaryRecord::clusterMemberRef).orElse(null);
        if (ref == null) throw new Refusal("CLUSTER_NOT_FOUND");
        return units(opaque(ref), actor);
    }

    public List<Unit> units(String clusterId, String actor) {
        requireOperator(actor);
        List<String> clusters = devices.listAll().stream().map(DeviceSummaryRecord::clusterMemberRef)
            .flatMap(Optional::stream).distinct().filter(ref -> opaque(ref).equals(clusterId)).toList();
        if (clusters.size() != 1) throw new Refusal("CLUSTER_NOT_FOUND");
        String cluster=clusters.get(0);
        List<DeviceSummaryRecord> members=devices.findMembersByClusterRef(cluster);
        if (members.size()!=2 || members.stream().anyMatch(m -> !"check_point".equals(m.vendorHint())
                || devices.find(m.deviceId()).filter(d -> d.permitsReadCollection()).isEmpty()
                || devices.findEndpointByDeviceId(m.deviceId()).isEmpty()))
            throw new Refusal("CLUSTER_NOT_ELIGIBLE");
        members=members.stream().sorted(Comparator.comparing(DeviceSummaryRecord::deviceId)).toList();
        List<DeviceSummaryRecord> pair=members;
        if (pair.stream().anyMatch(m -> inventory.findLatestRun(m.deviceId()).isEmpty()))
            throw new Refusal("INVENTORY_REQUIRED");
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
        return units(clusterId,actor).stream().filter(u -> u.id().equals(unitId)).findFirst()
            .orElseThrow(() -> new Refusal("UNIT_NOT_FOUND"));
    }

    public JooqCpFailoverRepository.Approval approve(String clusterId, String unitId, Instant from,
            Instant until, String reason, String actor) {
        requireAdmin(actor);
        Unit unit=unit(clusterId,unitId,actor);
        if (from==null || until==null || !until.isAfter(from) || !until.isAfter(Instant.now())
                || reason==null || reason.isBlank() || reason.length()>500) throw new Refusal("INVALID_WINDOW");
        return store.createApproval(unit.members().get(0).clusterMemberRef().orElseThrow(), unit.vsId(),
            from,until,reason,actor);
    }
    public List<JooqCpFailoverRepository.Approval> approvals(String clusterId, String unitId, String actor) {
        Unit u=unit(clusterId,unitId,actor);
        return store.approvals(u.members().get(0).clusterMemberRef().orElseThrow(),u.vsId());
    }
    public boolean revoke(String approvalId,String actor) { requireAdmin(actor); return store.revoke(approvalId,actor); }

    /** Latest independent member observations are only an admission filter; the worker rechecks live. */
    public String request(String clusterId,String unitId,Instant scheduledFor,String actor) {
        requireOperator(actor);
        Unit u=unit(clusterId,unitId,actor);
        Instant now=Instant.now();
        Instant when=scheduledFor==null ? now : scheduledFor;
        if (when.isBefore(now.minusSeconds(2))) throw new Refusal("OUTSIDE_WINDOW");
        String a=observedRole(u.members().get(0),u.vsId());
        String b=observedRole(u.members().get(1),u.vsId());
        if (!("ACTIVE".equals(a) && "STANDBY".equals(b)
                || "STANDBY".equals(a) && "ACTIVE".equals(b))) throw new Refusal("CLUSTER_STATE_NOT_READY");
        for (DeviceSummaryRecord member:u.members()) requireTrusted(member);
        try {
            var decision=store.request(u.members().get(0).clusterMemberRef().orElseThrow(),u.vsId(),
                when,actor,u.members().get(0).deviceId(),scheduledFor==null);
            if (!"ADMITTED".equals(decision.code())) throw new Refusal(decision.code());
            return decision.runId();
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new Refusal("RUN_ALREADY_ACTIVE");
        } catch (org.jooq.exception.DataAccessException duplicate) {
            if ("23505".equals(duplicate.sqlState())) throw new Refusal("RUN_ALREADY_ACTIVE");
            throw duplicate;
        }
    }
    private String observedRole(DeviceSummaryRecord member,String vsId) {
        return inventory.findLatestRun(member.deviceId()).stream().flatMap(r -> r.haFacts().stream())
            .filter(f -> (vsId==null ? "physical" : vsId).equals(f.context()))
            .filter(f -> InventoryHaFact.SOURCE_CP_CPHAPROB_STAT.equals(f.source()))
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
        Unit u=unit(clusterId,unitId,actor);
        return store.runs(u.members().get(0).clusterMemberRef().orElseThrow(),u.vsId());
    }
    public Optional<JooqCpFailoverRepository.Detail> detail(String runId,String actor) {
        requireOperator(actor);
        return store.detail(runId);
    }
    public Optional<String> memberName(String memberId) {
        return devices.findSummary(memberId).flatMap(DeviceSummaryRecord::observedHostname);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public void startDue() {
        for (var run: store.due()) {
            List<DeviceSummaryRecord> members=devices.findMembersByClusterRef(run.clusterRef());
            if (members.size()!=2 || !("ACTIVE".equals(observedRole(members.get(0),run.vsId()))
                    && "STANDBY".equals(observedRole(members.get(1),run.vsId()))
                    || "STANDBY".equals(observedRole(members.get(0),run.vsId()))
                    && "ACTIVE".equals(observedRole(members.get(1),run.vsId())))) {
                store.stopPlanned(run.id(),"CLUSTER_STATE_NOT_READY");
                continue;
            }
            try { members.forEach(this::requireTrusted); }
            catch (Refusal refused) {
                store.stopPlanned(run.id(),refused.code());
                continue;
            }
            store.startDue(run.id(),members.get(0).deviceId());
        }
    }
}
