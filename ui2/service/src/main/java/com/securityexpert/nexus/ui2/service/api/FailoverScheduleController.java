package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverActionKind;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleRecord;
import com.securityexpert.nexus.ui2.service.failover.FailoverScheduleLedger;
import com.securityexpert.nexus.ui2.service.failover.FailoverScheduleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST Controller for Phase D: Scheduled Maintenance Window Failovers.
 * Strictly adheres to the AIView masking law: raw hardware identifiers are never returned.
 */
@RestController
@RequestMapping("/api/v2/failover")
public class FailoverScheduleController {

    public record CreateSchedulePayload(
        String actionKind,
        String windowStart,
        String windowEnd,
        int maxStartDelayMinutes,
        String requesterId,
        String approverId,
        String reason,
        String clientNonce
    ) {}

    public record CancelSchedulePayload(
        String operatorId,
        String reason
    ) {}

    private final FailoverScheduleService scheduleService;
    private final FailoverScheduleLedger scheduleLedger;

    public FailoverScheduleController(
        FailoverScheduleService scheduleService,
        FailoverScheduleLedger scheduleLedger
    ) {
        this.scheduleService = Objects.requireNonNull(scheduleService, "scheduleService must not be null");
        this.scheduleLedger = Objects.requireNonNull(scheduleLedger, "scheduleLedger must not be null");
    }

    @PostMapping("/{clusterRef}/schedules")
    public ResponseEntity<?> createSchedule(
        @PathVariable String clusterRef,
        @RequestBody CreateSchedulePayload payload
    ) {
        try {
            FailoverActionKind actionKind = FailoverActionKind.valueOf(payload.actionKind().toUpperCase());
            Instant start = Instant.parse(payload.windowStart());
            Instant end = Instant.parse(payload.windowEnd());

            FailoverScheduleService.ScheduleWindowRequest req = new FailoverScheduleService.ScheduleWindowRequest(
                clusterRef, actionKind, start, end, payload.maxStartDelayMinutes(),
                payload.requesterId(), payload.approverId(), payload.reason(), payload.clientNonce()
            );

            FailoverScheduleRecord record = scheduleService.scheduleMaintenanceWindow(req);
            return ResponseEntity.status(HttpStatus.CREATED).body(record);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "ADMISSION_REFUSED", "message", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "SERVER_ERROR", "message", ex.getMessage()));
        }
    }

    @GetMapping("/{clusterRef}/schedules")
    public ResponseEntity<List<FailoverScheduleRecord>> getSchedulesForCluster(@PathVariable String clusterRef) {
        return ResponseEntity.ok(scheduleService.getSchedulesForCluster(clusterRef));
    }

    @GetMapping("/schedules/{scheduleId}")
    public ResponseEntity<?> getScheduleDetails(@PathVariable String scheduleId) {
        return scheduleService.getSchedule(scheduleId)
            .map(record -> {
                List<FailoverScheduleLedger.TransitionEntry> history = scheduleLedger.getTransitionsForSchedule(scheduleId);
                return ResponseEntity.ok(Map.of("schedule", record, "transitions", history));
            })
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND", "message", "Schedule not found: " + scheduleId)));
    }

    @PostMapping("/schedules/{scheduleId}/cancel")
    public ResponseEntity<?> cancelSchedule(
        @PathVariable String scheduleId,
        @RequestBody CancelSchedulePayload payload
    ) {
        try {
            FailoverScheduleRecord record = scheduleService.cancelSchedule(scheduleId, payload.operatorId(), payload.reason());
            return ResponseEntity.ok(record);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "STATE_CONFLICT", "message", ex.getMessage()));
        }
    }

    @PostMapping("/schedules/{scheduleId}/trigger")
    public ResponseEntity<?> triggerScheduleExecution(
        @PathVariable String scheduleId,
        @RequestParam(defaultValue = "OPERATOR_DISPATCH") String actor
    ) {
        try {
            FailoverScheduleRecord record = scheduleService.dispatchScheduledExecution(scheduleId, actor);
            return ResponseEntity.ok(record);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "EXECUTION_BLOCKED", "message", ex.getMessage()));
        }
    }
}
