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
    public CpFailoverController(CpFailoverService service) { this(service,null,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public CpFailoverController(CpFailoverService service,
            com.securityexpert.nexus.ui2.persistence.TransactionBoundary projections,
            com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer names) {
        this.service=service; this.projections=projections; this.names=names;
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
        return m;
    }
    private void eligibility(Map<String,Object> view,CpFailoverService.Unit u,
            JooqCpFailoverRepository.ReadinessStatus readiness) {
        String refusal=readiness==null ? "INSUFFICIENT_EVIDENCE" : readiness.stopCode();
        String mode="UNKNOWN";
        if (names!=null) {
            String cluster=u.members().get(0).clusterMemberRef().orElseThrow();
            view.put("maskedName",u.vsId()==null ? names.maskClusterName(cluster) : names.maskVirtualSystem(u.label(),cluster));
            view.put("maskedMembers",u.members().stream().map(member -> Map.of(
                "device_id",member.deviceId(),"maskedLabel",names.maskDeviceName(member.observedHostname().orElse("Unknown"),cluster),
                "ha_role",member.observedHaRole().orElse("UNKNOWN"))).toList());
        }
        if (readiness!=null) try {
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
            boolean incident=projections.inTransaction(db -> db.fetchOne(
                "select exists(select 1 from failover_quarantine where active and (cluster_ref={0} "
                    + "or quarantined_member_ids @> jsonb_build_array({1}::text) "
                    + "or quarantined_member_ids @> jsonb_build_array({2}::text))) as open",
                u.members().get(0).clusterMemberRef().orElseThrow(),u.members().get(0).deviceId(),u.members().get(1).deviceId())
                .get("open",Boolean.class));
            if (incident) refusal="OPEN_INCIDENT";
        }
        view.put("mode",mode); view.put("refusalReason",refusal);
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
        return result(() -> (memberDeviceId != null ? service.unitsForMember(memberDeviceId,actor(request),vendor(request))
                : service.unitsForRef(clusterRef,actor(request),vendor(request))).stream()
            .map(u -> {
                Map<String,Object> m=unit(u);
                m.put("vendor",vendor(request));
                m.put("masked",PrivacyMaskingResponseBodyAdvice.isReplayViewer(request));
                m.put("canApprove",service.mayApprove(actor(request)));
                m.put("canStart",service.mayStart(actor(request)));
                m.put("canSchedule",service.mayStart(actor(request)));
                var readiness=service.summary(actor(request)).stream().filter(s -> s.unit().id().equals(u.id()))
                    .map(CpFailoverService.Summary::readiness).filter(java.util.Objects::nonNull).findFirst().orElse(null);
                eligibility(m,u,readiness);
                return m;
            }).toList());
    }
    @GetMapping("/summary")
    public ResponseEntity<?> summary(HttpServletRequest request) {
        return result(() -> service.summary(actor(request)).stream().map(s -> {
            Map<String,Object> m = unit(s.unit());
            m.put("vendor", s.vendor());
            m.put("masked", PrivacyMaskingResponseBodyAdvice.isReplayViewer(request));
            m.put("activeWindow", s.activeWindow());
            m.put("lastRunState", s.lastRunState());
            m.put("lastRunOutcome", s.lastRunOutcome());
            m.put("lastRunAt", s.lastRunAt());
            m.put("canRunReadiness", s.canRunReadiness());
            eligibility(m,s.unit(),s.readiness());
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
}
