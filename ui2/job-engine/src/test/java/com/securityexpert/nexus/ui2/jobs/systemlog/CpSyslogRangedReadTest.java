package com.securityexpert.nexus.ui2.jobs.systemlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSyslogRangedRead.Family;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSyslogRangedRead.ReadRequest;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSyslogRangedRead.Refusal;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.RangedFetchStatus;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/** Gate and path-rule enforcement of the C2 ranged read (phase B contract §4.3, §10.1 C2): refused before any I/O. */
class CpSyslogRangedReadTest {

    /** Synthetic expanded $FWDIR; never a real install path. */
    private static final String FW_LOG = "/opt/synthetic-suite/fw1/log";
    private static final String FWD = FW_LOG + "/fwd.elg";
    private static final String MESSAGES = "/var/log/messages";
    private static final Set<String> STAT = Set.of(MESSAGES, MESSAGES + ".1", MESSAGES + ".2.gz", FWD, FWD + ".1",
            FWD + ".gz", FWD + ".old", FW_LOG + "/fwk.elg", "/var/log/elsewhere/messages.1", MESSAGES + ".123");
    private static final TransportSession SESSION = () -> "synthetic-session";

    /** The test fixture for the V160 C2 row (the migration itself belongs to another lane). */
    static GateRow c2Row(SignOffState state) {
        return new GateRow(CpSyslogRangedRead.GATE_ID, "check_point", "cp_gaia_gateway", "not_applicable", "SFTP_GET",
                CpSyslogRangedRead.COMMAND_KEY, ActionClass.CLASS_0_READ, state, 60, "none",
                "per source per run, until the per-source or per-device budget; only inside the job window",
                "same session (SFTP channel); sequential", "missing file or short anchor read = UNKNOWN",
                "raw log lines; redacted on write", List.of("startOffset", "bytesRead", "lines", "anchorMatch",
                        "lineSplit"), "docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.1 C2");
    }

    private static GateRegistryPort gates(GateRow... rows) {
        return key -> java.util.Arrays.stream(rows).filter(row -> row.key().equals(key)).toList();
    }

    private static ReadRequest request(Family family, String base, String path, long start, long max, long cap) {
        return new ReadRequest("cp_gaia_gateway", family, base, STAT, path, start, max, cap);
    }

    private static final class RecordingTransport implements DeviceTransport {
        final List<RangedFetchSpec> specs = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();

        @Override
        public RangedFetchResult fetchRange(TransportSession session, RangedFetchSpec spec, Duration timeout,
                OutputStream sink) {
            specs.add(spec);
            timeouts.add(timeout);
            return new RangedFetchResult(RangedFetchStatus.CAP_REACHED, spec.startOffset(), spec.maxBytes(),
                    spec.maxBytes(), OptionalLong.of(10_000_000), false, "");
        }

        @Override public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) { throw new AssertionError(); }
        @Override public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) { throw new AssertionError("no exec channel"); }
        @Override public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) { throw new AssertionError(); }
        @Override public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) { throw new AssertionError(); }
        @Override public void disconnect(TransportSession session) { throw new AssertionError("the run owns the session"); }
    }

    /** The constant key and the registered C2 row (V160, mirrored in the gate fixture) can never drift apart. */
    @Test
    void theConstantKeyIsExactlyTheRegisteredC2Row() {
        List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        List<GateRow> c2 = rows.stream().filter(row -> CpSyslogRangedRead.GATE_ID.equals(row.gateId())).toList();
        assertEquals(1, c2.size());
        GateRow row = c2.get(0);
        assertEquals(CpSyslogRangedRead.GATE_KEY, row.key());
        assertEquals(ActionClass.CLASS_0_READ, row.actionClass());
        assertEquals(SignOffState.SIGNED_OFF, row.signOffState());
        assertEquals(60, row.timeoutS());
        assertTrue(row.canonicalCommandKey().contains("`<cap> \u2264 " + CpSyslogRangedRead.MAX_READ_BYTES + "`"),
                "the gate's cap bound and MAX_READ_BYTES agree");
        // The real fixture row authorises an eligible read on its own.
        RecordingTransport transport = new RecordingTransport();
        RangedFetchResult result = CpSyslogRangedRead.read(transport, SESSION,
                key -> rows.stream().filter(r -> r.key().equals(key)).toList(),
                request(Family.CP_MESSAGES, MESSAGES, MESSAGES + ".1", 0, 64, CpSyslogRangedRead.MAX_READ_BYTES),
                new ByteArrayOutputStream());
        assertEquals(RangedFetchStatus.CAP_REACHED, result.status());
        assertEquals(List.of(Duration.ofSeconds(60)), transport.timeouts);
    }

    @Test
    void eligibleReadGoesToTheTransportWithTheCapAndTheGateTimeout() {
        RecordingTransport transport = new RecordingTransport();
        RangedFetchResult result = CpSyslogRangedRead.read(transport, SESSION, gates(c2Row(SignOffState.SIGNED_OFF)),
                request(Family.CP_FWD_ELG, FWD, FWD + ".1", 4_096, 20_000_000, CpSyslogRangedRead.MAX_READ_BYTES),
                new ByteArrayOutputStream());
        assertEquals(RangedFetchStatus.CAP_REACHED, result.status());
        assertEquals(List.of(new RangedFetchSpec(FWD + ".1", 4_096, 8_388_672)), transport.specs);
        assertEquals(List.of(Duration.ofSeconds(60)), transport.timeouts);
    }

    @Test
    void lengthBelowTheCapIsSentUnchanged() {
        RecordingTransport transport = new RecordingTransport();
        CpSyslogRangedRead.read(transport, SESSION, gates(c2Row(SignOffState.SIGNED_OFF)),
                request(Family.CP_MESSAGES, MESSAGES, MESSAGES, 0, 64, 1_024), new ByteArrayOutputStream());
        assertEquals(List.of(new RangedFetchSpec(MESSAGES, 0, 64)), transport.specs);
    }

    @Test
    void theGateIsRequiredAndMustBeSignedOff() {
        assertRefused(Refusal.GATE_NOT_KNOWN, gates(),
                request(Family.CP_MESSAGES, MESSAGES, MESSAGES, 0, 10, 10));
        for (SignOffState state : SignOffState.values()) {
            if (state == SignOffState.SIGNED_OFF) continue;
            assertRefused(Refusal.GATE_NOT_KNOWN, gates(c2Row(state)),
                    request(Family.CP_MESSAGES, MESSAGES, MESSAGES, 0, 10, 10));
        }
        // Two rows for one key is a spec defect (C4 §3.3 step 6): fail closed, never pick one.
        assertRefused(Refusal.GATE_NOT_KNOWN, gates(c2Row(SignOffState.SIGNED_OFF), c2Row(SignOffState.SIGNED_OFF)),
                request(Family.CP_MESSAGES, MESSAGES, MESSAGES, 0, 10, 10));
        // A row with another action class never authorises the read.
        GateRow write = new GateRow(CpSyslogRangedRead.GATE_ID, "check_point", "cp_gaia_gateway", "not_applicable",
                "SFTP_GET", CpSyslogRangedRead.COMMAND_KEY, ActionClass.CLASS_1_RECOVERY_WRITE,
                SignOffState.SIGNED_OFF, 60, "none", "", "", "", "", List.of(), "");
        assertRefused(Refusal.GATE_NOT_KNOWN, gates(write),
                request(Family.CP_MESSAGES, MESSAGES, MESSAGES, 0, 10, 10));
        assertRefused(Refusal.GATE_NOT_KNOWN, null, request(Family.CP_MESSAGES, MESSAGES, MESSAGES, 0, 10, 10));
    }

    @Test
    void onlyCheckPointGaiaGatewaysAreInScope() {
        for (String scope : new String[] {"gaia_embedded", "cp_multi_domain_server", "pan_firewall", "", null}) {
            assertRefused(Refusal.PLATFORM_NOT_IN_SCOPE, gates(c2Row(SignOffState.SIGNED_OFF)),
                    new ReadRequest(scope, Family.CP_MESSAGES, MESSAGES, STAT, MESSAGES, 0, 10, 10));
        }
    }

    @Test
    void rangeAndCapBounds() {
        GateRegistryPort ok = gates(c2Row(SignOffState.SIGNED_OFF));
        assertRefused(Refusal.RANGE_INVALID, ok, request(Family.CP_MESSAGES, MESSAGES, MESSAGES, -1, 10, 10));
        assertRefused(Refusal.RANGE_INVALID, ok, request(Family.CP_MESSAGES, MESSAGES, MESSAGES, 0, 0, 10));
        assertRefused(Refusal.RANGE_INVALID, ok, request(Family.CP_MESSAGES, MESSAGES, MESSAGES, 0, 10, 0));
        assertRefused(Refusal.CAP_ABOVE_GATE_BOUND, ok, request(Family.CP_MESSAGES, MESSAGES, MESSAGES, 0, 10,
                CpSyslogRangedRead.MAX_READ_BYTES + 1));
        assertEquals(Optional.empty(), CpSyslogRangedRead.check(request(Family.CP_MESSAGES, MESSAGES, MESSAGES, 0, 10,
                CpSyslogRangedRead.MAX_READ_BYTES), ok));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/var/log/messages.2.gz", "/var/log/messages.123", "/opt/synthetic-suite/fw1/log/fwd.elg.gz",
            "/opt/synthetic-suite/fw1/log/fwd.elg.old"})
    void siblingNamesOutsideTheFamilyPatternAreNeverRead(String path) {
        Family family = path.contains("fwd") ? Family.CP_FWD_ELG : Family.CP_MESSAGES;
        String base = path.contains("fwd") ? FWD : MESSAGES;
        assertRefused(Refusal.NAME_NOT_READ_ELIGIBLE, gates(c2Row(SignOffState.SIGNED_OFF)),
                request(family, base, path, 0, 10, 10));
    }

    @Test
    void pathMustBePrintedByThisRunsStatAndSitInTheFamilyDirectory() {
        GateRegistryPort ok = gates(c2Row(SignOffState.SIGNED_OFF));
        assertRefused(Refusal.PATH_NOT_PRINTED_BY_STAT, ok,
                request(Family.CP_MESSAGES, MESSAGES, "/var/log/messages.3", 0, 10, 10));
        assertRefused(Refusal.PATH_NOT_PRINTED_BY_STAT, ok,
                request(Family.CP_MESSAGES, MESSAGES, "/etc/shadow", 0, 10, 10));
        assertRefused(Refusal.FAMILY_DIRECTORY_MISMATCH, ok,
                request(Family.CP_MESSAGES, MESSAGES, "/var/log/elsewhere/messages.1", 0, 10, 10));
        // fwk.elg's file read under the fwd.elg family: the base name does not match the family.
        assertRefused(Refusal.FAMILY_DIRECTORY_MISMATCH, ok,
                request(Family.CP_FWD_ELG, FW_LOG + "/fwk.elg", FW_LOG + "/fwk.elg", 0, 10, 10));
        assertRefused(Refusal.FAMILY_BASE_NOT_PRINTED_BY_STAT, ok,
                request(Family.CP_FWD_ELG, "/opt/other/log/fwd.elg", FWD, 0, 10, 10));
        // A $FWDIR family whose base directory is not a .../log directory, as the C1 globs require.
        Set<String> odd = Set.of("/opt/synthetic-suite/fw1/fwd.elg");
        assertRefused(Refusal.FAMILY_DIRECTORY_MISMATCH, ok, new ReadRequest("cp_gaia_gateway", Family.CP_FWD_ELG,
                "/opt/synthetic-suite/fw1/fwd.elg", odd, "/opt/synthetic-suite/fw1/fwd.elg", 0, 10, 10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"var/log/messages", "/var/log/../log/messages", "/var/log/./messages", "/var//log/messages",
            "/var/log/messages*", "/var/log/mess?ges", "/var/log/messages\n", "/var/log/", "/var/log/messages;id"})
    void unsafePathsAreRefused(String path) {
        Set<String> stat = new java.util.HashSet<>(STAT);
        stat.add(path);
        assertRefused(Refusal.PATH_UNSAFE, gates(c2Row(SignOffState.SIGNED_OFF)),
                new ReadRequest("cp_gaia_gateway", Family.CP_MESSAGES, MESSAGES, stat, path, 0, 10, 10));
    }

    @Test
    void familiesCarryTheSectionTwoSourceKeys() {
        assertEquals(List.of("cp_messages", "cp_fwd_elg", "cp_cpd_elg", "cp_cpwd_elg", "cp_fwk_elg", "cp_mq_mng_elg",
                "cp_spike_log", "cp_spike_csv"), java.util.Arrays.stream(Family.values()).map(Family::sourceKey).toList());
        assertTrue(Family.CP_SPIKE_CSV.isReadEligibleName("spike_detective.csv.12"));
        assertTrue(!Family.CP_SPIKE_CSV.isReadEligibleName("spike_detective.csvx"));
        assertNull(RangedFetchStatus.CAP_REACHED.forcedSourceOutcome());
        assertEquals("UNKNOWN", RangedFetchStatus.FILE_MISSING.forcedSourceOutcome());
    }

    private static void assertRefused(Refusal expected, GateRegistryPort gates, ReadRequest request) {
        RecordingTransport transport = new RecordingTransport();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RangedFetchResult result = CpSyslogRangedRead.read(transport, SESSION, gates, request, sink);
        assertEquals(RangedFetchStatus.REFUSED, result.status());
        assertEquals(expected.name(), result.reasonCode());
        assertEquals(0, result.bytesRequested());
        assertEquals(0, result.bytesReceived());
        assertTrue(transport.specs.isEmpty(), "refused before any I/O");
        assertEquals(0, sink.size());
    }
}
