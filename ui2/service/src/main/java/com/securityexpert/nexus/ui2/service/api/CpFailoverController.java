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
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/** Opaque-unit API for the Check Point failover screen. */
@RestController
@RequestMapping("/api/v2/cp-failover")
public final class CpFailoverController {
    public record ApprovalRequest(String clusterId, String unitId, Instant windowFrom, Instant windowUntil, String reason) {}
    public record RunRequest(String clusterId, String unitId, Instant scheduledFor) {}
    private final CpFailoverService service;
    public CpFailoverController(CpFailoverService service) { this.service=service; }
    private static String actor(HttpServletRequest request) {
        return (String)request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
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
        m.put("steps",List.of("PRECHECK","FAILING_OVER","SWITCHED","POSTCHECK","RETURNING","DONE"));
        return m;
    }
    private static ResponseEntity<?> result(java.util.function.Supplier<Object> action) {
        try { return ResponseEntity.ok(action.get()); }
        catch (CpFailoverService.Refusal refused) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code",refused.code()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST"));
        }
    }
    @GetMapping("/units/{clusterId}")
    public ResponseEntity<?> units(@PathVariable String clusterId,HttpServletRequest request) {
        return result(() -> service.units(clusterId,actor(request)).stream()
            .map(u -> Map.of("unitId",u.id(),"clusterId",u.clusterId(),"label",u.label())).toList());
    }
    @GetMapping("/units")
    public ResponseEntity<?> unitsForRef(@RequestParam String clusterRef,HttpServletRequest request) {
        return result(() -> service.unitsForRef(clusterRef,actor(request)).stream()
            .map(u -> Map.of("unitId",u.id(),"clusterId",u.clusterId(),"label",u.label(),
                "canApprove",service.mayApprove(actor(request)),"canStart",service.mayStart(actor(request)),
                "canSchedule",service.mayStart(actor(request)))).toList());
    }
    @PostMapping("/approvals")
    public ResponseEntity<?> approve(@RequestBody ApprovalRequest body,HttpServletRequest request) {
        return result(() -> approval(service.approve(body.clusterId(),body.unitId(),body.windowFrom(),
            body.windowUntil(),body.reason(),actor(request))));
    }
    @GetMapping("/approvals")
    public ResponseEntity<?> approvals(@RequestParam String clusterId,@RequestParam String unitId,HttpServletRequest request) {
        return result(() -> service.approvals(clusterId,unitId,actor(request)).stream().map(CpFailoverController::approval).toList());
    }
    @PostMapping("/approvals/{approvalId}/revoke")
    public ResponseEntity<?> revoke(@PathVariable String approvalId,HttpServletRequest request) {
        return result(() -> Map.of("revoked",service.revoke(approvalId,actor(request))));
    }
    @PostMapping("/runs")
    public ResponseEntity<?> start(@RequestBody RunRequest body,HttpServletRequest request) {
        return result(() -> Map.of("runId",service.request(body.clusterId(),body.unitId(),body.scheduledFor(),actor(request))));
    }
    @GetMapping("/runs")
    public ResponseEntity<?> runs(@RequestParam String clusterId,@RequestParam String unitId,HttpServletRequest request) {
        return result(() -> service.runs(clusterId,unitId,actor(request)).stream().map(CpFailoverController::run).toList());
    }
    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> detail(@PathVariable String runId,HttpServletRequest request) {
        return result(() -> service.detail(runId,actor(request)).map(d -> {
            Map<String,Object> view=run(d.run());
            view.put("checks",d.checks().stream().map(c -> Map.of(
                "phase",c.phase(),"member", service.maskedMember(c.memberRef(),d.run().clusterRef()),
                "checkNo",c.checkNo(),"status",c.status(),"derived",c.derived(),
                "observedAt",c.observedAt())).toList());
            return view;
        }).orElseThrow(() -> new CpFailoverService.Refusal("RUN_NOT_FOUND")));
    }
}
