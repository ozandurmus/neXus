package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;
import com.securityexpert.nexus.ui2.service.failover.ReadinessCheckView;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;

/** Opaque-unit API for the Check Point and Palo Alto failover screens. */
@RestController
@RequestMapping({"/api/v2/cp-failover", "/api/v2/pan-failover"})
public final class CpFailoverController {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    public record ApprovalRequest(String clusterId, String unitId, Instant windowFrom, Instant windowUntil, String reason, String requestId) {}
    public record RunRequest(String clusterId, String unitId, Instant scheduledFor, String requestId, long revision, String executionNonce, boolean warningConfirmed) {}
    public record ReadinessRequest(String clusterId, String unitId) {}
    private final CpFailoverService service;
    private final com.securityexpert.nexus.ui2.persistence.TransactionBoundary projections;
    private final com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer names;
    private final com.securityexpert.nexus.ui2.service.failover.PanConfigSyncService configSync;
    public CpFailoverController(CpFailoverService service) { this(service,null,null,null); }
    public CpFailoverController(CpFailoverService service,
            com.securityexpert.nexus.ui2.persistence.TransactionBoundary projections,
            com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer names) {
        this(service,projections,names,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public CpFailoverController(CpFailoverService service,
            com.securityexpert.nexus.ui2.persistence.TransactionBoundary projections,
            com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer names,
            com.securityexpert.nexus.ui2.service.failover.PanConfigSyncService configSync) {
        this.service=service; this.projections=projections; this.names=names; this.configSync=configSync;
    }
    private Map<String,Object> approvalView(JooqCpFailoverRepository.Approval a,HttpServletRequest request) {
        Map<String,Object> view=approval(a);
        view.remove("approvedBy");
        if (projections!=null) new JooqCpFailoverRepository(projections).requestApproval(a.id()).ifPresent(binding -> {
            view.put("requestId",a.id()); view.put("revision",binding.revision());
            view.put("policy",binding.policy()); view.put("approved",a.approvedBy()!=null);
            boolean initiator=actor(request).equals(binding.initiatedBy())
                && !PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
            view.put("canStart",initiator && service.mayStart(actor(request)));
            view.put("canApprove",!initiator && service.mayApprove(actor(request))
                && JooqCpFailoverRepository.TWO_PERSON.equals(binding.policy()) && a.approvedBy()==null);
            if (initiator) view.put("executionNonce",binding.executionNonce());
        });
        return view;
    }
    private Map<String,Object> requestView(JooqCpFailoverRepository.RequestApproval binding,HttpServletRequest request) {
        Map<String,Object> view=approval(binding.approval());
        view.remove("approvedBy");
        view.put("requestId",binding.approval().id()); view.put("revision",binding.revision());
        view.put("policy",binding.policy()); view.put("approved",binding.approval().approvedBy()!=null);
        if (actor(request).equals(binding.initiatedBy()) && !PrivacyMaskingResponseBodyAdvice.isReplayViewer(request))
            view.put("executionNonce",binding.executionNonce());
        return view;
    }
    private static String actor(HttpServletRequest request) {
        return (String)request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }
    private static String vendor(HttpServletRequest request) {
        return request.getServletPath().startsWith("/api/v2/pan-failover") ? "palo_alto" : "check_point";
    }
    private static Map<String,Object> approval(JooqCpFailoverRepository.Approval a) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("approvalId",a.id()); m.put("windowFrom",a.from()); m.put("windowUntil",a.until());
        m.put("reason",a.reason()); m.put("approvedBy",a.approvedBy()); m.put("revokedAt",a.revokedAt());
        return m;
    }
    private static Map<String,Object> run(JooqCpFailoverRepository.Run r) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("runId",r.id()); m.put("approvalId",r.approvalId()); m.put("scheduledFor",r.scheduledFor());
        m.put("state",r.state()); m.put("step",r.step()); m.put("outcome",r.outcome());
        m.put("failedCheck",r.failedCheck()); m.put("message",r.message());
        m.put("kind",r.kind());
        m.put("steps",List.of("PRECHECK","FAILING_OVER","SWITCHED","POSTCHECK","RETURNING","DONE"));
        return m;
    }
    private static Map<String,Object> unit(CpFailoverService.Unit u) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("unitId",u.id()); m.put("clusterId",u.clusterId());
        m.put("cluster_member_ref",u.members().get(0).clusterMemberRef().orElseThrow());
        m.put("members", u.members().stream().sorted(java.util.Comparator.comparing(
            com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord::deviceId)).map(member -> {
                Map<String,Object> view = new LinkedHashMap<>();
                view.put("device_id", member.deviceId());
                view.put("hostname", member.observedHostname().orElse(null));
                view.put("ha_role", member.observedHaRole().orElse(null));
                return view;
            }).toList());
        if (u.vsId()!=null) m.put("virtual_system",u.label());
        // The chassis and every VS of a VSX cluster are separate units; the chassis is never offered a failover (§10).
        m.put("unitKind",u.vsxChassis() ? "VSX_CHASSIS" : u.vsId()!=null ? "VIRTUAL_SYSTEM" : "CLUSTER");
        if (u.vsxChassis()) m.put("failoverRefusal",CpFailoverService.CHASSIS_FAILOVER_NOT_APPROVED);
        return m;
    }
    /** {@code incidents} memoizes the open-incident lookup per cluster and member pair within one request. */
    private void eligibility(Map<String,Object> view,CpFailoverService.Unit u,
            JooqCpFailoverRepository.ReadinessStatus readiness,Map<String,Boolean> incidents) {
        // A READY run stores its outcome as the run message ("READY"): that is not a stop code, so it never refuses.
        String refusal=readiness==null ? "INSUFFICIENT_EVIDENCE"
            : "READY".equals(readiness.outcome()) ? null : readiness.stopCode();
        String mode="UNKNOWN";
        // Pseudonyms only for the masked (replay viewer) view; an operator sees the real cluster and member names
        // (PO 2026-10-21: the failover tab showed pseudonyms to security_admin).
        if (names!=null && Boolean.TRUE.equals(view.get("masked"))) {
            String cluster=u.members().get(0).clusterMemberRef().orElseThrow();
            view.put("maskedName",u.vsId()==null ? names.maskClusterName(cluster) : names.maskVirtualSystem(u.label(),cluster));
            view.put("maskedMembers",u.members().stream().map(member -> Map.of(
                "device_id",member.deviceId(),"maskedLabel",names.maskDeviceName(member.observedHostname().orElse("Unknown"),cluster,member.deviceId()),
                "ha_role",member.observedHaRole().orElse("UNKNOWN"))).toList());
        }
        String recovery="UNKNOWN";
        if (readiness!=null) try {
            // PO 2026-10-20 item 2: the unit shows Active Up only when both members reported it (check 15).
            java.util.List<String> observed=new java.util.ArrayList<>();
            for (var check:JSON.readTree(readiness.checks())) if (check.path("checkNo").asInt()==15)
                observed.add(check.path("derived").path("recoveryMode").asText());
            if (observed.contains("PRIMARY_UP")) recovery="PRIMARY_UP";
            else if (observed.size()==2 && observed.stream().allMatch("ACTIVE_UP"::equals)) recovery="ACTIVE_UP";
            for (var check:JSON.readTree(readiness.checks())) if (check.path("checkNo").asInt()==1) {
                var facts=check.path("derived");
                String observedMode=facts.path("mode").asText();
                if (java.util.Set.of("HA","VSLS","LOAD_SHARING","active-passive","active-active","UNKNOWN").contains(observedMode)) mode=observedMode;
                String reason=facts.path("reason").asText();
                if (java.util.Set.of("UNSUPPORTED_MODE","IDENTITY_NOT_RECORDED","UNSUPPORTED").contains(reason)) refusal=reason;
            }
        } catch (java.io.IOException invalid) { refusal="INSUFFICIENT_EVIDENCE"; }
        if (readiness!=null && !"READY".equals(readiness.outcome()) && (refusal==null || refusal.isBlank())) refusal="READINESS_NOT_READY";
        if (projections!=null) {
            String incidentKey=u.members().get(0).clusterMemberRef().orElseThrow()+"|"
                +u.members().get(0).deviceId()+"|"+u.members().get(1).deviceId();
            boolean incident=incidents.computeIfAbsent(incidentKey,key -> projections.inTransaction(db -> db.fetchOne(
                "select exists(select 1 from failover_quarantine where active and (cluster_ref={0} "
                    + "or quarantined_member_ids @> jsonb_build_array({1}::text) "
                    + "or quarantined_member_ids @> jsonb_build_array({2}::text))) as open",
                u.members().get(0).clusterMemberRef().orElseThrow(),u.members().get(0).deviceId(),u.members().get(1).deviceId())
                .get("open",Boolean.class)));
            if (incident) refusal="OPEN_INCIDENT";
        }
        view.put("mode",mode); view.put("recoveryMode",recovery);
        view.put("refusalReason",refusal);
        view.put("readinessObservedAt",readiness==null ? null : readiness.observedAt());
    }
    private static ResponseEntity<?> result(java.util.function.Supplier<Object> action) {
        try { return ResponseEntity.ok(action.get()); }
        catch (CpFailoverService.Refusal refused) {
            Map<String,Object> body = new LinkedHashMap<>();
            body.put("code", refused.code());
            if (refused.dispatchRef()!=null) body.put("dispatchRef", refused.dispatchRef());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST"));
        }
    }
    @GetMapping("/units/{clusterId}")
    public ResponseEntity<?> units(@PathVariable String clusterId,HttpServletRequest request) {
        return result(() -> service.units(clusterId,actor(request),vendor(request)).stream()
            .map(CpFailoverController::unit).toList());
    }
    @GetMapping("/units")
    public ResponseEntity<?> unitsForRef(@RequestParam(required = false) String clusterRef,
            @RequestParam(required = false) String memberDeviceId, HttpServletRequest request) {
        return result(() -> {
            String actor = actor(request);
            List<CpFailoverService.Unit> units = memberDeviceId != null
                ? service.unitsForMember(memberDeviceId,actor,vendor(request))
                : service.unitsForRef(clusterRef,actor,vendor(request));
            if (units.isEmpty()) return List.of();
            // Permissions and readiness are request-wide facts: read once, not once per unit.
            boolean canApprove = service.mayApprove(actor), canStart = service.mayStart(actor);
            boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
            String vendor = vendor(request);
            var readiness = service.unitReadiness(actor,units);
            Map<String,Boolean> incidents = new java.util.HashMap<>();
            return units.stream().map(u -> {
                Map<String,Object> m=unit(u);
                m.put("vendor",vendor);
                m.put("masked",masked);
                // Role facts; a unit the server never fails over says so in failoverRefusal (the VSX chassis), and every
                // approval or start for it is refused server-side whatever these flags say.
                m.put("canApprove",canApprove);
                m.put("canStart",canStart);
                m.put("canSchedule",canStart);
                eligibility(m,u,readiness.get(u.id()),incidents);
                putConfigSync(m,u,vendor,readiness.get(u.id()),masked,actor);
                return m;
            }).toList();
        });
    }
    @GetMapping("/summary")
    public ResponseEntity<?> summary(HttpServletRequest request) {
        Map<String,Boolean> incidents = new java.util.HashMap<>();
        return result(() -> service.summary(actor(request)).stream().map(s -> {
            Map<String,Object> m = unit(s.unit());
            m.put("vendor", s.vendor());
            m.put("masked", PrivacyMaskingResponseBodyAdvice.isReplayViewer(request));
            m.put("activeWindow", s.activeWindow());
            m.put("lastRunState", s.lastRunState());
            m.put("lastRunOutcome", s.lastRunOutcome());
            m.put("lastRunAt", s.lastRunAt());
            m.put("canRunReadiness", s.canRunReadiness());
            eligibility(m,s.unit(),s.readiness(),incidents);
            putConfigSync(m,s.unit(),s.vendor(),s.readiness(),PrivacyMaskingResponseBodyAdvice.isReplayViewer(request),actor(request));
            if (s.readiness() != null) {
                var readiness = s.readiness();
                try {
                    var rows=JSON.readTree(readiness.checks());
                    var members=new java.util.TreeSet<String>();
                    rows.forEach(row -> members.add(row.path("memberRef").asText()));
                    var ordered=List.copyOf(members);
                    rows.forEach(row -> {
                        var fields=ReadinessCheckView.fields(s.vendor(),row.path("checkNo").asInt(),
                            "Member "+(ordered.indexOf(row.path("memberRef").asText())+1),
                            row.path("status").asText(),row.path("derived"),readiness.observedAt());
                        ((com.fasterxml.jackson.databind.node.ObjectNode)row).setAll(
                            (com.fasterxml.jackson.databind.node.ObjectNode)JSON.valueToTree(fields));
                        ((com.fasterxml.jackson.databind.node.ObjectNode)row).put("device_id",row.path("memberRef").asText());
                        ((com.fasterxml.jackson.databind.node.ObjectNode)row).remove("memberRef");
                    });
                    m.put("readiness", Map.of("status", readiness.outcome(), "observedAt", readiness.observedAt(),
                        "failedCheck", readiness.failedCheck() == null ? "" : readiness.failedCheck(),
                        "stopCode", readiness.stopCode() == null ? "" : readiness.stopCode(),
                        "checks", JSON.convertValue(rows, List.class)));
                } catch (java.io.IOException invalidStoredJson) {
                    throw new IllegalStateException("Stored readiness checks are invalid", invalidStoredJson);
                }
            } else m.put("readiness", null);
            return m;
        }).toList());
    }
    @PostMapping("/approvals")
    public ResponseEntity<?> approve(@RequestBody ApprovalRequest body,HttpServletRequest request) {
        return result(() -> requestView(service.createApprovalRequest(body.requestId(),body.clusterId(),body.unitId(),body.windowFrom(),
            body.windowUntil(),body.reason(),actor(request),vendor(request)),request));
    }
    public record SecondApprovalRequest(String clusterId,String unitId,long revision) {}
    @PostMapping("/approvals/{requestId}/approve")
    public ResponseEntity<?> secondApproval(@PathVariable String requestId,@RequestBody SecondApprovalRequest body,
            HttpServletRequest request) {
        return result(() -> requestView(service.secondApproval(requestId,body.revision(),body.clusterId(),body.unitId(),
            actor(request),vendor(request)),request));
    }
    @GetMapping("/approvals")
    public ResponseEntity<?> approvals(@RequestParam String clusterId,@RequestParam String unitId,HttpServletRequest request) {
        return result(() -> service.approvals(clusterId,unitId,actor(request),vendor(request)).stream().map(a -> approvalView(a,request)).toList());
    }
    @PostMapping("/approvals/{approvalId}/revoke")
    public ResponseEntity<?> revoke(@PathVariable String approvalId,HttpServletRequest request) {
        return result(() -> Map.of("revoked",service.revoke(approvalId,actor(request))));
    }
    @PostMapping("/runs")
    public ResponseEntity<?> start(@RequestBody RunRequest body,HttpServletRequest request) {
        return result(() -> Map.of("runId",service.request(body.clusterId(),body.unitId(),body.scheduledFor(),actor(request),vendor(request),
            body.requestId(),body.revision(),body.executionNonce(),body.warningConfirmed())));
    }
    @PostMapping("/units/{unitId}/readiness")
    public ResponseEntity<?> readiness(@PathVariable String unitId, @RequestBody ReadinessRequest body,
            HttpServletRequest request) {
        return result(() -> {
            if (!unitId.equals(body.unitId())) throw new IllegalArgumentException("unit mismatch");
            return Map.of("runId", service.requestReadiness(body.clusterId(), unitId, actor(request), vendor(request)));
        });
    }
    /**
     * PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md §6: the unit's config-sync facts. Palo Alto units only; the server decides
     * whether the button is offered ({@code canRun}) and why it would refuse ({@code refusal}).
     */
    private void putConfigSync(Map<String,Object> view,CpFailoverService.Unit unit,String vendor,
            JooqCpFailoverRepository.ReadinessStatus readiness,boolean masked,String actor) {
        if (configSync==null || !"palo_alto".equals(vendor) || unit.vsId()!=null) return;
        var facts=configSync.view(unit,vendor,readiness,(String)view.get("refusalReason"),masked,
            !masked && configSync.mayRun(actor),configSync.latest(unit));
        if (facts!=null) view.put("configSync",configSyncView(facts));
    }
    private static Map<String,Object> configSyncView(com.securityexpert.nexus.ui2.service.failover.PanConfigSyncService.View v) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("offered",v.offered()); m.put("canRun",v.canRun()); m.put("refusal",v.refusal());
        m.put("sourceRef",v.sourceRef()); m.put("targetRef",v.targetRef());
        m.put("readinessObservedAt",v.readinessObservedAt());
        m.put("latest",v.latest()==null ? null : syncRecord(v.latest()));
        return m;
    }
    private static Map<String,Object> syncRecord(com.securityexpert.nexus.ui2.persistence.JooqPanConfigSyncRepository.ConfigSync s) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("syncId",s.id()); m.put("state",s.state()); m.put("outcome",s.outcome());
        m.put("deviceStatus",s.deviceStatus()); m.put("sourceRef",s.sourceRef()); m.put("targetRef",s.targetRef());
        m.put("requestedAt",s.requestedAt()); m.put("startedAt",s.startedAt()); m.put("finishedAt",s.finishedAt());
        m.put("mutationPossible",s.mutationPossible());
        m.put("readinessRunId",s.followupReadinessRunId()); m.put("readinessRefusal",s.followupRefusal());
        return m;
    }
    private static final java.util.Set<String> CONFIG_SYNC_FIELDS = java.util.Set.of("clusterId","sourceDeviceId","targetDeviceId");
    /**
     * PO decision 2026-10-24 (PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md §3.1): sync the running configuration from the
     * active member to the passive. The body names only the unit's cluster and the two members the caller saw; any
     * other field is refused. Palo Alto only: the Check Point prefix is unmapped at the gate chain and refused here too.
     */
    @PostMapping("/units/{unitId}/config-sync")
    public ResponseEntity<?> requestConfigSync(@PathVariable String unitId,
            @RequestBody(required = false) Map<String,Object> body, HttpServletRequest request) {
        return result(() -> {
            if (!"palo_alto".equals(vendor(request)) || configSync==null)
                throw new CpFailoverService.Refusal(com.securityexpert.nexus.ui2.service.failover.PanConfigSyncService.NOT_SUPPORTED);
            if (PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) throw new CpFailoverService.Refusal("WRONG_ROLE");
            if (body==null || !CONFIG_SYNC_FIELDS.equals(body.keySet())) throw new IllegalArgumentException("unexpected field");
            String clusterId=text(body,"clusterId"), source=text(body,"sourceDeviceId"), target=text(body,"targetDeviceId");
            if (clusterId==null || source==null || target==null) throw new IllegalArgumentException("missing field");
            return syncRecord(configSync.request(clusterId,unitId,source,target,actor(request),vendor(request)));
        });
    }
    @GetMapping("/config-syncs/{syncId}")
    public ResponseEntity<?> configSyncStatus(@PathVariable String syncId,HttpServletRequest request) {
        return result(() -> {
            if (!"palo_alto".equals(vendor(request)) || configSync==null)
                throw new CpFailoverService.Refusal(com.securityexpert.nexus.ui2.service.failover.PanConfigSyncService.NOT_SUPPORTED);
            return configSync.find(syncId,actor(request)).map(CpFailoverController::syncRecord)
                .orElseThrow(() -> new CpFailoverService.Refusal("CONFIG_SYNC_NOT_FOUND"));
        });
    }
    @GetMapping("/runs")
    public ResponseEntity<?> runs(@RequestParam String clusterId,@RequestParam String unitId,HttpServletRequest request) {
        return result(() -> service.runs(clusterId,unitId,actor(request),vendor(request)).stream().map(CpFailoverController::run).toList());
    }
    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> detail(@PathVariable String runId,HttpServletRequest request) {
        return result(() -> service.detail(runId,actor(request)).filter(d -> vendor(request).equals(d.run().vendor())).map(d -> {
            Map<String,Object> view=run(d.run());
            if (projections!=null) projections.inTransaction(db -> {
                var row=db.fetchOne("select mutation_possible from failover_run where run_id={0}",runId);
                view.put("mutationPossible",row==null ? null : row.get("mutation_possible",Boolean.class));
                var incident=db.fetchOne("select execution_id from failover_quarantine where execution_id={0} and active",runId);
                view.put("incidentRef",incident==null ? null : incident.get("execution_id",String.class));
                return null;
            });
            if ("check_point".equals(d.run().vendor()) && "FAILOVER".equals(d.run().kind()))
                view.put("resume",resumeView(d.run(),request));
            view.put("sessionContinuity","NOT_EVALUATED");
            view.put("lastConfirmedRoles",d.checks().stream().filter(c -> c.checkNo()==1 && "PASS".equals(c.status()))
                .collect(java.util.stream.Collectors.toMap(JooqCpFailoverRepository.Check::memberRef,c -> {
                    try {
                        String role=JSON.readTree(c.derived()).path("role").asText().toUpperCase(java.util.Locale.ROOT);
                        return java.util.Set.of("ACTIVE","STANDBY","PASSIVE","DOWN","SUSPENDED").contains(role)?role:"UNKNOWN";
                    } catch (java.io.IOException invalid) { return "UNKNOWN"; }
                },(earlier,later) -> later)));
            var members=d.checks().stream().map(JooqCpFailoverRepository.Check::memberRef).distinct().sorted().toList();
            view.put("checks",d.checks().stream().map(c -> {
                Map<String,Object> check=new LinkedHashMap<>();
                check.put("phase",c.phase()); check.put("device_id",c.memberRef());
                check.put("cluster_member_ref",d.run().clusterRef());
                check.put("hostname",null);
                try {
                    check.putAll(ReadinessCheckView.fields(d.run().vendor(),c.checkNo(),
                        "Member "+(members.indexOf(c.memberRef())+1),c.status(),JSON.readTree(c.derived()),c.observedAt()));
                } catch (java.io.IOException invalid) {
                    throw new IllegalStateException("Stored readiness checks are invalid",invalid);
                }
                check.put("checkNo",c.checkNo()); check.put("status",c.status());
                check.put("derived",c.derived()); check.put("observedAt",c.observedAt());
                return check;
            }).toList());
            return view;
        }).orElseThrow(() -> new CpFailoverService.Refusal("RUN_NOT_FOUND")));
    }
    /**
     * Contract §6.1: the resume block of a Check Point FAILOVER run. Every reader sees the state and the server's
     * eligibility verdict; only security_admin/operation_admin outside the masked view get {@code canResume} and
     * {@code canApprove}. Opaque member references only.
     */
    private Map<String,Object> resumeView(JooqCpFailoverRepository.Run run, HttpServletRequest request) {
        String actor=actor(request);
        boolean may=!PrivacyMaskingResponseBodyAdvice.isReplayViewer(request) && service.mayResume(actor);
        String refusal="STOPPED".equals(run.state()) ? service.resumeEligibility(run.id(),actor) : "RESUME_RUN_NOT_ELIGIBLE";
        Map<String,Object> view=new LinkedHashMap<>();
        view.put("eligible",refusal==null);
        view.put("refusal",refusal);
        view.put("canResume",may && refusal==null);
        view.put("businessHoursInside",service.insideBusinessHours());
        view.put("businessHours",service.businessHoursLabel());
        if (refusal==null) service.resumeFormerActive(run.id(),actor).ifPresent(member -> view.put("formerActiveRef",member));
        service.resumeOf(run.id(),actor).ifPresent(s -> {
            view.put("resumeId",s.id()); view.put("state",s.state()); view.put("step",s.step());
            view.put("outcome",s.outcome()); view.put("failedCheck",s.failedCheck()); view.put("message",s.message());
            view.put("policy",s.policy()); view.put("requestedAt",s.requestedAt()); view.put("expiresAt",s.expiresAt());
            view.put("businessHoursAcknowledged",s.businessHoursAcknowledged());
            view.put("approved",s.approvedBy()!=null);
            view.put("formerActiveRef",s.formerActiveRef()); view.put("peerRef",s.peerRef());
            view.put("canApprove",may && JooqCpFailoverRepository.TWO_PERSON.equals(s.policy())
                && JooqCpFailoverRepository.RESUME_PENDING.equals(s.state()) && !s.initiatedBy().equals(actor)
                && s.expiresAt().isAfter(Instant.now()));
        });
        return view;
    }
    private static final java.util.Set<String> RESUME_FIELDS = java.util.Set.of("expectedIncidentId","reason","businessHoursAck");
    private static final java.util.Set<String> RESUME_APPROVE_FIELDS = java.util.Set.of("resumeId","businessHoursAck");
    private static String text(Map<String,Object> body, String field) {
        Object value=body==null ? null : body.get(field);
        if (value!=null && !(value instanceof String)) throw new IllegalArgumentException(field+" must be text");
        return (String)value;
    }
    private static boolean flag(Map<String,Object> body, String field) {
        Object value=body==null ? null : body.get(field);
        if (value!=null && !(value instanceof Boolean)) throw new IllegalArgumentException(field+" must be a boolean");
        return Boolean.TRUE.equals(value);
    }
    /**
     * Contract §6.4: request "Resume return step" for a stopped Check Point run (route action
     * cp_failover_resume_return). The actor is the session; any field beyond the expected incident, the reason and the
     * business-hours acknowledgement (for example an actor or approver) is refused, not ignored.
     */
    @PostMapping("/runs/{runId}/resume-return")
    public ResponseEntity<?> resumeReturn(@PathVariable String runId,
            @RequestBody(required = false) Map<String,Object> body, HttpServletRequest request) {
        return result(() -> {
            if (body!=null && !RESUME_FIELDS.containsAll(body.keySet())) throw new IllegalArgumentException("unexpected field");
            if (!"check_point".equals(vendor(request))) throw new CpFailoverService.Refusal("RESUME_RUN_NOT_ELIGIBLE");
            var decision=service.resumeReturn(runId,text(body,"expectedIncidentId"),text(body,"reason"),
                flag(body,"businessHoursAck"),actor(request));
            Map<String,Object> view=new LinkedHashMap<>();
            view.put("runId",runId); view.put("resumeId",decision.runId());
            view.put("state",JooqCpFailoverRepository.RESUME_ADMITTED.equals(decision.code()) ? "QUEUED" : decision.code());
            return view;
        });
    }
    /**
     * Contract §5.1: the second administrator approves a pending two-person resume. The resume is named in the body
     * (the gate chain resolves one wildcard path segment per route).
     */
    @PostMapping("/runs/{runId}/resume-return/approve")
    public ResponseEntity<?> approveResume(@PathVariable String runId,
            @RequestBody(required = false) Map<String,Object> body, HttpServletRequest request) {
        return result(() -> {
            if (body!=null && !RESUME_APPROVE_FIELDS.containsAll(body.keySet())) throw new IllegalArgumentException("unexpected field");
            if (!"check_point".equals(vendor(request))) throw new CpFailoverService.Refusal("RESUME_RUN_NOT_ELIGIBLE");
            var decision=service.approveResume(runId,text(body,"resumeId"),flag(body,"businessHoursAck"),actor(request));
            return Map.of("runId",runId,"resumeId",decision.runId(),"state","QUEUED");
        });
    }
    /** Shown to every reader who cannot release (operation_admin, operator, viewer, aiview). */
    public static final String INCIDENT_ROLE_NOTE = "Only a security_admin can release a failover incident.";
    /**
     * Open failover incidents and terminal runs whose unresolved sends block every failover in the estate
     * (PO decision 2026-10-20 item 1). Opaque references only; names pass through the masking advice.
     */
    @GetMapping("/incidents")
    public ResponseEntity<?> incidents(HttpServletRequest request) {
        return result(() -> {
            String actor=actor(request);
            boolean masked=PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
            boolean canRelease=!masked && service.mayReleaseIncident(actor);
            List<Map<String,Object>> rows=service.incidents(actor).stream().map(v -> {
                var i=v.incident();
                Map<String,Object> m=new LinkedHashMap<>();
                m.put("incidentRef",i.runId()); m.put("clusterId",v.clusterId()); m.put("unitId",v.unitId());
                m.put("cluster_member_ref",i.clusterRef());
                if (names!=null && masked) m.put("maskedName",names.maskClusterName(i.clusterRef()));
                m.put("vendor",i.vendor()); m.put("reason",i.reason()); m.put("openedAt",i.openedAt());
                m.put("incidentActive",i.incidentActive()); m.put("blocksEstate",i.blocksEstate());
                m.put("unresolvedSends",i.unresolvedDispatchRefs().size());
                m.put("members",i.memberIds().stream().map(id -> Map.of("device_id",id)).toList());
                return m;
            }).toList();
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("canRelease",canRelease);
            body.put("roleNote",canRelease ? null : INCIDENT_ROLE_NOTE);
            body.put("incidents",rows);
            return body;
        });
    }
    /**
     * Release one run's incident (security_admin, route action cp_failover_incident_release). The body carries
     * the reason only: the actor is the session (GateChainInterceptor); any other field, such as a caller-named
     * approver, is refused rather than ignored.
     */
    @PostMapping("/runs/{runId}/incident/release")
    public ResponseEntity<?> releaseIncident(@PathVariable String runId,
            @RequestBody(required = false) Map<String,Object> body, HttpServletRequest request) {
        return result(() -> {
            if (body!=null && !java.util.Set.of("reason").containsAll(body.keySet()))
                throw new IllegalArgumentException("only a reason may be supplied");
            Object reason=body==null ? null : body.get("reason");
            if (reason!=null && !(reason instanceof String)) throw new IllegalArgumentException("reason must be text");
            return Map.of("incidentRef",runId,"status",service.releaseIncident(runId,(String)reason,actor(request)));
        });
    }
}
