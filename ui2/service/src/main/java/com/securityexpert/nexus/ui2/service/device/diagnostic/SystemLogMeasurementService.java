package com.securityexpert.nexus.ui2.service.device.diagnostic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.diagnostic.PanSyslogMeasure;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSpikeLayoutReview;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogMeasurementRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * System-log programme phase A, M3/M4 (DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4; PO chat approval 2026-10-15):
 * admits one {@code pan_syslog_measure} job per enrolled Palo Alto firewall (never Panorama) and reads back the
 * recorded summaries (Palo Alto from {@code pan_syslog_measure}, Check Point from the numeric summary of each
 * {@code cp_syslog_measure_*} diagnostic read). Admission goes through the shared job windows; a second start in the same window resolves to
 * the job already admitted for that firewall.
 */
@Service
public final class SystemLogMeasurementService {
    public record BulkOutcome(int enrolledFirewalls, int admitted, int deduplicated, int refused, List<String> refusalCodes) {
    }

    private static final TypeReference<Map<String, Object>> SUMMARY = new TypeReference<>() { };
    private final DeviceRepository devices;
    private final JobAdmissionService admission;
    private final SystemLogMeasurementRepository measurements;
    private final JobWindowPolicy windows;
    private final ObjectMapper json = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public SystemLogMeasurementService(DeviceRepository devices, JobAdmissionService admission,
            SystemLogMeasurementRepository measurements) {
        this(devices, admission, measurements, JobWindowPolicy.SYSTEM);
    }

    SystemLogMeasurementService(DeviceRepository devices, JobAdmissionService admission,
            SystemLogMeasurementRepository measurements, JobWindowPolicy windows) {
        this.devices = devices; this.admission = admission; this.measurements = measurements; this.windows = windows;
    }

    /** Every enrolled, enabled Palo Alto firewall reached over the XML API. */
    List<DeviceSummaryRecord> targets() {
        return devices.listReadCollectionTargets("pan_xml_api").stream()
                .filter(d -> "palo_alto".equals(d.vendorHint()) && "gateway".equals(d.role())).toList();
    }

    /** Window-bound for every role, as {@link SystemLogCollectionService#start}: measurement frequency is set per window. */
    public BulkOutcome startAll(String actor) {
        return ManualAdmission.withoutGrant(() -> startAllInsideWindow(actor));
    }

    private BulkOutcome startAllInsideWindow(String actor) {
        windows.requireOpen();
        String slot = String.valueOf(windows.slotStart(windows.now()).toEpochSecond());
        int admitted = 0, deduplicated = 0;
        var refusals = new java.util.TreeSet<String>();
        var targets = targets();
        for (DeviceSummaryRecord firewall : targets) {
            AdmissionResult result = admission.submit(PanSyslogMeasure.CAPABILITY, firewall.deviceId(),
                    "syslog-measure:" + firewall.deviceId() + ":" + slot, actor, ActionRegistry.SYSTEM_LOG_MEASURE_START);
            switch (result) {
                case AdmissionResult.Admitted ignored -> admitted++;
                case AdmissionResult.Deduplicated ignored -> deduplicated++;
                case AdmissionResult.Refused refused -> refusals.add(refused.code());
            }
        }
        return new BulkOutcome(targets.size(), admitted, deduplicated, targets.size() - admitted - deduplicated,
                List.copyOf(refusals));
    }

    /** The admission of one Spike Detective layout review: {@code ADMITTED}/{@code DEDUPLICATED} with a job, or a refusal code. */
    public record ReviewOutcome(String outcome, String jobId, String code) {
        static ReviewOutcome refused(String code) { return new ReviewOutcome("REFUSED", null, code); }
    }

    /**
     * The one-off Spike Detective layout review (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 8.3; PO approval
     * 2026-10-19): admits ONE {@code cp_spike_layout_review} job for one Check Point gateway, chosen by its opaque
     * device id, inside a job window ({@link JobWindowPolicy.OutsideWindow} otherwise). The idempotency key
     * {@code spike-layout-review:<device>:<slot>} holds a window to one job per device; a device that already has a
     * MEASURED review row is refused ({@code SPIKE_LAYOUT_ALREADY_REVIEWED}), so a successful review is never repeated.
     */
    public ReviewOutcome startSpikeLayoutReview(String deviceId, String actor) {
        // PO 2026-10-20: a one-off review an administrator starts is not held to the window (acknowledgement inside
        // business hours instead); the slot-keyed idempotency still holds one job per device per slot.
        windows.requireOpenFor(actor);
        if (deviceId == null || deviceId.isBlank()) return ReviewOutcome.refused("DEVICE_ID_REQUIRED");
        var device = devices.find(deviceId);
        if (device.isEmpty()) return ReviewOutcome.refused("DEVICE_NOT_FOUND");
        if (!"check_point".equals(device.get().vendorHint())) return ReviewOutcome.refused("SPIKE_REVIEW_VENDOR_UNSUPPORTED");
        if ("management_server".equals(device.get().role())) return ReviewOutcome.refused("SYSTEM_LOG_DISABLED_ON_MANAGEMENT");
        if (!"gateway".equals(device.get().role())) return ReviewOutcome.refused("SPIKE_REVIEW_VENDOR_UNSUPPORTED");
        boolean reviewed = measurements.list(Optional.of(deviceId), Optional.of("check_point"),
                Optional.of(CpSpikeLayoutReview.SOURCE), 1000).stream()
                .anyMatch(m -> "MEASURED".equals(summary(m.summaryJson()).get("status")));
        if (reviewed) return ReviewOutcome.refused("SPIKE_LAYOUT_ALREADY_REVIEWED");
        String slot = String.valueOf(windows.slotStart(windows.now()).toEpochSecond());
        AdmissionResult result = admission.submit(CpSpikeLayoutReview.CAPABILITY, deviceId,
                "spike-layout-review:" + deviceId + ":" + slot, actor, ActionRegistry.SYSTEM_LOG_MEASURE_START);
        return switch (result) {
            case AdmissionResult.Admitted admitted -> new ReviewOutcome("ADMITTED", admitted.jobId(), null);
            case AdmissionResult.Deduplicated deduplicated -> new ReviewOutcome("DEDUPLICATED", deduplicated.jobId(), null);
            case AdmissionResult.Refused refused -> ReviewOutcome.refused(refused.code());
        };
    }

    /** Newest first. Hostnames and cluster references go out raw here and are masked for aiview by the response advice. */
    public List<Map<String, Object>> list(Optional<String> deviceId, int limit) {
        return list(deviceId, Optional.empty(), Optional.empty(), limit);
    }

    /** Check Point rows are numeric summaries (sizes, times, counts, name classes): no identity beyond the device row. */
    public List<Map<String, Object>> list(Optional<String> deviceId, Optional<String> vendor, Optional<String> source,
            int limit) {
        Map<String, DeviceSummaryRecord> byId = new java.util.HashMap<>();
        devices.listAll().forEach(d -> byId.put(d.deviceId(), d));
        return measurements.list(deviceId.filter(id -> !id.isBlank()), vendor.filter(v -> !v.isBlank()),
                source.filter(v -> !v.isBlank()), limit).stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            var device = Optional.ofNullable(byId.get(m.deviceId()));
            row.put("measurement_id", m.measurementId());
            row.put("device_id", m.deviceId());
            row.put("hostname", device.flatMap(DeviceSummaryRecord::observedHostname).orElse(null));
            row.put("cluster_member_ref", device.flatMap(DeviceSummaryRecord::clusterMemberRef).orElse(null));
            row.put("vendor", m.vendor());
            row.put("source", m.source());
            row.put("measured_at", m.measuredAt().toString());
            row.put("job_id", m.jobId());
            row.put("summary", summary(m.summaryJson()));
            return row;
        }).toList();
    }

    private Map<String, Object> summary(String text) {
        try {
            return json.readValue(text, SUMMARY);
        } catch (java.io.IOException unreadable) {
            return Map.of("status", "UNKNOWN", "reason", "SUMMARY_UNREADABLE");
        }
    }
}
