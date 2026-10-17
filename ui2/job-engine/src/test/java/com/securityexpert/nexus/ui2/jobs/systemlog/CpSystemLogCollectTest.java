package com.securityexpert.nexus.ui2.jobs.systemlog;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSystemLogCollect.Exec;
import com.securityexpert.nexus.ui2.jobs.systemlog.CpSystemLogCollect.FileKind;
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

/** The Check Point collection reads resolve to their own V160 rows only, and C8 refuses before any I/O. */
class CpSystemLogCollectTest {
    private final List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
            .getResourceAsStream("capabilities/gate_registry_fixture.yaml")).stream().toList();
    private final GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();

    @Test
    void everyExecReadResolvesToItsOwnSignedOffCollectionRowNeverAMeasurementRow() {
        for (Exec exec : Exec.values()) {
            var gate = CpSystemLogCollect.gate(exec, gates).orElseThrow(() -> new AssertionError(exec.name()));
            assertEquals(exec.gateId(), gate.gateId());
            assertEquals(30, gate.timeoutS());
            var row = rows.stream().filter(r -> r.gateId().equals(exec.gateId())).findFirst().orElseThrow();
            assertEquals(row.canonicalCommandKey(), exec.command(), "byte-identical to the V160 row");
            assertEquals(row.shellContext(), exec.shellContext());
        }
        assertEquals("cp_syslog_collect_fetch", CpSystemLogCollect.fetchGate(gates).orElseThrow().gateId());
        GateRegistryPort measurementOnly = key -> rows.stream()
                .filter(r -> r.key().equals(key) && r.gateId().startsWith("cp_syslog_measure")).toList();
        for (Exec exec : Exec.values()) assertTrue(CpSystemLogCollect.gate(exec, measurementOnly).isEmpty(), exec.name());
    }

    @Test
    void theWholeFileFetchChecksNameListingAndBoundBeforeAnyIoAndAsksOneBytePastTheBound() {
        List<String> sent = new ArrayList<>();
        DeviceTransport transport = new DeviceTransport() {
            @Override public ConnectResult connect(ConnectionTarget t, ConnectSpec s, Duration d) { throw new AssertionError(); }
            @Override public ExecResult exec(TransportSession s, ExecSpec e, Duration d) { throw new AssertionError(); }
            @Override public FetchResult fetch(TransportSession s, FetchSpec f, Duration d) { throw new AssertionError(); }
            @Override public XmlApiResult xmlApiCall(ApiTarget t, XmlApiSpec x, Duration d) { throw new AssertionError(); }
            @Override public void disconnect(TransportSession s) { }
            @Override public RangedFetchResult fetchBounded(TransportSession s, RangedFetchSpec spec, Duration timeout,
                    OutputStream sink, String nameClass) {
                sent.add(spec.remotePath() + "|" + spec.maxBytes() + "|" + timeout.toSeconds() + "|" + nameClass);
                return new RangedFetchResult(RangedFetchStatus.END_OF_FILE, 0, spec.maxBytes(), 0, java.util.OptionalLong.of(0), true, "");
            }
        };
        TransportSession session = () -> "s";
        var sink = new ByteArrayOutputStream();
        assertEquals("NAME_NOT_ALLOWED", CpSystemLogCollect.fetch(transport, session, gates, FileKind.SAR_DAILY,
                Set.of("sa16"), "sa16", 10, sink).reasonCode(), "the binary sa<DD> is never fetched");
        assertEquals("NAME_NOT_LISTED", CpSystemLogCollect.fetch(transport, session, gates, FileKind.SAR_DAILY,
                Set.of(), "sar16", 10, sink).reasonCode());
        assertEquals("NAME_NOT_ALLOWED", CpSystemLogCollect.fetch(transport, session, gates, FileKind.HCP_REPORT,
                Set.of("../x.tar.gz"), "../x.tar.gz", 10, sink).reasonCode());
        assertEquals("SIZE_ABOVE_BOUND", CpSystemLogCollect.fetch(transport, session, gates, FileKind.HCP_REPORT,
                Set.of("a.tar.gz"), "a.tar.gz", 16L * 1024 * 1024 + 1, sink).reasonCode());
        assertEquals("GATE_NOT_KNOWN", CpSystemLogCollect.fetch(transport, session, key -> List.of(), FileKind.HCP_REPORT,
                Set.of("a.tar.gz"), "a.tar.gz", 10, sink).reasonCode());
        assertTrue(sent.isEmpty());
        CpSystemLogCollect.fetch(transport, session, gates, FileKind.SAR_DAILY, Set.of("sar16"), "sar16", 10, sink);
        CpSystemLogCollect.fetch(transport, session, gates, FileKind.HCP_REPORT, Set.of("a.tar.gz"), "a.tar.gz", 10, sink);
        assertEquals(List.of("/var/log/sa/sar16|" + (32L * 1024 * 1024 + 1) + "|120|cp_sar_daily",
                "/var/log/hcp/last/a.tar.gz|" + (16L * 1024 * 1024 + 1) + "|300|cp_hcp_report"), sent);
    }
}
