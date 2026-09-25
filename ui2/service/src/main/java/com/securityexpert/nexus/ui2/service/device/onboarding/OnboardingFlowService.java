package com.securityexpert.nexus.ui2.service.device.onboarding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.securityexpert.nexus.ui2.persistence.device.DeviceOnboarding;
import com.securityexpert.nexus.ui2.persistence.device.DeviceOnboardingRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.device.DeviceAddSingleService;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationCollectService;
import com.securityexpert.nexus.ui2.service.device.inventory.InventoryCollectService;

/**
 * The device onboarding flow (PO, 2026-09-25; docs/design/DEVICE_ONBOARDING_FLOW_CONTRACT.md): identity confirm,
 * then inventory, then configuration -- each its own job, admitted through the same services a person's Collect uses,
 * so every gate, time bound and eligibility rule still applies. {@link #advanceAll} is called by the scheduler; it
 * never waits on a job, it only looks at jobs that are already terminal.
 */
public final class OnboardingFlowService {

    private static final System.Logger LOG = System.getLogger(OnboardingFlowService.class.getName());

    public static final String ACTOR = "system:onboarding";
    public static final String ACTION = "device_onboarding_advance";

    /** Admission refusals meaning "this vendor/role has no such read" -- the step is skipped with its code, never failed. */
    static final Set<String> NOT_APPLICABLE = Set.of("VENDOR_UNSUPPORTED", "MANAGEMENT_SERVER_UNGATED",
            "ROLE_UNRECOGNISED", "VENDOR_READ_SET_UNGATED");

    public sealed interface RetryOutcome {
        record Retried(String step, String jobId) implements RetryOutcome {
        }

        record NotStopped() implements RetryOutcome {
        }

        record NotFound() implements RetryOutcome {
        }

        record Refused(String code, String reason) implements RetryOutcome {
        }
    }

    /** Admits one step's job for a device (production: the inventory / configuration collect services). */
    interface StepAdmitter {
        Admission admit(String deviceId, String step, String actorFingerprint);
    }

    /** Re-admits a device's identity confirm (production: {@link DeviceAddSingleService#retryConfirm}). */
    interface IdentityRetry {
        RetryOutcome retry(String deviceId, String actorFingerprint);
    }

    private final DeviceOnboardingRepository onboarding;
    private final Function<String, Optional<DeviceEnrollmentState>> enrollment;
    private final JobRecordDao jobs;
    private final StepAdmitter admitter;
    private final IdentityRetry identityRetry;

    public OnboardingFlowService(DeviceOnboardingRepository onboarding, DeviceRepository devices, JobRecordDao jobs,
            InventoryCollectService inventory, ConfigurationCollectService configuration, DeviceAddSingleService addSingle) {
        this(onboarding, deviceId -> devices.find(deviceId).map(DeviceRecord::enrollmentState), jobs,
                collectAdmitter(inventory, configuration), identityRetry(addSingle));
    }

    OnboardingFlowService(DeviceOnboardingRepository onboarding,
            Function<String, Optional<DeviceEnrollmentState>> enrollment, JobRecordDao jobs, StepAdmitter admitter,
            IdentityRetry identityRetry) {
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.enrollment = Objects.requireNonNull(enrollment, "enrollment");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.admitter = Objects.requireNonNull(admitter, "admitter");
        this.identityRetry = Objects.requireNonNull(identityRetry, "identityRetry");
    }

    private static StepAdmitter collectAdmitter(InventoryCollectService inventory,
            ConfigurationCollectService configuration) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(configuration, "configuration");
        return (deviceId, step, actor) -> {
            Optional<String> nonce = Optional.of("onboarding-" + UUID.randomUUID());
            if (DeviceOnboarding.INVENTORY.equals(step)) {
                return switch (inventory.requestCollect(deviceId, actor, nonce)) {
                    case InventoryCollectService.Outcome.Admitted a -> new Admission.Admitted(a.jobId());
                    case InventoryCollectService.Outcome.DeviceNotFound d -> new Admission.Gone();
                    case InventoryCollectService.Outcome.AdmissionRefused r -> new Admission.Refused(r.code(), r.reason());
                };
            }
            return switch (configuration.requestCollect(deviceId, actor, nonce)) {
                case ConfigurationCollectService.Outcome.Admitted a -> new Admission.Admitted(a.jobId());
                case ConfigurationCollectService.Outcome.DeviceNotFound d -> new Admission.Gone();
                case ConfigurationCollectService.Outcome.AdmissionRefused r -> new Admission.Refused(r.code(), r.reason());
            };
        };
    }

    private static IdentityRetry identityRetry(DeviceAddSingleService addSingle) {
        return (deviceId, actor) -> {
            if (addSingle == null) {
                return new RetryOutcome.Refused("UNAVAILABLE", "identity retry is not wired in this composition");
            }
            // retryConfirm restarts the flow at the identity step itself (the same restart a Retry on the confirm does).
            return switch (addSingle.retryConfirm(deviceId, actor)) {
                case DeviceAddSingleService.Outcome.Admitted a -> new RetryOutcome.Retried(DeviceOnboarding.IDENTITY, a.jobId());
                case DeviceAddSingleService.Outcome.ValidationFailed v -> new RetryOutcome.Refused(v.reasonCode(), v.reasonCode());
                case DeviceAddSingleService.Outcome.AdmissionRefused r -> new RetryOutcome.Refused(r.code(), r.reason());
            };
        };
    }

    /** @return how many flows moved this pass */
    public int advanceAll() {
        int moved = 0;
        for (DeviceOnboarding flow : onboarding.findRunning()) {
            try {
                if (advance(flow)) {
                    moved++;
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "ONBOARDING_ADVANCE_FAILED device=" + flow.deviceId()
                        + " step=" + flow.step() + " error=" + e.getClass().getSimpleName());
            }
        }
        return moved;
    }

    boolean advance(DeviceOnboarding flow) {
        if (flow.stepJobId().isEmpty()) {
            return stop(flow, "no job recorded for this step");
        }
        Optional<JobRow> job = jobs.find(flow.stepJobId().get());
        if (job.isEmpty()) {
            return stop(flow, "the step's job no longer exists");
        }
        String state = job.get().state();
        if (!isTerminal(state)) {
            return false;
        }
        if (!"COMPLETED".equals(state)) {
            String why = Optional.ofNullable(job.get().terminalReason()).filter(s -> !s.isBlank())
                    .orElse(Optional.ofNullable(job.get().outcome()).orElse("no reason recorded"));
            return stop(flow, state + ": " + why);
        }
        if (DeviceOnboarding.IDENTITY.equals(flow.step())) {
            Optional<DeviceEnrollmentState> state0 = enrollment.apply(flow.deviceId());
            if (state0.isEmpty()) {
                return false;
            }
            if (state0.get() != DeviceEnrollmentState.ENROLLED) {
                return stop(flow, "identity confirm completed but the device is not enrolled");
            }
        }
        return next(flow, flow.step(), new ArrayList<>(skippedOf(flow)));
    }

    /** Admits the step after {@code from}; a not-applicable step is recorded as skipped and the one after is tried. */
    private boolean next(DeviceOnboarding flow, String from, List<String> skipped) {
        String step = DeviceOnboarding.IDENTITY.equals(from) ? DeviceOnboarding.INVENTORY
                : DeviceOnboarding.INVENTORY.equals(from) ? DeviceOnboarding.CONFIGURATION : null;
        if (step == null) {
            return onboarding.advance(flow.deviceId(), flow.step(), flow.stepJobId(), DeviceOnboarding.COMPLETED,
                    flow.step(), flow.stepJobId(), Optional.empty(), String.join(",", skipped), ACTOR, ACTION);
        }
        Admission admission = admitter.admit(flow.deviceId(), step, ACTOR);
        return switch (admission) {
            case Admission.Admitted a -> onboarding.advance(flow.deviceId(), flow.step(), flow.stepJobId(),
                    DeviceOnboarding.RUNNING, step, Optional.of(a.jobId()), Optional.empty(), String.join(",", skipped),
                    ACTOR, ACTION);
            case Admission.Gone g -> false;
            case Admission.Refused r when NOT_APPLICABLE.contains(r.code()) -> {
                skipped.add(step + ":" + r.code());
                yield nextFrom(flow, step, skipped);
            }
            case Admission.Refused r -> onboarding.advance(flow.deviceId(), flow.step(), flow.stepJobId(),
                    DeviceOnboarding.STOPPED, step, Optional.empty(), Optional.of(r.code() + ": " + r.reason()),
                    String.join(",", skipped), ACTOR, ACTION);
        };
    }

    /** Continues past a skipped step while keeping the row's current step/job as the optimistic-lock expectation. */
    private boolean nextFrom(DeviceOnboarding flow, String skippedStep, List<String> skipped) {
        if (DeviceOnboarding.CONFIGURATION.equals(skippedStep)) {
            return onboarding.advance(flow.deviceId(), flow.step(), flow.stepJobId(), DeviceOnboarding.COMPLETED,
                    skippedStep, Optional.empty(), Optional.empty(), String.join(",", skipped), ACTOR, ACTION);
        }
        return next(flow, skippedStep, skipped);
    }

    /** Re-admits the step a STOPPED flow stopped at; nothing else. */
    public RetryOutcome retry(String deviceId, String actorFingerprint) {
        Optional<DeviceOnboarding> found = onboarding.find(deviceId);
        if (found.isEmpty()) {
            return new RetryOutcome.NotFound();
        }
        DeviceOnboarding flow = found.get();
        if (!DeviceOnboarding.STOPPED.equals(flow.state())) {
            return new RetryOutcome.NotStopped();
        }
        if (DeviceOnboarding.IDENTITY.equals(flow.step())) {
            return identityRetry.retry(deviceId, actorFingerprint);
        }
        return switch (admitter.admit(deviceId, flow.step(), actorFingerprint)) {
            case Admission.Admitted a -> {
                onboarding.advance(deviceId, flow.step(), flow.stepJobId(), DeviceOnboarding.RUNNING, flow.step(),
                        Optional.of(a.jobId()), Optional.empty(), flow.skipped(), actorFingerprint, ACTION);
                yield new RetryOutcome.Retried(flow.step(), a.jobId());
            }
            case Admission.Gone g -> new RetryOutcome.NotFound();
            case Admission.Refused r -> new RetryOutcome.Refused(r.code(), r.reason());
        };
    }

    sealed interface Admission {
        record Admitted(String jobId) implements Admission {
        }

        record Refused(String code, String reason) implements Admission {
        }

        record Gone() implements Admission {
        }
    }

    private boolean stop(DeviceOnboarding flow, String reason) {
        return onboarding.advance(flow.deviceId(), flow.step(), flow.stepJobId(), DeviceOnboarding.STOPPED,
                flow.step(), flow.stepJobId(), Optional.of(reason), flow.skipped(), ACTOR, ACTION);
    }

    private static List<String> skippedOf(DeviceOnboarding flow) {
        return flow.skipped() == null || flow.skipped().isBlank() ? List.of() : List.of(flow.skipped().split(","));
    }

    private static boolean isTerminal(String state) {
        return Set.of("COMPLETED", "FAILED", "REJECTED", "CANCELLED", "OUTCOME_UNKNOWN", "RECONCILED").contains(state);
    }
}
