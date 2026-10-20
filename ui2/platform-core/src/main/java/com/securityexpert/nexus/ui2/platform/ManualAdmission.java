package com.securityexpert.nexus.ui2.platform;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The administrator's manual-submission grant (PO decision 2026-10-20,
 * {@code docs/design/PO_DECISION_RECORD_2026_10_20_ADMIN_MANUAL_JOBS_AND_BUSINESS_HOURS.md}).
 *
 * <p>The service opens a grant for the duration of one HTTP request whose authenticated actor holds
 * {@code security_admin} or {@code operation_admin} (never a replay viewer). While it is open on the request thread,
 * {@link JobWindowPolicy#requireOpen()} and {@link JobWindowPolicy#requireDeviceJobOpen} do not refuse for the job
 * window; inside {@link BusinessHours} they refuse with {@link BusinessHoursConfirmRequired} unless the grant carries
 * the acknowledgement. Schedulers, workers and every other thread never see a grant (a thread-local is not inherited),
 * so scheduled and automatic jobs keep the windows exactly as before; operator and aiview requests never get one.</p>
 */
public final class ManualAdmission {
    /** The HTTP request header an administrator's client sends after confirming. */
    public static final String ACK_HEADER = "X-Nexus-Business-Hours-Ack";
    /** The same acknowledgement as a query parameter ({@code ?business_hours_ack=true}). */
    public static final String ACK_PARAMETER = "business_hours_ack";
    public static final String CONFIRM_CODE = "BUSINESS_HOURS_CONFIRM_REQUIRED";
    public static final String SECURITY_ADMIN = "security_admin";
    public static final String OPERATION_ADMIN = "operation_admin";

    /** @param role {@link #SECURITY_ADMIN} or {@link #OPERATION_ADMIN} */
    public record Grant(String actorFingerprint, String role, boolean acknowledged) {
        public Grant {
            Objects.requireNonNull(actorFingerprint, "actorFingerprint");
            if (!SECURITY_ADMIN.equals(role) && !OPERATION_ADMIN.equals(role))
                throw new IllegalArgumentException("only security_admin and operation_admin hold the window bypass");
        }
    }

    private static final ThreadLocal<Grant> CURRENT = new ThreadLocal<>();

    private ManualAdmission() { }

    public static Optional<Grant> current() { return Optional.ofNullable(CURRENT.get()); }

    /** The grant, only when it belongs to {@code actorFingerprint}: a job submitted for another actor never rides on it. */
    public static Optional<Grant> currentFor(String actorFingerprint) {
        return current().filter(g -> g.actorFingerprint().equals(actorFingerprint));
    }

    /** Request scope: the service interceptor opens it in {@code preHandle} and {@link #close()}s it in {@code afterCompletion}. */
    public static void open(Grant grant) { CURRENT.set(Objects.requireNonNull(grant)); }

    public static void close() { CURRENT.remove(); }

    public static <T> T under(Grant grant, Supplier<T> work) {
        Grant previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(grant));
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    /**
     * Runs {@code work} with no grant: for a manual start whose approved contract keeps it inside the job windows for
     * every role (the system-log collection and measurement starts, whose command frequencies are set per window).
     */
    public static <T> T withoutGrant(Supplier<T> work) {
        Grant previous = CURRENT.get();
        CURRENT.remove();
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    private static void restore(Grant previous) {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }

    /** 409 {@link #CONFIRM_CODE}: an administrator's manual device job inside business hours without the acknowledgement. */
    public static final class BusinessHoursConfirmRequired extends RuntimeException {
        private final BusinessHours hours;
        public BusinessHoursConfirmRequired(BusinessHours hours) {
            super(hours.confirmMessage());
            this.hours = hours;
        }
        public BusinessHours hours() { return hours; }
    }
}
