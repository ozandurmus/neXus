package com.securityexpert.nexus.ui2.worker.supportbundle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.supportbundle.PanSupportBundle;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamHandler;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamOutcome;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;

/** The {@code pan_support_bundle} executor against a scripted XML API (no socket or device). Every value is synthetic. */
class PanSupportBundleJobExecutorTest {
    private static final String RESOURCES = "<response status=\"success\"><result><![CDATA[top - 01:00:00 up 3 days\n"
            + "%Cpu(s):  2.3 us,  1.6 sy,  0.0 ni, 95.9 id,  0.0 wa,  0.0 hi,  0.2 si,  0.0 st\n]]></result></response>";
    private static final String DISK = "<response status=\"success\"><result><![CDATA[Filesystem Size Used Avail Use% Mounted on\n"
            + "/dev/root 7.0G 3.3G 3.4G 50% /\n/dev/sda5 19G 1.2G 17G 7% /opt/panlogs\n]]></result></response>";

    static final class Pan implements DeviceTransport {
        final List<String> calls = new ArrayList<>();
        final List<Map<String, String>> params = new ArrayList<>();
        final List<Duration> pauses = new ArrayList<>();
        String resources = RESOURCES, disk = DISK;
        String startAnswer = "<response status=\"success\"><result><msg><line>Job enqueued</line></msg><job>42</job></result></response>";
        int finAfter = 2;                 // status polls that answer ACT before FIN (-1: never)
        String finAnswer = "<response status=\"success\"><result><job><status>FIN</status><result>OK</result></job></result></response>";
        String finishAnswer = "<response status=\"success\"><result>done</result></response>";
        byte[] file = gzipBytes("synthetic tech support");
        boolean keygenFails, getXml, streamFails;
        int polls;

        @Override public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) { throw new AssertionError("connect"); }
        @Override public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) { throw new AssertionError("exec"); }
        @Override public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) { throw new AssertionError("fetch"); }
        @Override public void disconnect(TransportSession session) { }
        @Override public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
            params.add(spec.formParams());
            if ("keygen".equals(spec.type())) {
                calls.add("keygen");
                return keygenFails ? new XmlApiResult.Completed(403, "<response status=\"error\"/>")
                        : new XmlApiResult.Completed(200, "<response status = 'success'><result><key>SYNTHKEY</key></result></response>");
            }
            assertEquals("SYNTHKEY", spec.headers().get("X-PAN-KEY"));
            String action = spec.formParams().get("action");
            String cmd = spec.formParams().get("cmd");
            if ("op".equals(spec.type())) {
                calls.add("op:" + cmd);
                return new XmlApiResult.Completed(200, cmd.contains("resources") ? resources : disk);
            }
            assertEquals("export", spec.type());
            assertEquals("tech-support", spec.formParams().get("category"));
            if (action == null) { calls.add("start"); return new XmlApiResult.Completed(200, startAnswer); }
            if ("status".equals(action)) {
                calls.add("status:" + spec.formParams().get("job-id"));
                polls++;
                boolean fin = finAfter >= 0 && polls > finAfter;
                return new XmlApiResult.Completed(200, fin ? finAnswer
                        : "<response status=\"success\"><result><job><status>ACT</status><result>PEND</result></job></result></response>");
            }
            if ("finish".equals(action)) { calls.add("finish:" + spec.formParams().get("job-id")); return new XmlApiResult.Completed(200, finishAnswer); }
            throw new AssertionError(action);
        }
        @Override public <T> XmlApiStreamOutcome<T> xmlApiCallStreaming(ApiTarget target, XmlApiSpec spec, Duration timeout,
                XmlApiStreamHandler<T> handler) {
            assertEquals("get", spec.formParams().get("action"));
            calls.add("get:" + spec.formParams().get("job-id"));
            if (streamFails) return new XmlApiStreamOutcome.Failed<>("synthetic");
            byte[] body = getXml ? "<response status=\"error\"><msg>not allowed</msg></response>".getBytes(StandardCharsets.UTF_8) : file;
            try (InputStream in = new ByteArrayInputStream(body)) {
                return new XmlApiStreamOutcome.Completed<>(200, handler.handle(in));
            } catch (IOException e) { return new XmlApiStreamOutcome.Failed<>("io"); }
        }
    }

    static byte[] gzipBytes(String text) {
        try {
            var out = new ByteArrayOutputStream();
            try (var gz = new GZIPOutputStream(out)) { gz.write(text.getBytes(StandardCharsets.UTF_8)); }
            return out.toByteArray();
        } catch (IOException e) { throw new AssertionError(e); }
    }

    private BundleHarness harness(Path dir, String role) { return new BundleHarness(dir, "palo_alto", role, "pan_xml_api", null); }

    private void run(BundleHarness h, BundleHarness.Store store, Pan pan) {
        new PanSupportBundleJobExecutor(h.leases, h.attempts, h.devices, pan, ref -> new PanCredentialMaterial("synthetic-user", "synthetic-pass".toCharArray()),
                h.gates(), store, h.artefacts, BundleHarness.fingerprint(), d -> pan.pauses.add(d), BundleHarness.clock())
                .execute("job-1", 1, "device-1", "192.0.2.1", 22, "credential-ref");
    }

    private BundleHarness.Store store(String scope) { return new BundleHarness.Store("palo_alto", scope, "pan_tsf"); }

    @Test
    void startsPollsEveryFifteenSecondsAndStreamsTheFileIntoTheVault(@TempDir Path dir) throws Exception {
        var h = harness(dir, "gateway");
        var pan = new Pan();
        var store = store("pan_firewall");
        run(h, store, pan);
        assertEquals(List.of("keygen", "op:<show><system><resources></resources></system></show>",
                "op:<show><system><disk-space></disk-space></system></show>", "start", "status:42", "status:42", "status:42", "get:42"), pan.calls);
        assertEquals(List.of("pan_sb_system_resources", "pan_sb_disk_space", "pan_sb_tsf_start", "pan_sb_tsf_status", "pan_sb_tsf_status",
                "pan_sb_tsf_status", "pan_sb_tsf_get"), h.gateIds);
        assertEquals(List.of("read", "read", "recovery-write", "read", "read", "read", "read"), h.classes);
        assertEquals(List.of(Duration.ofSeconds(15), Duration.ofSeconds(15)), pan.pauses);
        assertEquals("COMPLETED", store.state("pan_tsf"));
        assertEquals("items/pan_tsf/techsupport.tgz", store.entryNames.get(store.id("pan_tsf")));
        assertArrayEquals(pan.file, h.read(store.manifests.get(store.id("pan_tsf"))), "stored as received, unmasked");
        assertEquals("support_bundle", store.manifests.get(store.id("pan_tsf")).artefactClass());
        assertEquals("COMPLETED", store.requestState);
        assertEquals("COMPLETED", h.finalState);
        assertFalse(pan.calls.contains("finish:42"));
    }

    @Test
    void aPanoramaUsesItsOwnGateRows(@TempDir Path dir) {
        var h = harness(dir, "management_server");
        var pan = new Pan();
        var store = store("panorama");
        run(h, store, pan);
        assertEquals("COMPLETED", store.state("pan_tsf"));
        assertEquals("panorama_sb_system_resources", h.gateIds.get(0));
        assertEquals("panorama_sb_tsf_get", h.gateIds.get(h.gateIds.size() - 1));
    }

    @Test
    void aBusyManagementPlaneRefusesBeforeAnythingIsStarted(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var pan = new Pan();
        pan.resources = RESOURCES.replace("95.9 id", " 9.0 id");
        var store = store("pan_firewall");
        run(h, store, pan);
        assertEquals("REFUSED_PRECHECK", store.state("pan_tsf"));
        assertEquals("CPU_OVER_LIMIT", store.code("pan_tsf"));
        assertEquals("management CPU 91% exceeds 80% limit", store.details.get(store.id("pan_tsf")));
        assertFalse(pan.calls.contains("start"));
        assertEquals("FAILED", store.requestState);
    }

    @Test
    void aFullDiskRefusesBeforeAnythingIsStarted(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var pan = new Pan();
        pan.disk = DISK.replace("7% /opt/panlogs", "93% /opt/panlogs");
        var store = store("pan_firewall");
        run(h, store, pan);
        assertEquals("DISK_OVER_LIMIT", store.code("pan_tsf"));
        assertEquals("disk /opt/panlogs at 93% exceeds 90% limit", store.details.get(store.id("pan_tsf")));
        assertFalse(pan.calls.contains("start"));
    }

    @Test
    void unreadablePrechecksAreRefusedNotAssumedFine(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var pan = new Pan();
        pan.disk = "<response status=\"error\"><msg>nope</msg></response>";
        var store = store("pan_firewall");
        run(h, store, pan);
        assertEquals("PRECHECK_UNKNOWN", store.code("pan_tsf"));
        assertFalse(pan.calls.contains("start"));
    }

    @Test
    void aStartThatTheDeviceRefusesIsUnsupportedAndNeverRetried(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var pan = new Pan();
        pan.startAnswer = "<response status=\"error\" code=\"7\"><msg>Command not allowed</msg></response>";
        var store = store("pan_firewall");
        run(h, store, pan);
        assertEquals("UNSUPPORTED", store.state("pan_tsf"));
        assertEquals(1, pan.calls.stream().filter("start"::equals).count());
    }

    @Test
    void aJobNumberThatIsNotDigitsIsNeverSentBackAndTheOutcomeIsUnknown(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var pan = new Pan();
        pan.startAnswer = "<response status=\"success\"><result><job>4x2; rm</job></result></response>";
        var store = store("pan_firewall");
        run(h, store, pan);
        assertEquals("OUTCOME_UNKNOWN", store.state("pan_tsf"));
        assertEquals("JOB_ID_UNREADABLE", store.code("pan_tsf"));
        assertTrue(pan.calls.stream().noneMatch(c -> c.startsWith("status") || c.startsWith("get") || c.startsWith("finish")), pan.calls.toString());
    }

    @Test
    void theThirtyMinuteDeadlineSendsOneFinishAndIsTimedOut(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var pan = new Pan();
        pan.finAfter = -1;
        var store = store("pan_firewall");
        run(h, store, pan);
        assertEquals("TIMED_OUT", store.state("pan_tsf"));
        assertEquals(PanSupportBundle.MAX_POLLS, pan.calls.stream().filter(c -> c.startsWith("status")).count());
        assertEquals(1, pan.calls.stream().filter("finish:42"::equals).count());
        assertEquals("pan_sb_tsf_finish", h.gateIds.get(h.gateIds.size() - 1));
        assertEquals("recovery-write", h.classes.get(h.classes.size() - 1));
        assertFalse(pan.calls.contains("get:42"));
        assertTrue(store.manifests.isEmpty());
        assertEquals(119L, pan.pauses.size());
        assertEquals(Duration.ofSeconds(30 * 60 - 15), pan.pauses.stream().reduce(Duration.ZERO, Duration::plus));
    }

    @Test
    void aFailedJobIsFailedWithoutAFinish(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var pan = new Pan();
        pan.finAnswer = "<response status=\"success\"><result><job><status>FIN</status><result>FAIL</result></job></result></response>";
        var store = store("pan_firewall");
        run(h, store, pan);
        assertEquals("FAILED", store.state("pan_tsf"));
        assertEquals("TSF_JOB_FAILED", store.code("pan_tsf"));
        assertFalse(pan.calls.contains("get:42"));
    }

    @Test
    void anXmlErrorInsteadOfAnArchiveIsRefusedAndNothingIsStored(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var pan = new Pan();
        pan.getXml = true;
        var store = store("pan_firewall");
        run(h, store, pan);
        assertEquals("UNSUPPORTED", store.state("pan_tsf"));
        assertEquals("DEVICE_REFUSED", store.code("pan_tsf"));
        assertTrue(store.manifests.isEmpty());
    }

    @Test
    void aStreamThatNeverStartsFailsTheItemWithoutARetry(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var pan = new Pan();
        pan.streamFails = true;
        var store = store("pan_firewall");
        run(h, store, pan);
        assertEquals("FAILED", store.state("pan_tsf"));
        assertEquals(1, pan.calls.stream().filter("get:42"::equals).count());
        assertTrue(store.manifests.isEmpty());
    }

    @Test
    void anAuthenticationFailureFailsTheItemBeforeAnyRequest(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var pan = new Pan();
        pan.keygenFails = true;
        var store = store("pan_firewall");
        run(h, store, pan);
        assertEquals("AUTH_UNAVAILABLE", store.code("pan_tsf"));
        assertEquals(List.of("keygen"), pan.calls);
        assertEquals("FAILED", h.finalState);
    }

    @Test
    void aGateThatIsNotSignedOffRejectsTheJobBeforeAnyContact(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var pan = new Pan();
        h.rows = h.rows.stream().filter(r -> !r.gateId().equals("pan_sb_tsf_finish")).toList();
        var store = store("pan_firewall");
        run(h, store, pan);
        assertEquals("REJECTED", h.finalState);
        assertTrue(pan.calls.isEmpty());
    }

    @Test
    void theStreamCopyChecksTheGzipMagicAndTheBound() throws Exception {
        var out = new ByteArrayOutputStream();
        var ok = PanSupportBundleJobExecutor.copy(new ByteArrayInputStream(gzipBytes("x")), out);
        assertTrue(ok.gzip());
        assertFalse(ok.tooLarge());
        assertEquals(ok.bytes(), out.size());
        var html = PanSupportBundleJobExecutor.copy(new ByteArrayInputStream("<html>no</html>".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
        assertFalse(html.gzip());
        assertEquals("xml", html.xml());
        var other = PanSupportBundleJobExecutor.copy(new ByteArrayInputStream("plain".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
        assertEquals("other", other.xml());
        var empty = PanSupportBundleJobExecutor.copy(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        assertEquals(0, empty.bytes());
        // Over the 2 GiB bound: a lazily generated gzip-magic stream, nothing buffered.
        InputStream endless = new InputStream() {
            long n;
            @Override public int read() { n++; return n == 1 ? 0x1f : n == 2 ? 0x8b : 0; }
            @Override public int read(byte[] b, int off, int len) { for (int i = 0; i < len; i++) b[off + i] = (byte) read(); return len; }
        };
        long[] written = {0};
        var big = PanSupportBundleJobExecutor.copy(endless, new java.io.OutputStream() {
            @Override public void write(int b) { written[0]++; }
            @Override public void write(byte[] b, int off, int len) { written[0] += len; }
        });
        assertTrue(big.tooLarge());
        assertTrue(written[0] <= PanSupportBundle.TSF_MAX_BYTES);
    }

    @Test
    void parsersReadThePanOsShapes() {
        assertEquals(5, PanSupportBundleJobExecutor.cpuPercent(RESOURCES));
        assertNull(PanSupportBundleJobExecutor.cpuPercent("<response status=\"success\"><result>no cpu</result></response>"));
        var disk = PanSupportBundleJobExecutor.diskPercent(DISK);
        assertEquals(50, disk[0]);
        assertEquals(7, disk[1]);
        assertNull(PanSupportBundleJobExecutor.diskPercent("garbled")[0]);
    }
}
