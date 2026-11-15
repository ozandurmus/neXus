package com.securityexpert.nexus.ui2.service.supportbundle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.supportbundle.AsaSupportBundle;
import com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundle;
import com.securityexpert.nexus.ui2.jobs.supportbundle.FgtSupportBundle;
import com.securityexpert.nexus.ui2.jobs.supportbundle.PanSupportBundle;
import com.securityexpert.nexus.ui2.jobs.supportbundle.SupportBundleKind;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemSpec;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * Support bundle requests (design sections 5.1, 5.7, 5.8; PO decisions 2026-11-15). A request is one device and a list of
 * catalogue kinds; the browser and the CLI never send a command. Admission goes through the shared job admission
 * (an administrator's manual job: never held to a window, business-hours confirmation applies, also against the
 * chosen time of an "AT" request, PO Q1).
 */
public final class SupportBundleService {

    public static final int MIN_REASON_LENGTH = 8;
    public static final int MAX_REASON_LENGTH = 500;
    public static final int PER_DEVICE_CAP = 3;
    public static final int MAX_ITEMS = 32;
    public static final long FLEET_BYTES_CAP = 50L * 1024 * 1024 * 1024;
    public static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(7);
    public static final Duration TTL = Duration.ofDays(14);
    static final Pattern CASE_REF = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    public sealed interface Outcome {
        record Accepted(UUID requestId, String jobId, boolean mdsWarning) implements Outcome {
        }

        /** {@code status} is the HTTP status the controller answers with. */
        record Refused(int status, String code, String reason) implements Outcome {
        }
    }

    private final DeviceRepository devices;
    private final JobAdmissionService admission;
    private final JooqSupportBundleStore store;
    private final Clock clock;
    private final BusinessHours businessHours;

    public SupportBundleService(DeviceRepository devices, JobAdmissionService admission, JooqSupportBundleStore store) {
        this(devices, admission, store, Clock.systemUTC(), BusinessHours.SYSTEM);
    }

    SupportBundleService(DeviceRepository devices, JobAdmissionService admission, JooqSupportBundleStore store, Clock clock,
            BusinessHours businessHours) {
        this.devices = devices; this.admission = admission; this.store = store; this.clock = clock;
        this.businessHours = businessHours;
    }

    /** The job capability and the platform scope of the gate rows a device is collected with (one session, one job per device). */
    record VendorPlan(String capability, String platformRole) {
    }

    /**
     * Platform enablement per vendor (design 5.2): Check Point gateways (S2) and management servers and Multi-Domain Servers
     * (S6, heavy items only; PO Q5), Palo Alto firewalls and Panoramas (S3), Cisco ASA (S4) and FortiGate (S5). A FortiManager
     * is not enabled.
     */
    static Optional<VendorPlan> planFor(String vendor, String role) {
        boolean gateway = "gateway".equals(role);
        boolean management = "management_server".equals(role);
        if (vendor == null) return Optional.empty();
        return switch (vendor) {
            case "check_point" -> gateway ? Optional.of(new VendorPlan(CpSupportBundle.CAPABILITY, CpSupportBundle.PLATFORM_ROLE_SCOPE))
                    : management ? Optional.of(new VendorPlan(CpSupportBundle.CAPABILITY, CpSupportBundle.MANAGEMENT_SCOPE))
                    : Optional.empty();
            case "palo_alto" -> gateway ? Optional.of(new VendorPlan(PanSupportBundle.CAPABILITY, PanSupportBundle.FIREWALL_SCOPE))
                    : management ? Optional.of(new VendorPlan(PanSupportBundle.CAPABILITY, PanSupportBundle.PANORAMA_SCOPE))
                    : Optional.empty();
            case "cisco_asa" -> gateway ? Optional.of(new VendorPlan(AsaSupportBundle.CAPABILITY, AsaSupportBundle.PLATFORM_ROLE_SCOPE))
                    : Optional.empty();
            case "fortinet" -> gateway ? Optional.of(new VendorPlan(FgtSupportBundle.CAPABILITY, FgtSupportBundle.PLATFORM_ROLE_SCOPE))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** The catalogue rows of a vendor, for the new-request form: id, load, default tick, whether it can be requested yet. */
    public static List<SupportBundleKind> catalogue(String vendor) {
        return SupportBundleKind.forVendor(vendor);
    }

    public Outcome request(String deviceId, List<String> kindIds, Optional<Instant> runAt, String caseRef, String reason,
            String actor) {
        if (reason == null || reason.strip().length() < MIN_REASON_LENGTH || reason.strip().length() > MAX_REASON_LENGTH) {
            return new Outcome.Refused(400, "REASON_REQUIRED", "A reason of 8 to 500 characters is required");
        }
        if (caseRef != null && !caseRef.isBlank() && !CASE_REF.matcher(caseRef).matches()) {
            return new Outcome.Refused(400, "INVALID_CASE_REF", "The case reference may use letters, digits, - and _ (40 at most)");
        }
        Optional<DeviceRecord> device = devices.find(deviceId);
        if (device.isEmpty()) return new Outcome.Refused(404, "DEVICE_NOT_FOUND", "No device with that identifier");
        String vendor = device.get().vendorHint();
        Optional<VendorPlan> plan = planFor(vendor, device.get().role());
        if (plan.isEmpty()) {
            boolean knownVendor = java.util.Set.of("check_point", "palo_alto", "cisco_asa", "fortinet").contains(vendor);
            boolean role = "gateway".equals(device.get().role()) || "management_server".equals(device.get().role());
            if (!knownVendor) return new Outcome.Refused(409, "VENDOR_NOT_YET_ENABLED", "Support bundles for this vendor are not built yet");
            if (!role) return new Outcome.Refused(409, "ROLE_UNRECOGNISED", "Only gateways and management servers can be collected");
            // The light Check Point items are gated for gateways in S2 (an MDS and a management server come with their gate
            // rows); a FortiManager is not a FortiGate.
            return new Outcome.Refused(409, "PLATFORM_NOT_YET_ENABLED",
                    "Support bundle items for this kind of management server are not enabled yet");
        }
        if (kindIds == null || kindIds.isEmpty()) return new Outcome.Refused(400, "NO_ITEMS", "Pick at least one item");
        Set<SupportBundleKind> kinds = new LinkedHashSet<>();
        for (String id : kindIds) {
            Optional<SupportBundleKind> kind = SupportBundleKind.fromId(id);
            if (kind.isEmpty() || !kind.get().vendor().equals(vendor)) {
                return new Outcome.Refused(400, "INVALID_KIND", "Unknown item for this device");
            }
            if (!kind.get().enabled()) {
                return new Outcome.Refused(409, "KIND_NOT_AVAILABLE", "That item is not available yet");
            }
            if ("management_server".equals(device.get().role()) && "check_point".equals(vendor) && !kind.get().onCheckPointManagement()) {
                return new Outcome.Refused(409, "KIND_NOT_AVAILABLE", "That item is not available on a management server");
            }
            kinds.add(kind.get());
        }
        // The optional cpdata_collector run runs only when the presence read found the tool, so the presence read is always part of it.
        if (kinds.contains(SupportBundleKind.CP_CPDATA_COLLECTOR)) kinds.add(SupportBundleKind.CPDATA_COLLECTOR_PRESENT);
        boolean recoveryWrite = kinds.stream().anyMatch(SupportBundleKind::recoveryWrite);
        boolean checkPointManagement = "check_point".equals(vendor) && "management_server".equals(device.get().role());
        boolean heavyOnManagement = checkPointManagement && kinds.stream().anyMatch(SupportBundleKind::heavy);
        Instant now = clock.instant();
        Instant when = now;
        String mode = "NOW";
        if (runAt.isPresent()) {
            when = runAt.get();
            mode = "AT";
            if (when.isBefore(now.minusSeconds(60)) || when.isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
                return new Outcome.Refused(400, "RUN_AT_OUT_OF_RANGE", "Pick a time within the next 7 days");
            }
            if (when.isBefore(now)) when = now;
            // PO 2026-11-15 (Q1): the business-hours confirmation applies against the chosen time too.
            var grant = ManualAdmission.currentFor(actor);
            if (businessHours.inside(when) && !(grant.isPresent() && grant.get().acknowledged())) {
                throw new ManualAdmission.BusinessHoursConfirmRequired(businessHours);
            }
        }
        if (store.liveRequestCount(deviceId) >= PER_DEVICE_CAP) {
            var oldest = store.oldestLive(deviceId);
            return new Outcome.Refused(409, "DEVICE_BUNDLE_QUOTA_EXCEEDED", "This device already has " + PER_DEVICE_CAP
                    + " live bundles; delete one first" + oldest.map(o -> " (oldest id " + o.id() + ")").orElse(""));
        }
        if (store.liveBundleBytes() >= FLEET_BYTES_CAP) {
            return new Outcome.Refused(409, "FLEET_BUNDLE_QUOTA_EXCEEDED", "The fleet support bundle storage quota is used up");
        }

        UUID id = UUID.randomUUID();
        List<ItemSpec> specs = new ArrayList<>();
        // A VSX gateway: HCP run and cpinfo run once per virtual system of the stored inventory (C10, C14); never a guessed id.
        List<Integer> virtualSystems = !checkPointManagement && "check_point".equals(vendor)
                && kinds.stream().anyMatch(SupportBundleKind::perVirtualSystem) ? store.virtualSystemIds(deviceId) : List.of();
        for (SupportBundleKind k : kinds.stream().sorted(java.util.Comparator.comparingInt(SupportBundleKind::order)).toList()) {
            if (k.perVirtualSystem() && !virtualSystems.isEmpty()) virtualSystems.forEach(vs -> specs.add(new ItemSpec(k.id(), vs)));
            else specs.add(new ItemSpec(k.id(), null));
        }
        if (specs.size() > MAX_ITEMS) {
            return new Outcome.Refused(409, "TOO_MANY_ITEMS", "This request would need " + specs.size() + " items; at most " + MAX_ITEMS
                    + " are allowed (a VSX gateway runs each heavy item once per virtual system)");
        }
        RequestRow row = new RequestRow(id, deviceId, vendor, plan.get().platformRole(), mode, when,
                caseRef == null || caseRef.isBlank() ? null : caseRef, reason.strip(), actor, now, null, null,
                when.plus(TTL), null, null, 0, null, null, null, "{}", "QUEUED", null);
        try {
            store.insert(row, specs);
        } catch (RuntimeException e) {
            if (String.valueOf(e.getMessage()).contains("uq_support_bundle_active_per_device")
                    || String.valueOf(e.getCause()).contains("uq_support_bundle_active_per_device")) {
                return new Outcome.Refused(409, "SUPPORT_BUNDLE_ALREADY_ACTIVE",
                        "This device already has a support bundle queued or running");
            }
            throw e;
        }
        AdmissionResult result;
        try {
            // BW-2 (PO 14K): a request holding a recovery-write gate is class 1, as the MDS export is; reads only stay class 0.
            result = admission.submit(plan.get().capability(), deviceId, "support-bundle:" + id, actor,
                    ActionRegistry.SUPPORT_BUNDLE_REQUEST, recoveryWrite ? ActionClass.CLASS_1_RECOVERY_WRITE : null);
        } catch (RuntimeException refused) {
            store.failQueued(id, actor, "ADMISSION_REFUSED");
            throw refused;
        }
        return switch (result) {
            case AdmissionResult.Admitted admitted -> accepted(id, admitted.jobId(), mode, when, actor, heavyOnManagement);
            case AdmissionResult.Deduplicated deduplicated -> accepted(id, deduplicated.jobId(), mode, when, actor, heavyOnManagement);
            case AdmissionResult.Refused refused -> {
                store.failQueued(id, actor, refused.code());
                yield new Outcome.Refused(409, refused.code(), refused.reason());
            }
        };
    }

    /** PO Q5: the heavy items are included for a management server or MDS; the response says so plainly (no block). */
    public static final String MDS_WARNING = "This is a Check Point management server or Multi-Domain Server. HCP and cpinfo are heavy "
            + "on it, and a Multi-Domain Server has crashed under a heavy backup before. The items were queued as requested; "
            + "run them outside business hours and watch the server.";

    private Outcome accepted(UUID id, String jobId, String mode, Instant when, String actor, boolean mdsWarning) {
        store.attachJob(id, jobId, actor);
        if ("AT".equals(mode)) store.deferJob(jobId, when, actor);
        return new Outcome.Accepted(id, jobId, mdsWarning);
    }

    /** Cancels a queued request (and its job). A running request is not stopped in this step. */
    public boolean cancel(UUID id, String actor) {
        return store.cancelQueued(id, actor);
    }

    public boolean delete(UUID id, String actor, String reason) {
        return store.markDeleted(id, actor, reason);
    }
}
