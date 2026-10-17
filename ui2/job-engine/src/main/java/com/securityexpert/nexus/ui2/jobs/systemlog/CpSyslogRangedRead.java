package com.securityexpert.nexus.ui2.jobs.systemlog;

import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * The one entry point to the C2 incremental read of the system-log programme
 * ({@code docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md} §4.3, §10.1 row C2): an SFTP ranged read of one file of
 * one Check Point Gaia gateway log family, over the run's existing SSH session.
 *
 * <p>Before any I/O the read is checked, in this order, and refused with a {@link Refusal} code on the first failure:
 * <ol>
 *   <li>the device's resolved platform role scope is {@code cp_gaia_gateway};</li>
 *   <li>the C2 gate {@value #GATE_ID} resolves {@code KNOWN} against the live gate registry, re-evaluated on every
 *       call (C4 §3.3: never cached across a sign-off change), as {@code read} ({@link ActionClass#CLASS_0_READ});</li>
 *   <li>the range is non-negative and the caller's per-read cap is at most {@link #MAX_READ_BYTES};</li>
 *   <li>the path rule of §4.3: the path is one of the absolute paths this run's C1 stat printed, its directory equals
 *       the directory of the family's base file (also printed by C1), and its basename matches the family's
 *       read-eligible pattern {@code ^<base>(\.[0-9]{1,2})?$}.</li>
 * </ol>
 * The requested length is capped by the caller's per-read cap ({@code <cap>}); the timeout is the gate row's.
 * Device-load bounds (§3.4) are the caller's to keep: one session per device, one read at a time, inside the job
 * window; this class issues exactly one transport call per invocation and never retries.</p>
 */
public final class CpSyslogRangedRead {

    /** §10.1 row C2. The gate row itself is inserted by the phase B implementation migration (V160). */
    public static final String GATE_ID = "cp_syslog_collect_read";
    public static final String VENDOR = "check_point";
    public static final String PLATFORM_ROLE_SCOPE = "cp_gaia_gateway";
    /** No shell runs for C2; the V160 row and the gate fixture carry {@code not_applicable}. */
    public static final String SHELL_CONTEXT = "not_applicable";
    /** C2 sends no shell command: its transport is the {@code sftp_get} step with a byte range (C4 §2.3, A-2). */
    public static final String TRANSPORT_KIND = "SFTP_GET";
    /**
     * The literal canonical command key of the C2 gate row: the §10.1 C2 cell text, exactly as V160 and
     * {@code gate_registry_fixture.yaml} register it ({@code CpSyslogRangedReadTest} asserts the equality).
     * {@code <pos>} and {@code <cap>} are computed by neXus.
     */
    public static final String COMMAND_KEY = "no shell command. Read-only SFTP open of `<path>`, read "
            + "`[<pos>, <pos> + <cap>)`, close. `<pos>`, `<cap>` non-negative integers computed by neXus, "
            + "`<cap> \u2264 8388672`; `<path>` = an absolute path printed by C1 in the same run, basename in the "
            + "source's family pattern, directory equal to the family base directory";
    public static final CanonicalCommandKey GATE_KEY =
            new CanonicalCommandKey(VENDOR, PLATFORM_ROLE_SCOPE, SHELL_CONTEXT, TRANSPORT_KIND, COMMAND_KEY);

    /** §10.1 C2: {@code <cap> ≤ 8388672}, the 8 MiB read budget plus the 64-byte anchor region. */
    public static final long MAX_READ_BYTES = 8L * 1024 * 1024 + 64;

    private static final Pattern SAFE_ABSOLUTE_PATH = Pattern.compile("/[A-Za-z0-9._/-]{1,510}");

    /** Why a read was refused before any I/O. The name is the {@code reasonCode} of the refused result. */
    public enum Refusal {
        PLATFORM_NOT_IN_SCOPE,
        GATE_NOT_KNOWN,
        RANGE_INVALID,
        CAP_ABOVE_GATE_BOUND,
        PATH_UNSAFE,
        PATH_NOT_PRINTED_BY_STAT,
        FAMILY_BASE_NOT_PRINTED_BY_STAT,
        FAMILY_DIRECTORY_MISMATCH,
        NAME_NOT_READ_ELIGIBLE
    }

    /**
     * The incremental text sources of §2.1 with their base file names. {@code fixedDirectory} is the directory the
     * C1 command names literally; {@code null} for a family under {@code $FWDIR/log} or {@code $CPDIR/log}, whose
     * directory the device's login shell expands (the base directory must then end in {@code /log}, as the C1 globs
     * do).
     */
    public enum Family {
        CP_MESSAGES("cp_messages", "messages", "/var/log"),
        CP_FWD_ELG("cp_fwd_elg", "fwd.elg", null),
        CP_CPD_ELG("cp_cpd_elg", "cpd.elg", null),
        CP_CPWD_ELG("cp_cpwd_elg", "cpwd.elg", null),
        CP_FWK_ELG("cp_fwk_elg", "fwk.elg", null),
        CP_MQ_MNG_ELG("cp_mq_mng_elg", "mq_mng.elg", null),
        CP_SPIKE_LOG("cp_spike_log", "spike_detective.log", "/var/log/spike_detective"),
        CP_SPIKE_CSV("cp_spike_csv", "spike_detective.csv", "/var/log/spike_detective");

        private final String sourceKey;
        private final String baseName;
        private final String fixedDirectory;
        private final Pattern readEligibleName;

        Family(String sourceKey, String baseName, String fixedDirectory) {
            this.sourceKey = sourceKey;
            this.baseName = baseName;
            this.fixedDirectory = fixedDirectory;
            this.readEligibleName = Pattern.compile(Pattern.quote(baseName) + "(\\.[0-9]{1,2})?");
        }

        public String sourceKey() {
            return sourceKey;
        }

        public String baseName() {
            return baseName;
        }

        /** §4.3: {@code ^<base>(\.[0-9]{1,2})?$}; every other sibling name ({@code .gz}, {@code .old}, dated) is never read. */
        public boolean isReadEligibleName(String name) {
            return name != null && readEligibleName.matcher(name).matches();
        }

        boolean isFamilyDirectory(String directory) {
            return fixedDirectory != null ? fixedDirectory.equals(directory)
                    : directory.length() > "/log".length() && directory.endsWith("/log");
        }
    }

    /**
     * One planned read.
     *
     * @param platformRoleScope the device's resolved platform role scope
     * @param family            the source's file family
     * @param familyBasePath    the family's base file as this run's C1 stat printed it (e.g. the expanded
     *                          {@code $FWDIR/log/fwd.elg})
     * @param statPrintedPaths  every absolute path this run's C1 stat printed
     * @param path              the file to read
     * @param startOffset       {@code <pos>}: 0-based byte position, computed by neXus from the cursor
     * @param maxBytes          the bytes wanted from {@code startOffset} ({@code K + budget})
     * @param perReadCap        the caller's per-read cap ({@code <cap>}, 8 MiB + 64 B provisional, §3.4)
     */
    public record ReadRequest(String platformRoleScope, Family family, String familyBasePath,
            Set<String> statPrintedPaths, String path, long startOffset, long maxBytes, long perReadCap) {

        public ReadRequest {
            Objects.requireNonNull(family, "family");
            statPrintedPaths = statPrintedPaths == null ? Set.of() : Set.copyOf(statPrintedPaths);
        }
    }

    private CpSyslogRangedRead() {
    }

    /**
     * Checks {@code request} and, only when every check passes, performs the one ranged read into {@code sink}.
     * A refusal returns {@link com.securityexpert.nexus.ui2.jobs.transport.RangedFetchStatus#REFUSED} with the
     * {@link Refusal} name as its reason code and touches neither the transport nor the sink.
     */
    public static RangedFetchResult read(DeviceTransport transport, TransportSession session, GateRegistryPort gates,
            ReadRequest request, OutputStream sink) {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(sink, "sink");
        Optional<GateResolution.Known> gate = resolveGate(gates);
        Optional<Refusal> refusal = check(request, gate.isPresent());
        if (refusal.isPresent()) {
            return RangedFetchResult.refused(Math.max(0, request.startOffset()), refusal.get().name());
        }
        long length = Math.min(request.maxBytes(), request.perReadCap());
        return transport.fetchRange(session, new RangedFetchSpec(request.path(), request.startOffset(), length),
                Duration.ofSeconds(gate.get().timeoutS()), sink);
    }

    /** The pre-I/O checks alone (no transport); {@code empty} means the read is eligible. */
    public static Optional<Refusal> check(ReadRequest request, GateRegistryPort gates) {
        return check(request, resolveGate(gates).isPresent());
    }

    private static Optional<Refusal> check(ReadRequest request, boolean gateKnown) {
        if (!PLATFORM_ROLE_SCOPE.equals(request.platformRoleScope())) {
            return Optional.of(Refusal.PLATFORM_NOT_IN_SCOPE);
        }
        if (!gateKnown) {
            return Optional.of(Refusal.GATE_NOT_KNOWN);
        }
        if (request.startOffset() < 0 || request.maxBytes() < 1 || request.perReadCap() < 1) {
            return Optional.of(Refusal.RANGE_INVALID);
        }
        if (request.perReadCap() > MAX_READ_BYTES) {
            return Optional.of(Refusal.CAP_ABOVE_GATE_BOUND);
        }
        String path = request.path();
        String base = request.familyBasePath();
        if (!isSafeAbsolutePath(path) || !isSafeAbsolutePath(base)) {
            return Optional.of(Refusal.PATH_UNSAFE);
        }
        if (!request.statPrintedPaths().contains(path)) {
            return Optional.of(Refusal.PATH_NOT_PRINTED_BY_STAT);
        }
        if (!request.statPrintedPaths().contains(base)) {
            return Optional.of(Refusal.FAMILY_BASE_NOT_PRINTED_BY_STAT);
        }
        String baseDirectory = directoryOf(base);
        if (!request.family().baseName().equals(nameOf(base)) || !request.family().isFamilyDirectory(baseDirectory)
                || !baseDirectory.equals(directoryOf(path))) {
            return Optional.of(Refusal.FAMILY_DIRECTORY_MISMATCH);
        }
        if (!request.family().isReadEligibleName(nameOf(path))) {
            return Optional.of(Refusal.NAME_NOT_READ_ELIGIBLE);
        }
        return Optional.empty();
    }

    private static Optional<GateResolution.Known> resolveGate(GateRegistryPort gates) {
        if (gates == null) {
            return Optional.empty();
        }
        try {
            GateResolution resolution = GateResolver.resolve(GATE_KEY, Optional.of(ActionClass.CLASS_0_READ), gates);
            return resolution instanceof GateResolution.Known known && GATE_ID.equals(known.gateId())
                    && known.actionClass() == ActionClass.CLASS_0_READ && known.timeoutS() > 0
                    ? Optional.of(known) : Optional.empty();
        } catch (RuntimeException invalidGate) {
            // Ambiguous rows, an action-class mismatch or a denylist violation: fail closed, never read.
            return Optional.empty();
        }
    }

    private static boolean isSafeAbsolutePath(String path) {
        if (path == null || !SAFE_ABSOLUTE_PATH.matcher(path).matches() || path.endsWith("/")
                || path.contains("//")) {
            return false;
        }
        for (String segment : path.substring(1).split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static String directoryOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
