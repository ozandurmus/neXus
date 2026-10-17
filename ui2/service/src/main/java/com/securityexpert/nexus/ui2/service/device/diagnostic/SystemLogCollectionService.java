package com.securityexpert.nexus.ui2.service.device.diagnostic;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.diagnostic.PanSystemLogCollect;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSystemLogCollect;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * System-log programme phase B admission (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md sections 3.1 and 14): admits one
 * collection job per enrolled Palo Alto firewall ({@code pan_system_log_collect}, never Panorama) and per enrolled
 * Check Point gateway ({@code cp_system_log_collect}, never a management server) through the shared job windows.
 *
 * <ul>
 *   <li><b>Manual start</b> ({@link #start}): one device or every supported device, only inside a window
 *       ({@code OUTSIDE_JOB_WINDOW} otherwise), with no stagger.</li>
 *   <li><b>Scheduled start</b> ({@link #admitDue}): each device is due {@code hash(device_id) mod 40} minutes into the
 *       window, which spreads the load and keeps clear of window-start readiness and policy jobs.</li>
 * </ul>
 *
 * Both use the idempotency key {@code syslog-collect:<device>:<slot>}, so a window holds at most one collection job per
 * device whichever way it starts (the P3/P4 gate rows allow at most 4 pages per firewall per window; C1 and C3 at most
 * once per device per window).
 */
@Service
public final class SystemLogCollectionService {
    public record StartOutcome(int targets, int admitted, int deduplicated, int refused, List<String> refusalCodes) {
    }

    /** Section 3.1: the start of a device inside its window is offset by hash(device_id) mod this many minutes. */
    public static final int STAGGER_MINUTES = 40;
    /** Vendor to collection capability. */
    static final Map<String, String> CAPABILITY_BY_VENDOR = Map.of("palo_alto", PanSystemLogCollect.CAPABILITY,
            "check_point", CpSystemLogCollect.CAPABILITY);

    private final DeviceRepository devices;
    private final JobAdmissionService admission;
    private final JobWindowPolicy windows;

    @org.springframework.beans.factory.annotation.Autowired
    public SystemLogCollectionService(DeviceRepository devices, JobAdmissionService admission) {
        this(devices, admission, JobWindowPolicy.SYSTEM);
    }

    SystemLogCollectionService(DeviceRepository devices, JobAdmissionService admission, JobWindowPolicy windows) {
        this.devices = devices; this.admission = admission; this.windows = windows;
    }

    /**
     * Every enrolled, enabled Palo Alto firewall reached over the XML API and every Check Point gateway reached over SSH
     * (management servers -- Panorama, MDS -- are never listed; a Gaia Embedded device is admitted and its executor
     * records UNSUPPORTED, so the outcome stays visible).
     */
    List<DeviceSummaryRecord> targets() {
        var pan = devices.listReadCollectionTargets("pan_xml_api").stream()
                .filter(d -> "palo_alto".equals(d.vendorHint()) && "gateway".equals(d.role()));
        var cp = devices.listReadCollectionTargets("ssh_exec").stream()
                .filter(d -> "check_point".equals(d.vendorHint()) && "gateway".equals(d.role()));
        return java.util.stream.Stream.concat(pan, cp).toList();
    }

    /** Minutes into the window at which a device is due; never at or beyond the end of a short window. */
    public static int staggerMinute(String deviceId, int windowMinutes) {
        return Math.floorMod(deviceId.hashCode(), Math.max(1, Math.min(STAGGER_MINUTES, windowMinutes)));
    }

    /** Manual start ({@code syslog-collect-start [deviceId]}); throws {@link JobWindowPolicy.OutsideWindow} outside a window. */
    public StartOutcome start(Optional<String> deviceId, String actor) {
        windows.requireOpen();
        Instant now = windows.now();
        if (deviceId.isPresent()) {
            String id = deviceId.get();
            Optional<DeviceRecord> device = devices.find(id);
            if (device.isEmpty()) return refused("DEVICE_NOT_FOUND");
            if ("management_server".equals(device.get().role())) return refused("SYSTEM_LOG_DISABLED_ON_MANAGEMENT");
            String capability = CAPABILITY_BY_VENDOR.get(device.get().vendorHint());
            if (capability == null || !"gateway".equals(device.get().role())) return refused("SYSTEM_LOG_VENDOR_UNSUPPORTED");
            return tally(1, List.of(submit(capability, id, now, actor)));
        }
        var targets = targets();
        return tally(targets.size(), targets.stream().map(d -> submit(CAPABILITY_BY_VENDOR.get(d.vendorHint()), d.deviceId(), now, actor)).toList());
    }

    /**
     * One scheduler tick: admits every target whose stagger offset has been reached in the current window. Safe to
     * call every minute and after a restart: a device already admitted in this slot resolves to its existing job.
     */
    public StartOutcome admitDue(Instant now, String actor) {
        if (!windows.isOpen(now)) return new StartOutcome(0, 0, 0, 0, List.of());
        long minutesIn = Duration.between(windows.slotStart(now).toInstant(), now).toMinutes();
        var due = targets().stream()
                .filter(d -> staggerMinute(d.deviceId(), windows.windowMinutes()) <= minutesIn).toList();
        return tally(due.size(), due.stream().map(d -> submit(CAPABILITY_BY_VENDOR.get(d.vendorHint()), d.deviceId(), now, actor)).toList());
    }

    private AdmissionResult submit(String capability, String deviceId, Instant now, String actor) {
        String slot = String.valueOf(windows.slotStart(now).toEpochSecond());
        return admission.submit(capability, deviceId, "syslog-collect:" + deviceId + ":" + slot, actor,
                ActionRegistry.SYSTEM_LOG_COLLECT_START);
    }

    private static StartOutcome refused(String code) {
        return new StartOutcome(1, 0, 0, 1, List.of(code));
    }

    private static StartOutcome tally(int targets, List<AdmissionResult> results) {
        int admitted = 0, deduplicated = 0;
        var refusals = new TreeSet<String>();
        for (AdmissionResult result : results) {
            switch (result) {
                case AdmissionResult.Admitted ignored -> admitted++;
                case AdmissionResult.Deduplicated ignored -> deduplicated++;
                case AdmissionResult.Refused refused -> refusals.add(refused.code());
            }
        }
        return new StartOutcome(targets, admitted, deduplicated, results.size() - admitted - deduplicated, List.copyOf(refusals));
    }
}
