package com.securityexpert.nexus.ui2.service.supportbundle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
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
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemSpec;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * Support bundle requests (design sections 5.1, 5.7, 5.8, 5.13; PO decisions 2026-11-15 and 2026-11-26). A request is one
 * device and a list of catalogue kinds; the browser and the CLI never send a command. Admission goes through the shared job
 * admission (an administrator's manual job: never held to a window, business-hours confirmation applies, also against the
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
    public static final Duration QUIET_HOUR_MIN_LEAD = Duration.ofMinutes(5);
    static final Pattern CASE_REF = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    static final Pattern DOMAIN_REF = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * Preferred quiet start slots in {@link JobWindowPolicy#ZONE_ID} (PO 2026-11-26): avoids 00/06/12/18 collection windows,
     * 02:00 backups, ~03:00 device HCP runs, and 08:00-20:00 business hours; prefers 20:00-23:30 or 04:00.
     */
    public static final List<LocalTime> QUIET_SLOTS = List.of(
            LocalTime.of(4, 0),
            LocalTime.of(20, 0),
            LocalTime.of(21, 0),
            LocalTime.of(22, 0),
            LocalTime.of(23, 0),
            LocalTime.of(23, 30));

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
    private final JobWindowPolicy jobWindows;

    public SupportBundleService(DeviceRepository devices, JobAdmissionService admission, JooqSupportBundleStore store) {
        this(devices, admission, store, Clock.systemUTC(), BusinessHours.SYSTEM);
    }

    SupportBundleService(DeviceRepository devices, JobAdmissionService admission, JooqSupportBundleStore store, Clock clock,
            BusinessHours businessHours) {
        this.devices = devices; this.admission = admission; this.store = store; this.clock = clock;
        this.businessHours = businessHours;
        this.jobWindows = new JobWindowPolicy(clock, 60, businessHours);
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

    /** Next quiet-hour start instant in {@link JobWindowPolicy#ZONE_ID}, at least {@link #QUIET_HOUR_MIN_LEAD} ahead of {@code now}. */
    public static Instant nextQuietHour(Instant now) {
        Instant minStart = now.plus(QUIET_HOUR_MIN_LEAD);
        LocalDate baseDay = now.atZone(JobWindowPolicy.ZONE_ID).toLocalDate();
        for (int dayOffset = 0; dayOffset <= 2; dayOffset++) {
            LocalDate day = baseDay.plusDays(dayOffset);
            for (LocalTime slot : QUIET_SLOTS) {
                Instant candidate = ZonedDateTime.of(day, slot, JobWindowPolicy.ZONE_ID).toInstant();
                if (!candidate.isBefore(minStart)) {
                    return candidate;
                }
            }
        }
        return ZonedDateTime.of(baseDay.plusDays(1), LocalTime.of(4, 0), JobWindowPolicy.ZONE_ID).toInstant();
    }

    /**
     * Plain sentence for {@code NOT_STARTED_DEVICE_BUSY} built strictly from closed hold-reason codes and {@code finishedAt};
     * {@code null} for every other outcome. Never contains raw hostnames, addresses, actor identifiers, or free text.
     */
    public static String notStartedSummary(RequestRow r) {
        if (r == null || !"NOT_STARTED_DEVICE_BUSY".equals(r.outcome())) return null;
        List<String> labels = new ArrayList<>();
        for (String code : JooqSupportBundleStore.canonicalHoldReasons(r.holdReasons())) {
            switch (code) {
                case JooqSupportBundleStore.HOLD_MODULE_DRAIN -> labels.add("a module drain");
                case JooqSupportBundleStore.HOLD_COLLECTION_WINDOW -> labels.add("a collection window");
                case JooqSupportBundleStore.HOLD_DEVICE_JOB_RUNNING -> labels.add("another job on the device");
                case JooqSupportBundleStore.HOLD_HA_PEER_RUNNING -> labels.add("a bundle running on its HA peer");
                case JooqSupportBundleStore.HOLD_FLEET_CAP -> labels.add("the fleet heavy-bundle limit");
                default -> { /* ignore unknown token */ }
            }
        }
        String expiredAt = r.finishedAt() != null ? "expired at " + r.finishedAt() : "expired after 3 hours";
        if (labels.isEmpty()) {
            return "Did not start: device remained busy for 3 hours; " + expiredAt + ".";
        }
        return "Did not start: held by " + joinNatural(labels) + "; " + expiredAt + ".";
    }

    private static String joinNatural(List<String> parts) {
        if (parts.size() == 1) return parts.get(0);
        if (parts.size() == 2) return parts.get(0) + " and " + parts.get(1);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + ", and " + parts.get(parts.size() - 1);
    }

    public boolean isModuleDraining() {
        return store.isSupportBundleModuleDraining();
    }

    public boolean isCollectionWindowOpen() {
        return jobWindows.isOpen(clock.instant());
    }

    public Optional<Instant> currentCollectionWindowEnd() {
        Instant now = clock.instant();
        if (!jobWindows.isOpen(now)) return Optional.empty();
        return Optional.of(jobWindows.slotStart(now).plusMinutes(jobWindows.windowMinutes()).toInstant());
    }

    public Outcome request(String deviceId, List<String> kindIds, Optional<Instant> runAt, String caseRef, String reason,
            String actor) {
        return request(deviceId, kindIds, runAt, caseRef, reason, actor, null, null, null);
    }

    public Outcome request(String deviceId, List<String> kindIds, Optional<Instant> runAt, String caseRef, String reason,
            String actor, UUID previousRequestId, List<Integer> requestedVsIds, String domainRef) {
        if (reason == null || reason.strip().length() < MIN_REASON_LENGTH || reason.strip().length() > MAX_REASON_LENGTH) {
            return new Outcome.Refused(400, "REASON_REQUIRED", "A reason of 8 to 500 characters is required");
        }
        if (caseRef != null && !caseRef.isBlank() && !CASE_REF.matcher(caseRef).matches()) {
            return new Outcome.Refused(400, "INVALID_CASE_REF", "The case reference may use letters, digits, - and _ (40 at most)");
        }
        String cleanDomainRef = domainRef == null || domainRef.isBlank() ? null : domainRef.strip();
        if (cleanDomainRef != null && !DOMAIN_REF.matcher(cleanDomainRef).matches()) {
            return new Outcome.Refused(400, "INVALID_DOMAIN_REF", "The domain reference may use letters, digits, ., - and _ (64 at most)");
        }
        Optional<DeviceRecord> device = devices.find(deviceId);
        if (device.isEmpty()) return new Outcome.Refused(404, "DEVICE_NOT_FOUND", "No device with that identifier");
        Optional<RequestRow> previous = Optional.empty();
        if (previousRequestId != null) {
            previous = store.find(previousRequestId);
            if (previous.isEmpty()) {
                return new Outcome.Refused(404, "PREVIOUS_REQUEST_NOT_FOUND", "No support bundle with that previous_request_id");
            }
            if (!deviceId.equals(previous.get().deviceId())) {
                return new Outcome.Refused(400, "PREVIOUS_DEVICE_MISMATCH", "previous_request_id belongs to a different device");
            }
        }
        String vendor = device.get().vendorHint();
        Optional<VendorPlan> plan = planFor(vendor, device.get().role());
        if (plan.isEmpty()) {
            boolean knownVendor = java.util.Set.of("check_point", "palo_alto", "cisco_asa", "fortinet").contains(vendor);
            boolean role = "gateway".equals(device.get().role()) || "management_server".equals(device.get().role());
            if (!knownVendor) return new Outcome.Refused(409, "VENDOR_NOT_YET_ENABLED", "Support bundles for this vendor are not built yet");
            if (!role) return new Outcome.Refused(409, "ROLE_UNRECOGNISED", "Only gateways and management servers can be collected");
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
        List<Integer> virtualSystems = List.of();
        if (!checkPointManagement && "check_point".equals(vendor) && kinds.stream().anyMatch(SupportBundleKind::perVirtualSystem)) {
            List<Integer> storedVs = store.virtualSystemIds(deviceId);
            if (requestedVsIds != null && !requestedVsIds.isEmpty()) {
                Set<Integer> allowedFromPrevious = previous.map(p -> store.items(p.id()).stream()
                        .map(ItemRow::vsId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()))
                        .orElse(Set.of());
                List<Integer> validated = new ArrayList<>();
                for (Integer vs : requestedVsIds) {
                    if (vs == null || vs < 0 || vs > 4095
                            || (!storedVs.contains(vs) && !allowedFromPrevious.contains(vs))) {
                        return new Outcome.Refused(400, "INVALID_VS_ID", "Virtual system id is not in stored inventory for this gateway");
                    }
                    if (!validated.contains(vs)) validated.add(vs);
                }
                virtualSystems = validated.stream().sorted().toList();
            } else {
                virtualSystems = storedVs;
            }
        } else if (requestedVsIds != null && !requestedVsIds.isEmpty()) {
            return new Outcome.Refused(400, "INVALID_VS_ID", "Virtual system ids apply only to Check Point gateway heavy items");
        }
        final List<Integer> chosenVs = virtualSystems;
        for (SupportBundleKind k : kinds.stream().sorted(java.util.Comparator.comparingInt(SupportBundleKind::order)).toList()) {
            if (k.perVirtualSystem() && !chosenVs.isEmpty()) {
                chosenVs.forEach(vs -> specs.add(new ItemSpec(k.id(), vs, cleanDomainRef)));
            } else {
                specs.add(new ItemSpec(k.id(), null, cleanDomainRef));
            }
        }
        if (specs.size() > MAX_ITEMS) {
            return new Outcome.Refused(409, "TOO_MANY_ITEMS", "This request would need " + specs.size() + " items; at most " + MAX_ITEMS
                    + " are allowed (a VSX gateway runs each heavy item once per virtual system)");
        }
        List<String> initialHolds = !when.isAfter(now.plusSeconds(60))
                ? store.currentHoldReasons(deviceId, jobWindows.isOpen(when))
                : (jobWindows.isOpen(when) ? List.of(JooqSupportBundleStore.HOLD_COLLECTION_WINDOW) : List.of());
        RequestRow row = new RequestRow(id, deviceId, vendor, plan.get().platformRole(), mode, when,
                caseRef == null || caseRef.isBlank() ? null : caseRef, reason.strip(), actor, now, null, null,
                when.plus(TTL), null, null, 0, null, null, null, "{}", "QUEUED", null,
                previousRequestId, false, initialHolds, null);
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

    /**
     * Evaluates expired {@code NOT_STARTED_DEVICE_BUSY} requests once each (PO 2026-11-26, design 5.13): creates at most one
     * automatic follow-up request at the next quiet hour as the original requester, never retrying a retry, never retrying an
     * operator cancellation, and never bypassing business-hours confirmation or quotas. Returns how many automatic follow-up
     * requests were scheduled.
     */
    public int retryExpiredBusy() {
        Instant now = clock.instant();
        store.giveUpStale(now);
        int created = 0;
        for (RequestRow r : store.autoRetryCandidates(20)) {
            if (!"FAILED".equals(r.state()) || !"NOT_STARTED_DEVICE_BUSY".equals(r.outcome())
                    || r.startedAt() != null || r.autoRetry() || r.retryOutcome() != null) {
                continue;
            }
            if (!store.claimAutoRetryEvaluation(r.id())) {
                continue;
            }
            if (store.hasFollowUp(r.id())) {
                store.finishAutoRetryEvaluation(r.id(), JooqSupportBundleStore.SYSTEM_RETRY_ACTOR, "SKIPPED_ALREADY_REREQUESTED");
                continue;
            }
            Instant when = nextQuietHour(now);
            // Never cross a business-hours confirmation gate without an interactive human acknowledgement.
            if (businessHours.inside(when) || businessHours.inside(now)) {
                store.finishAutoRetryEvaluation(r.id(), JooqSupportBundleStore.SYSTEM_RETRY_ACTOR, "SKIPPED_BUSINESS_HOURS");
                continue;
            }
            Optional<DeviceRecord> device = devices.find(r.deviceId());
            Optional<VendorPlan> plan = device.filter(d -> !d.disabled()).flatMap(d -> planFor(d.vendorHint(), d.role()));
            List<ItemRow> prevItems = store.items(r.id());
            boolean itemsValid = !prevItems.isEmpty() && plan.isPresent() && prevItems.stream().allMatch(i -> {
                Optional<SupportBundleKind> k = SupportBundleKind.fromId(i.kind());
                if (k.isEmpty() || !k.get().enabled() || !k.get().vendor().equals(device.get().vendorHint())) return false;
                return !"management_server".equals(device.get().role()) || !"check_point".equals(device.get().vendorHint())
                        || k.get().onCheckPointManagement();
            });
            if (!itemsValid) {
                store.finishAutoRetryEvaluation(r.id(), JooqSupportBundleStore.SYSTEM_RETRY_ACTOR, "SKIPPED_INELIGIBLE");
                continue;
            }
            if (store.liveRequestCount(r.deviceId()) >= PER_DEVICE_CAP || store.liveBundleBytes() >= FLEET_BYTES_CAP) {
                store.finishAutoRetryEvaluation(r.id(), JooqSupportBundleStore.SYSTEM_RETRY_ACTOR, "SKIPPED_QUOTA");
                continue;
            }
            UUID retryId = UUID.randomUUID();
            String retryReason = "automatic retry of " + r.id();
            List<ItemSpec> specs = prevItems.stream().map(i -> new ItemSpec(i.kind(), i.vsId(), i.domainRef())).toList();
            boolean recoveryWrite = prevItems.stream().map(i -> SupportBundleKind.fromId(i.kind()))
                    .flatMap(Optional::stream).anyMatch(SupportBundleKind::recoveryWrite);
            RequestRow retryRow = new RequestRow(retryId, r.deviceId(), device.get().vendorHint(), plan.get().platformRole(),
                    "AT", when, r.caseRef(), retryReason, r.requestedBy(), now, null, null, when.plus(TTL), null, null, 0,
                    null, null, null, "{}", "QUEUED", null, r.id(), true, List.of(), null);
            try {
                store.insert(retryRow, specs);
            } catch (RuntimeException conflict) {
                String msg = String.valueOf(conflict.getMessage()) + " " + String.valueOf(conflict.getCause());
                if (msg.contains("uq_support_bundle_auto_retry_once")) {
                    store.finishAutoRetryEvaluation(r.id(), JooqSupportBundleStore.SYSTEM_RETRY_ACTOR, "SKIPPED_ALREADY_REREQUESTED");
                    continue;
                }
                if (msg.contains("uq_support_bundle_active_per_device")) {
                    store.finishAutoRetryEvaluation(r.id(), JooqSupportBundleStore.SYSTEM_RETRY_ACTOR, "SKIPPED_QUOTA");
                    continue;
                }
                store.finishAutoRetryEvaluation(r.id(), JooqSupportBundleStore.SYSTEM_RETRY_ACTOR, "SKIPPED_INELIGIBLE");
                throw conflict;
            }
            AdmissionResult result;
            try {
                result = ManualAdmission.under(
                        new ManualAdmission.Grant(r.requestedBy(), ManualAdmission.SECURITY_ADMIN, false),
                        () -> admission.submit(plan.get().capability(), r.deviceId(), "support-bundle:" + retryId,
                                r.requestedBy(), ActionRegistry.SUPPORT_BUNDLE_REQUEST,
                                recoveryWrite ? ActionClass.CLASS_1_RECOVERY_WRITE : null));
            } catch (ManualAdmission.BusinessHoursConfirmRequired bh) {
                store.failQueued(retryId, r.requestedBy(), "BUSINESS_HOURS_CONFIRM_REQUIRED");
                store.finishAutoRetryEvaluation(r.id(), JooqSupportBundleStore.SYSTEM_RETRY_ACTOR, "SKIPPED_BUSINESS_HOURS");
                continue;
            } catch (RuntimeException refused) {
                store.failQueued(retryId, r.requestedBy(), "ADMISSION_REFUSED");
                store.finishAutoRetryEvaluation(r.id(), JooqSupportBundleStore.SYSTEM_RETRY_ACTOR, "SKIPPED_INELIGIBLE");
                continue;
            }
            String jobId = switch (result) {
                case AdmissionResult.Admitted a -> a.jobId();
                case AdmissionResult.Deduplicated d -> d.jobId();
                case AdmissionResult.Refused refused -> {
                    store.failQueued(retryId, r.requestedBy(), refused.code());
                    store.finishAutoRetryEvaluation(r.id(), JooqSupportBundleStore.SYSTEM_RETRY_ACTOR, "SKIPPED_INELIGIBLE");
                    yield null;
                }
            };
            if (jobId != null) {
                store.attachJob(retryId, jobId, r.requestedBy());
                store.deferJob(jobId, when, r.requestedBy());
                store.finishAutoRetryEvaluation(r.id(), r.requestedBy(), "RETRY_SCHEDULED");
                created++;
            }
        }
        return created;
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
