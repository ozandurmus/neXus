package com.securityexpert.nexus.ui2.worker.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundle;
import com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundleHeavy;
import com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundleHeavy.Step;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchStreamResult;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;

/**
 * Step S6: the Check Point heavy items against a scripted device (no socket, no device, synthetic masked values only):
 * work directory per item, detached start never retried, 30 s polling to the deadline, digest compare before removal,
 * cleanup retried once, the VSX per virtual system runs, the SMS variant, the management scope and the optional
 * cpdata_collector item.
 */
class CpSupportBundleHeavyExecutorTest {
    private static final byte[] BUNDLE = "synthetic bundle bytes".getBytes(StandardCharsets.UTF_8);
    private static final String REPORT = "hcp_report_fwtango04_15_11_26_03_00.tar.gz";
    private static final String CPU_OK = "|CPU#|User Time(%)|System Time(%)|Idle Time(%)|Usage(%)|\n|   1|  5|  3|  92|   8|\n|   2|  20|  10|  60|  40|\n";
    private static final String CPU_HIGH = "|CPU#|User Time(%)|System Time(%)|Idle Time(%)|Usage(%)|\n|   1|  50|  30|  20|  80|\n";
    private static String df(long freeBytes) {
        return "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/sda3 99999999 1 " + (freeBytes / 1024) + " 1% /var/log\n";
    }

    static String sha(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (Exception e) { throw new AssertionError(e); }
    }

    static final class Dev implements DeviceTransport {
        final List<String> commands = new ArrayList<>();
        String cpu = CPU_OK, df = df(50L << 30), digest = sha(BUNDLE);
        boolean cpdataPresent, startTimesOut, startRefused, removeAlwaysFails, deadlineNoRc;
        int removeFailuresLeft, pollsBeforeRc = 1, polls;
        String rc = "0";
        byte[] bundle = BUNDLE;
        String workdirListing = "-rw-r--r-- 1 admin root 100 Nov 15 03:00 hcp.rc\n-rw-r--r-- 1 admin root 100 Nov 15 03:00 cpinfo.rc\n"
                + "-rw-r--r-- 1 admin root 100 Nov 15 03:00 cpsdc.rc\n-rw-r--r-- 1 admin root 100 Nov 15 03:00 cpsdc.tgz\n";
        long fetchedCap;

        @Override public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            return new ConnectResult.Authenticated(() -> "s1");
        }
        @Override public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            String c = spec.command();
            commands.add(c);
            assertTrue(spec.outputLimitBytes() > 0);
            if (c.contains("cpstat os -f multi_cpu")) return new ExecResult.Completed(cpu, 0);
            if (c.contains("df -P /var/log")) return new ExecResult.Completed(df, 0);
            if (c.contains("command -v cpdata_collector")) return cpdataPresent ? new ExecResult.Completed("/opt/CPsuite/bin/cpdata_collector\n", 0) : new ExecResult.Completed("", 1);
            if (c.contains("cpdata_collector --list")) return new ExecResult.Completed("synthetic list\n", 0);
            if (c.contains("setsid nohup")) {
                if (startTimesOut) return new ExecResult.TimedOut();
                return new ExecResult.Completed("", startRefused ? 1 : 0);
            }
            if (c.contains("ls -lgo")) return new ExecResult.Completed("-rw-r--r--. 1 1234 1000000 1790086400 " + REPORT + "\n", 0);
            if (c.contains("/hcp.rc") || c.contains("/cpinfo.rc") || c.contains("/cpsdc.rc")) {
                polls++;
                return !deadlineNoRc && polls >= pollsBeforeRc ? new ExecResult.Completed(rc + "\n", 0) : new ExecResult.Completed("cat: no such file", 1);
            }
            if (c.contains("ls -l /var/log/nexus-sb-")) return new ExecResult.Completed(workdirListing, 0);
            if (c.contains("sha256sum")) return new ExecResult.Completed(digest + "  /var/log/nexus-sb-x.tgz\n", 0);
            if (c.contains("rm -rf")) {
                if (removeAlwaysFails || removeFailuresLeft-- > 0) return new ExecResult.Completed("rm: failed", 1);
                return new ExecResult.Completed("", 0);
            }
            return new ExecResult.Completed("", 0);   // mkdir, cp, tar
        }
        @Override public FetchStreamResult fetchStreaming(TransportSession session, FetchSpec spec, Duration timeout, OutputStream sink) {
            commands.add("SFTP " + spec.remotePath());
            fetchedCap = spec.maxBytes();
            try { sink.write(bundle); } catch (IOException e) { throw new AssertionError(e); }
            return new FetchStreamResult.Fetched(bundle.length);
        }
        @Override public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) { throw new AssertionError("fetch"); }
        @Override public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) { throw new AssertionError("xml"); }
        @Override public void disconnect(TransportSession session) { }

        long count(String fragment) { return commands.stream().filter(c -> c.contains(fragment)).count(); }
    }

    static final class HStore extends BundleHarness.Store {
        List<Integer> vs = List.of();
        boolean mds;
        final List<String> workDirs = new ArrayList<>();
        int cleaned;
        HStore(String... kinds) { super("check_point", "cp_gaia_gateway", kinds); }
        void addVsItems(String kind, int... ids) {
            items.removeIf(i -> i.kind().equals(kind));
            for (int id : ids) items.add(new ItemRow(UUID.randomUUID(), BundleHarness.REQUEST, items.size() + 1, kind, id, "QUEUED", null, null,
                    null, null, null, null, "none", List.of(), null, null));
        }
        @Override public void markWorkDir(UUID requestId, String path) { workDirs.add(path); }
        @Override public void markWorkDirCleaned(UUID requestId) { cleaned++; }
        @Override public boolean multiDomainServer(String deviceId) { return mds; }
        @Override public List<Integer> virtualSystemIds(String deviceId) { return vs; }
        String stateAt(int ordinal) { return states.get(items.get(ordinal).id()); }
        String codeAt(int ordinal) { return codes.get(items.get(ordinal).id()); }
    }

    private BundleHarness harness(Path dir, String role) {
        return new BundleHarness(dir, "check_point", role, "ssh_exec", null);
    }

    private List<Duration> run(BundleHarness h, Dev dev, HStore store) {
        List<Duration> slept = new ArrayList<>();
        new CpSupportBundleJobExecutor(h.leases, h.attempts, h.devices, dev, h.gates(), store, h.artefacts,
                BundleHarness.fingerprint(), BundleHarness.clock(), slept::add).execute("job-1", 1, "device-1", "192.0.2.1", 22, "credential-ref");
        return slept;
    }

    private static String dirOf(HStore store, int i) { return store.workDirs.get(i); }

    private static String normalize(String c) {
        return c.replaceAll("/var/log/nexus-sb-[a-f0-9]{12}", "@D").replaceAll("hcp_report_[A-Za-z0-9._-]+\\.tar\\.gz", "@N")
                .replaceAll("vsenv [0-9]+", "vsenv @V").replaceAll("-s -c [0-9]+", "-s -c @V");
    }

    private static boolean isApproved(String command) {
        if (command.startsWith("SFTP ")) return command.matches("SFTP /var/log/nexus-sb-[a-f0-9]{12}\\.tgz");
        String n = normalize(command);
        for (Step s : Step.values()) {
            String t = s.template().replace("%1$s", "@D").replace("%s", "@D").replace("<VSID>", "@V").replace("<listed name>", "@N");
            if (t.equals(n) || (s == Step.POLL_RC && (n.equals(t.replace("<hcp|cpinfo>", "hcp")) || n.equals(t.replace("<hcp|cpinfo>", "cpinfo"))))) return true;
        }
        for (CpSupportBundle.Exec e : CpSupportBundle.Exec.values()) if (e.command().equals(n)) return true;
        return false;
    }

    private static int indexOf(Dev d, String fragment) {
        for (int i = 0; i < d.commands.size(); i++) if (d.commands.get(i).contains(fragment)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void hcpRunHappyPathUsesOneWorkDirectoryAndRemovesItOnlyAfterTheDigestMatches(@TempDir Path dir) throws Exception {
        var h = harness(dir, "gateway");
        var dev = new Dev();
        var store = new HStore("cp_hcp_run");
        var slept = run(h, dev, store);
        assertEquals("COMPLETED", store.stateAt(0), store.codeAt(0));
        assertEquals("COMPLETED", store.requestState);
        assertEquals("COMPLETED", h.finalState);
        String wd = dirOf(store, 0);
        assertTrue(CpSupportBundleHeavy.WORK_DIR.matcher(wd).matches());
        assertEquals(List.of(wd), store.workDirs);
        assertEquals(1, store.cleaned);
        for (String c : dev.commands) assertTrue(isApproved(c), c);
        assertEquals(1, dev.count("setsid nohup"));
        assertTrue(dev.commands.stream().anyMatch(c -> c.contains("hcp -r all --include-wts yes -s > " + wd + "/hcp.log")));
        // order: prechecks, mkdir, start, poll, listing, copy, ls, tar, sha, fetch, remove last
        List<String> order = List.of("cpstat os -f multi_cpu", "df -P /var/log", "mkdir -p " + wd, "setsid nohup", "cat " + wd + "/hcp.rc",
                "ls -lgo", "cp /var/log/hcp/last/" + REPORT + " " + wd + "/", "ls -l " + wd, "tar -czf", "sha256sum", "SFTP " + wd + ".tgz", "rm -rf " + wd + " " + wd + ".tgz");
        int last = -1;
        for (String f : order) { int at = indexOf(dev, f); assertTrue(at > last, f + " at " + at); last = at; }
        assertEquals(dev.commands.size() - 1, last, "the removal is the last command");
        assertEquals(List.of(CpSupportBundleHeavy.POLL_INTERVAL), slept);   // 30 s, then the poll that finds the exit code
        assertEquals(BUNDLE.length, h.read(store.manifests.get(store.items.get(0).id())).length);
        assertEquals("items/cp_hcp_run/bundle.tgz", store.entryNames.get(store.items.get(0).id()));
        assertTrue(h.classes.contains("recovery-write") && h.classes.contains("read"));
        // the ledger records the writes as recovery-write: mkdir, start, cp, tar, rm
        assertEquals(5, h.classes.stream().filter("recovery-write"::equals).count());
        assertEquals(CpSupportBundleHeavy.HCP_BUNDLE_MAX_BYTES + 1, dev.fetchedCap);
    }

    @Test
    void cpinfoGatewayUsesTheDesignedLiteralAndAVsxRunsOncePerVirtualSystemEachInItsOwnDirectory(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var dev = new Dev();
        var store = new HStore("cp_cpinfo");
        store.vs = List.of(1, 3);
        store.addVsItems("cp_cpinfo", 1, 3);
        run(h, dev, store);
        assertEquals("COMPLETED", store.stateAt(0));
        assertEquals("COMPLETED", store.stateAt(1));
        assertEquals(2, store.workDirs.size());
        assertFalse(store.workDirs.get(0).equals(store.workDirs.get(1)));
        assertEquals(2, store.cleaned);
        assertEquals(1, dev.count("vsenv 1 >/dev/null 2>&1 && cpinfo -z -D -d -S -o " + store.workDirs.get(0) + "/cpinfo"));
        assertEquals(1, dev.count("vsenv 3 >/dev/null 2>&1 && cpinfo -z -D -d -S -o " + store.workDirs.get(1) + "/cpinfo"));
        assertEquals(0, dev.count("-S -x"));
        for (String c : dev.commands) assertTrue(isApproved(c), c);
        assertEquals("items/cp_cpinfo/vs3/bundle.tgz", store.entryNames.get(store.items.get(1).id()));
        // C1 and C2 once per run, not per item
        assertEquals(1, dev.count("cpstat os -f multi_cpu"));
    }

    @Test
    void aVirtualSystemThatInventoryDoesNotListIsNeverCollected(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var dev = new Dev();
        var store = new HStore("cp_hcp_run");
        store.vs = List.of(1);
        store.addVsItems("cp_hcp_run", 7);
        run(h, dev, store);
        assertEquals("FAILED", store.stateAt(0));
        assertEquals("INVALID_VS", store.codeAt(0));
        assertEquals(0, dev.count("setsid nohup"));
        assertEquals(0, dev.count("mkdir"));
    }

    @Test
    void aSecurityManagementServerAddsTheSmsFlagAndAnMdsDoesNotUseTheManagementGateIds(@TempDir Path dir) {
        var h = harness(dir, "management_server");
        var dev = new Dev();
        var store = new HStore("cp_cpinfo");
        run(h, dev, store);
        assertEquals("COMPLETED", store.stateAt(0));
        assertEquals(1, dev.count("cpinfo -z -D -d -S -x -o "));
        assertTrue(h.gateIds.stream().allMatch(g -> g.startsWith("cpm_sb_")), h.gateIds.toString());
        for (String c : dev.commands) assertTrue(isApproved(c), c);
        var h2 = harness(dir.resolve("mds"), "management_server");
        var dev2 = new Dev();
        var store2 = new HStore("cp_cpinfo");
        store2.mds = true;
        run(h2, dev2, store2);
        assertEquals("COMPLETED", store2.stateAt(0));
        assertEquals(0, dev2.count("-S -x"));
        assertEquals(1, dev2.count("cpinfo -z -D -d -S -o "));
    }

    @Test
    void aBusyCpuOrLowDiskRefusesHeavyItemsWithTheMeasuredValueAndTouchesNothing(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var dev = new Dev();
        dev.cpu = CPU_HIGH;
        var store = new HStore("cp_hcp_run", "cp_cpinfo");
        run(h, dev, store);
        assertEquals("REFUSED_PRECHECK", store.stateAt(0));
        assertEquals("CPU_HIGH_OR_UNKNOWN", store.codeAt(0));
        assertTrue(store.details.get(store.items.get(0).id()).contains("80"));
        assertEquals("REFUSED_PRECHECK", store.stateAt(1));
        assertEquals(0, dev.count("mkdir") + dev.count("setsid") + dev.count("rm -rf"));
        assertTrue(store.workDirs.isEmpty());
        // 3 GiB free: the HCP run (2 GiB) goes, cpinfo (5 GiB) is refused
        var h2 = harness(dir.resolve("b"), "gateway");
        var dev2 = new Dev();
        dev2.df = df(3L << 30);
        var store2 = new HStore("cp_hcp_run", "cp_cpinfo");
        run(h2, dev2, store2);
        assertEquals("COMPLETED", store2.stateAt(0));
        assertEquals("REFUSED_PRECHECK", store2.stateAt(1));
        assertEquals("DISK_LOW_OR_UNKNOWN", store2.codeAt(1));
        // an unreadable value is never assumed fine
        var h3 = harness(dir.resolve("c"), "gateway");
        var dev3 = new Dev();
        dev3.cpu = "garbage";
        var store3 = new HStore("cp_hcp_run");
        run(h3, dev3, store3);
        assertEquals("REFUSED_PRECHECK", store3.stateAt(0));
        assertEquals(0, dev3.count("mkdir"));
    }

    @Test
    void aTimedOutStartIsPolledAndNeverSentTwice(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var dev = new Dev();
        dev.startTimesOut = true;
        var store = new HStore("cp_hcp_run");
        run(h, dev, store);
        assertEquals(1, dev.count("setsid nohup"));
        assertTrue(dev.count("/hcp.rc") >= 1);
        assertEquals("COMPLETED", store.stateAt(0));
    }

    @Test
    void aRefusedStartRemovesTheDirectoryAndIsNotRetried(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var dev = new Dev();
        dev.startRefused = true;
        var store = new HStore("cp_cpinfo");
        run(h, dev, store);
        assertEquals("FAILED", store.stateAt(0));
        assertEquals("START_REFUSED", store.codeAt(0));
        assertEquals(1, dev.count("setsid nohup"));
        assertEquals(1, dev.count("rm -rf"));
        assertEquals(1, store.cleaned);
    }

    @Test
    void anExitCodeOf127IsUnsupportedAndAnotherNonZeroCodeFailsTheItemBothRemoveTheDirectory(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var dev = new Dev();
        dev.rc = "127";
        var store = new HStore("cp_hcp_run");
        run(h, dev, store);
        assertEquals("UNSUPPORTED", store.stateAt(0));
        assertEquals("TOOL_NOT_INSTALLED", store.codeAt(0));
        assertEquals(1, dev.count("rm -rf"));
        var h2 = harness(dir.resolve("b"), "gateway");
        var dev2 = new Dev();
        dev2.rc = "3";
        var store2 = new HStore("cp_cpinfo");
        run(h2, dev2, store2);
        assertEquals("FAILED", store2.stateAt(0));
        assertEquals("TOOL_EXIT_3", store2.codeAt(0));
        assertEquals(1, dev2.count("rm -rf"));
        assertEquals(1, store2.cleaned);
    }

    @Test
    void noExitCodeByTheDeadlineIsOutcomeUnknownTheDirectoryStaysAndLaterHeavyItemsAreRefused(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var dev = new Dev();
        dev.deadlineNoRc = true;
        var store = new HStore("cp_hcp_run", "cp_cpinfo");
        var slept = run(h, dev, store);
        assertEquals("OUTCOME_UNKNOWN", store.stateAt(0));
        assertEquals("NO_EXIT_CODE_BY_DEADLINE", store.codeAt(0));
        assertEquals(240, dev.commands.stream().filter(c -> c.contains("cat ") && c.contains("/hcp.rc")).count(), "every 30 s for two hours");
        assertEquals(240, slept.size());
        assertEquals(0, dev.count("rm -rf"));
        assertEquals(0, store.cleaned);
        assertEquals("REFUSED_PRECHECK", store.stateAt(1));
        assertEquals("PRIOR_WORKDIR_LEFT", store.codeAt(1));
        assertEquals(1, dev.count("setsid nohup"));
        assertEquals(List.of(store.workDirs.get(0)), store.workDirs);
    }

    @Test
    void aDigestMismatchStoresNothingAndRemovesNothing(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var dev = new Dev();
        dev.digest = "0".repeat(64);
        var store = new HStore("cp_hcp_run");
        run(h, dev, store);
        assertEquals("DIGEST_MISMATCH", store.stateAt(0));
        assertTrue(store.manifests.isEmpty());
        assertEquals(0, dev.count("rm -rf"));
        assertEquals(0, store.cleaned);
    }

    @Test
    void cleanupIsRetriedOnceThenTheItemKeepsItsBundleAsCleanupFailed(@TempDir Path dir) throws Exception {
        var h = harness(dir, "gateway");
        var dev = new Dev();
        dev.removeFailuresLeft = 1;
        var store = new HStore("cp_hcp_run");
        run(h, dev, store);
        assertEquals("COMPLETED", store.stateAt(0));
        assertEquals(2, dev.count("rm -rf"));
        assertEquals(1, store.cleaned);
        var h2 = harness(dir.resolve("b"), "gateway");
        var dev2 = new Dev();
        dev2.removeAlwaysFails = true;
        var store2 = new HStore("cp_hcp_run", "cp_cpinfo");
        run(h2, dev2, store2);
        assertEquals(2, dev2.count("rm -rf"), "exactly one retry");
        assertEquals("PARTIAL", store2.stateAt(0));
        assertEquals("CLEANUP_FAILED", store2.codeAt(0));
        assertTrue(store2.manifests.containsKey(store2.items.get(0).id()), "the verified bundle is kept");
        assertEquals(0, store2.cleaned);
        assertEquals("PRIOR_WORKDIR_LEFT", store2.codeAt(1));
    }

    @Test
    void theCpdataCollectorItemRunsOnlyWhenPresentPollsItsRcFileAndNeverTailsTheLog(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        var dev = new Dev();
        var store = new HStore("cpdata_collector_run");
        var slept = run(h, dev, store);
        assertEquals("UNSUPPORTED", store.stateAt(0));
        assertEquals("CPDATA_COLLECTOR_ABSENT", store.codeAt(0));
        assertEquals(0, dev.count("setsid") + dev.count("mkdir"));
        assertTrue(slept.isEmpty());

        var h2 = harness(dir.resolve("b"), "gateway");
        var dev2 = new Dev();
        dev2.cpdataPresent = true;
        var store2 = new HStore("cpdata_collector_present", "cpdata_collector_run");
        var slept2 = run(h2, dev2, store2);
        assertEquals("COMPLETED", store2.stateAt(0));
        assertEquals("COMPLETED", store2.stateAt(1));
        assertEquals(1, dev2.count("command -v cpdata_collector"), "the presence read runs once per run");
        assertEquals(1, dev2.count("cpdata_collector --list"));
        assertEquals(1, dev2.count("timeout 1800 cpdata_collector --run -p " + store2.workDirs.get(0) + "/cpsdc.tgz -t 3 -m 100"));
        assertEquals(1, dev2.commands.stream().filter(c -> c.startsWith(CpSupportBundle.CP_ENV + "cat ") && c.endsWith("/cpsdc.rc")).count(),
                "the rc file is read, and since it answers at once, once");
        assertEquals(0, dev2.count("cpsdc.log"), "the log tail literal is not approved");
        for (String c : dev2.commands) assertTrue(isApproved(c), c);
        assertEquals("items/cpdata_collector_run/bundle.tgz", store2.entryNames.get(store2.items.get(1).id()));
        assertEquals(CpSupportBundleHeavy.CPDATA_MAX_BYTES + 1, dev2.fetchedCap);

        var h3 = harness(dir.resolve("c"), "gateway");
        var dev3 = new Dev();
        dev3.cpdataPresent = true;
        dev3.workdirListing = "-rw-r--r-- 1 admin root 10 Nov 15 03:00 cpsdc.log\n";
        var store3 = new HStore("cpdata_collector_run");
        run(h3, dev3, store3);
        assertEquals("FAILED", store3.stateAt(0));
        assertEquals("NO_CPDATA_ARCHIVE", store3.codeAt(0));
        assertEquals(1, dev3.count("rm -rf"));
        assertEquals(0, dev3.count("tar -czf"));

        // a failed run is recorded by its exit code, and a run that never answers ends at the device bound plus a minute
        var dev4 = new Dev();
        dev4.cpdataPresent = true;
        dev4.rc = "124";
        var store4 = new HStore("cpdata_collector_run");
        run(harness(dir.resolve("d"), "gateway"), dev4, store4);
        assertEquals("FAILED", store4.stateAt(0));
        assertEquals("TOOL_EXIT_124", store4.codeAt(0));
        assertEquals(0, dev4.count("tar -czf"));

        var dev5 = new Dev();
        dev5.cpdataPresent = true;
        dev5.deadlineNoRc = true;
        var store5 = new HStore("cpdata_collector_run");
        var slept5 = run(harness(dir.resolve("e"), "gateway"), dev5, store5);
        assertEquals("TIMED_OUT", store5.stateAt(0));
        assertEquals("NO_EXIT_CODE_BY_DEADLINE", store5.codeAt(0));
        assertTrue(slept5.stream().reduce(Duration.ZERO, Duration::plus).compareTo(Duration.ofSeconds(1860)) >= 0);
        assertEquals(1, dev5.count("rm -rf"));
    }

    @Test
    void aLostLeaseBeforeTheStartSendsNothingAndTheRequestDoesNotStayRunning(@TempDir Path dir) {
        var h = harness(dir, "gateway");
        when(h.attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()))
                .thenAnswer(call -> "attempt-" + call.getArgument(3));
        when(h.attempts.markBoundaryCrossed(anyString(), anyLong())).thenReturn(true);
        when(h.attempts.markBoundaryCrossed(eq("attempt-cp_sb_hcp_start"), anyLong())).thenReturn(false);
        var dev = new Dev();
        var store = new HStore("cp_hcp_run");
        run(h, dev, store);
        assertEquals(0, dev.count("setsid nohup"));
        assertEquals("OUTCOME_UNKNOWN", h.finalState);
        assertEquals("FAILED", store.requestState);
        assertEquals(0, store.cleaned);
    }

    @Test
    void aKindNotSignedOffUnderTheManagementScopeNeverStarts(@TempDir Path dir) {
        var h = harness(dir, "management_server");
        h.rows = h.rows.stream().filter(r -> !r.gateId().equals("cpm_sb_bundle")).toList();
        var dev = new Dev();
        var store = new HStore("cp_hcp_run");
        run(h, dev, store);
        assertTrue(dev.commands.isEmpty(), "no device contact when a gate row is missing");
        assertEquals("REJECTED", h.finalState);
    }
}
