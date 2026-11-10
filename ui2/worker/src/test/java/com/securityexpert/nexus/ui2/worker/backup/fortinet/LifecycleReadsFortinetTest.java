package com.securityexpert.nexus.ui2.worker.backup.fortinet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

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
import com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorExecutor;
import com.securityexpert.nexus.ui2.worker.inventory.InventoryReadPlan;
import com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceCalls;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Credentials;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.DownloadResult;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.TextResponse;

/**
 * Lifecycle reads, group B, Fortinet (PO 2026-11-10): the two FortiGate {@code diagnose} reads and the FortiManager
 * {@code diagnose fmupdate dbcontract} read. Switch off: the command sequence is unchanged. Switch on: exactly the gate literals,
 * after every other read, 60 s, once, and no outcome of the new read changes the inventory result.
 */
class LifecycleReadsFortinetTest {

    private static final String DEVICE = "dev-1";
    private static final Target T = new Target("192.0.2.40", 22);
    private static final String CONTRACT = "diagnose test update info contract";
    private static final String VERSIONS = "diagnose autoupdate versions";
    private static final String DBCONTRACT = "diagnose fmupdate dbcontract";
    private static final String BOTH = "fgt_update_info_contract,fgt_autoupdate_versions";

    private static LifecycleReadSwitch on(String kinds) {
        return LifecycleReadSwitch.from(Map.of("UI2_LIFECYCLE_READS", kinds, "UI2_LIFECYCLE_READ_DEVICES", DEVICE)::get);
    }

    /** A scripted SSH double: logs every command with its timeout; chosen commands time out, fail or throw. */
    private static final class Ssh implements DeviceTransport {
        final Map<String, String> answers = new HashMap<>();
        final Set<String> timeOut = new HashSet<>();
        final Set<String> channelFailed = new HashSet<>();
        final Set<String> throwing = new HashSet<>();
        final List<String> sent = new ArrayList<>();
        final Map<String, Duration> timeouts = new HashMap<>();
        int connects;
        int disconnects;
        boolean refuseConnect;

        @Override
        public ConnectResult connect(ConnectionTarget t, ConnectSpec s, Duration d) {
            connects++;
            if (refuseConnect) {
                return new ConnectResult.TimedOut("synthetic");
            }
            return new ConnectResult.Authenticated(() -> "session-1");
        }

        @Override
        public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
            sent.add(spec.command());
            timeouts.put(spec.command(), timeout);
            if (throwing.contains(spec.command())) {
                throw new IllegalStateException("synthetic");
            }
            if (timeOut.contains(spec.command())) {
                return new ExecResult.TimedOut(false);
            }
            if (channelFailed.contains(spec.command())) {
                return new ExecResult.ChannelFailed("synthetic");
            }
            return new ExecResult.Completed(spec.command() + "\n" + answers.getOrDefault(spec.command(), "") + "\nfg-1 # ", 0);
        }

        @Override public ExecResult exec(TransportSession s, ExecSpec e, Duration d) { throw new AssertionError("exec"); }
        @Override public FetchResult fetch(TransportSession s, FetchSpec f, Duration d) { throw new AssertionError("fetch"); }
        @Override public XmlApiResult xmlApiCall(ApiTarget t, XmlApiSpec s, Duration d) { throw new AssertionError("xml"); }
        @Override public void disconnect(TransportSession s) { disconnects++; }
    }

    // ------------------------------------------------------------------------------------------------- FortiGate

    private static Ssh fortiGate(boolean multiVdom) {
        Ssh ssh = new Ssh();
        ssh.answers.put("get system status", "Version: FortiGate-60F v7.2.4,build1396,230101 (GA)\nHostname: fg-1\nSerial-Number: FGT60F" + "0000000001\n"
                + "Virtual domain configuration: " + (multiVdom ? "multiple" : "disable") + "\n");
        ssh.answers.put("get system ha status", "HA Health Status: OK\nMode: Standalone\n");
        ssh.answers.put(CONTRACT, "FortiCare Support Contract Expiry Date: 2030-01-01\nContracts: FMWR COMP\nCompany: Acme" + "Holdings\nAccount: jane@" + "example.com\n");
        ssh.answers.put(VERSIONS, "AV Engine: Version 7.0\nVirus Definitions: Version 1.0 Expiry Date: 2030-01-01\n");
        return ssh;
    }

    private record FgtRun(String result, List<String> sent, List<String> lines, Ssh ssh) {
    }

    private static FgtRun runFortiGate(Ssh ssh, LifecycleReadSwitch sw) {
        List<String> lines = new ArrayList<>();
        FortiGateExecutor executor = new FortiGateExecutor(ssh, null).withLifecycleReads(sw).withLifecycleSink(lines::add);
        ssh.sent.clear();
        HttpsVendorExecutor.InventoryOutcome outcome = executor.inventory(T, "cred", DEVICE);
        assertTrue(outcome instanceof HttpsVendorExecutor.InventoryOutcome.Completed, String.valueOf(outcome));
        return new FgtRun(outcome.toString(), new ArrayList<>(ssh.sent), lines, ssh);
    }

    @Test
    void theLiteralsAreTheGateKeys() {
        assertEquals(CONTRACT, InventoryReadPlan.FGT_DIAGNOSE_UPDATE_INFO_CONTRACT);
        assertEquals(VERSIONS, InventoryReadPlan.FGT_DIAGNOSE_AUTOUPDATE_VERSIONS);
        assertEquals(DBCONTRACT, InventoryReadPlan.FMG_DIAGNOSE_FMUPDATE_DBCONTRACT);
    }

    @Test
    void fortiGateSwitchOffSendsTheSameCommandsAsBefore() {
        for (boolean multi : new boolean[] {false, true}) {
            FgtRun plain = runFortiGate(fortiGate(multi), LifecycleReadSwitch.OFF);
            assertTrue(plain.sent().stream().noneMatch(c -> c.startsWith("diagnose")), plain.sent().toString());
            assertTrue(plain.lines().isEmpty());
            FgtRun unset = runFortiGate(fortiGate(multi), LifecycleReadSwitch.from(k -> null));
            assertEquals(plain.sent(), unset.sent());
            FgtRun otherDevice = runFortiGate(fortiGate(multi), LifecycleReadSwitch.from(Map.of("UI2_LIFECYCLE_READS", BOTH,
                    "UI2_LIFECYCLE_READ_DEVICES", "dev-9")::get));
            assertEquals(plain.sent(), otherDevice.sent());
            assertTrue(otherDevice.lines().isEmpty());
        }
    }

    @Test
    void fortiGateSendsBothLiteralsAtTheTopLevelAfterEveryExistingReadAndTheResultIsUnchanged() {
        FgtRun off = runFortiGate(fortiGate(false), LifecycleReadSwitch.OFF);
        FgtRun on = runFortiGate(fortiGate(false), on(BOTH));
        assertEquals(off.result(), on.result());
        assertEquals(off.sent(), on.sent().subList(0, off.sent().size()));
        assertEquals(List.of(CONTRACT, VERSIONS), on.sent().subList(off.sent().size(), on.sent().size()));
        assertEquals(Duration.ofSeconds(60), on.ssh().timeouts.get(CONTRACT));
        assertEquals(Duration.ofSeconds(60), on.ssh().timeouts.get(VERSIONS));
        assertEquals(1, on.sent().stream().filter(CONTRACT::equals).count(), "no retry");
        assertEquals(2, on.lines().size());
        assertTrue(on.lines().get(0).startsWith("[LIFECYCLE_MEASURE] kind=fgt_update_info_contract device=dev-1 outcome=OK "), on.lines().get(0));
        assertTrue(on.lines().get(0).contains("shape=a9 Support Contract Expiry Date: ~9-9-9 // Contracts: FMWR COMP // Company: a12 // Account: m16"), on.lines().get(0));
        assertTrue(on.lines().get(1).contains("kind=fgt_autoupdate_versions") && on.lines().get(1).contains("shape=AV Engine: Version ~9.9 // Virus Definitions: Version ~9.9 Expiry Date: ~9-9-9"), on.lines().get(1));
        String joined = String.join("\n", on.lines());
        for (String secret : List.of("Acme", "jane", "example.com", "2030", "FGT60F", "fg-1")) {
            assertFalse(joined.contains(secret), secret + " leaked: " + joined);
        }
        assertEquals(on.ssh().connects, on.ssh().disconnects, "the one session is closed");
    }

    @Test
    void fortiGateOnAMultiVdomUnitSendsThemInsideConfigGlobalOnceEach() {
        FgtRun off = runFortiGate(fortiGate(true), LifecycleReadSwitch.OFF);
        FgtRun on = runFortiGate(fortiGate(true), on(BOTH));
        assertEquals(off.result(), on.result());
        assertEquals(List.of("config global", CONTRACT, VERSIONS, "end"), on.sent().subList(off.sent().size(), on.sent().size()));
        FgtRun one = runFortiGate(fortiGate(true), on("fgt_autoupdate_versions"));
        assertEquals(List.of("config global", VERSIONS, "end"), one.sent().subList(off.sent().size(), one.sent().size()));
    }

    @Test
    void fortiGateEachKindIsSentAloneWhenOnlyItIsSwitchedOn() {
        FgtRun off = runFortiGate(fortiGate(false), LifecycleReadSwitch.OFF);
        assertEquals(List.of(CONTRACT), runFortiGate(fortiGate(false), on("fgt_update_info_contract")).sent().subList(off.sent().size(), off.sent().size() + 1));
        FgtRun versionsOnly = runFortiGate(fortiGate(false), on("fgt_autoupdate_versions"));
        assertEquals(List.of(VERSIONS), versionsOnly.sent().subList(off.sent().size(), versionsOnly.sent().size()));
    }

    @Test
    void fortiGateRefusalsTimeoutsFailuresAndExceptionsNeverChangeTheResult() {
        FgtRun off = runFortiGate(fortiGate(false), LifecycleReadSwitch.OFF);
        for (String refusal : List.of("Command fail. Return code -61", "command parse error before 'update'", "Unknown action 0",
                "Permission denied", "Unknown command")) {
            Ssh ssh = fortiGate(false);
            ssh.answers.put(CONTRACT, refusal);
            ssh.answers.put(VERSIONS, refusal);
            FgtRun r = runFortiGate(ssh, on(BOTH));
            assertEquals(off.result(), r.result(), refusal);
            assertTrue(r.lines().stream().allMatch(l -> l.contains("outcome=ERROR:CliRefusal")), refusal + " " + r.lines());
            assertEquals(2, r.lines().size());
        }
        Ssh timeout = fortiGate(false);
        timeout.timeOut.addAll(List.of(CONTRACT, VERSIONS));
        FgtRun t = runFortiGate(timeout, on(BOTH));
        assertEquals(off.result(), t.result());
        assertTrue(t.lines().stream().allMatch(l -> l.contains("outcome=ERROR:TimedOut")), t.lines().toString());
        Ssh failed = fortiGate(false);
        failed.channelFailed.addAll(List.of(CONTRACT, VERSIONS));
        FgtRun f = runFortiGate(failed, on(BOTH));
        assertEquals(off.result(), f.result());
        assertTrue(f.lines().stream().allMatch(l -> l.contains("outcome=ERROR:ChannelFailed")), f.lines().toString());
        Ssh thrown = fortiGate(false);
        thrown.throwing.add(CONTRACT);
        FgtRun x = runFortiGate(thrown, on(BOTH));
        assertEquals(off.result(), x.result());
        assertTrue(x.lines().get(0).contains("outcome=ERROR:IllegalStateException"), x.lines().toString());
        assertTrue(x.lines().get(1).contains("kind=fgt_autoupdate_versions") && x.lines().get(1).contains("outcome=OK"), "the second read still runs");
        Ssh empty = fortiGate(false);
        empty.answers.put(CONTRACT, "");
        empty.answers.put(VERSIONS, "");
        FgtRun e = runFortiGate(empty, on(BOTH));
        assertEquals(off.result(), e.result());
        assertTrue(e.lines().stream().allMatch(l -> l.contains("outcome=EMPTY")), e.lines().toString());
    }

    @Test
    void aThrowingFortiGateSinkNeverChangesTheResult() {
        Ssh ssh = fortiGate(false);
        FortiGateExecutor executor = new FortiGateExecutor(ssh, null).withLifecycleReads(on(BOTH)).withLifecycleSink(l -> { throw new IllegalStateException("sink"); });
        assertTrue(executor.inventory(T, "cred", DEVICE) instanceof HttpsVendorExecutor.InventoryOutcome.Completed);
    }

    // ------------------------------------------------------------------------------------------------- FortiManager

    /** The JSON-RPC side of a FortiManager: login, status, and empty lists. */
    private static final class Rpc implements HttpsDeviceCalls {
        @Override
        public TextResponse postJson(Target t, String p, String json, Credentials c, Duration d, int m) {
            String body;
            if (json.contains("/sys/login/user")) {
                body = "{\"session\":\"tok\",\"result\":[{\"status\":{\"code\":0}}]}";
            } else if (json.contains("/sys/status")) {
                body = "{\"result\":[{\"status\":{\"code\":0},\"data\":{\"Hostname\":\"fmg-1\",\"Version\":\"v7.2.0-build1\",\"Platform Type\":\"FMG-VM64\"}}]}";
            } else {
                body = "{\"result\":[{\"status\":{\"code\":0},\"data\":[]}]}";
            }
            return new TextResponse(200, Optional.of("application/json"), body, false);
        }

        @Override public TextResponse get(Target t, String p, Credentials c, Duration d, int m) { throw new AssertionError("get"); }
        @Override public HttpsDeviceClient.SessionLogin login(Target t, String p, String j, Duration d) { throw new AssertionError("login"); }
        @Override public DownloadResult download(Target t, String m, String p, Map<String, String> f, Credentials c, OutputStream s, long x, Duration d) {
            throw new AssertionError("download");
        }
    }

    private record FmgRun(String result, List<String> sent, List<String> lines, Ssh ssh) {
    }

    private static Ssh fortiManager() {
        Ssh ssh = new Ssh();
        ssh.answers.put("get system interface", "== [ port1 ]\nname: port1   status: up   ip: 192.0.2.50 255.255.255.0\n");
        ssh.answers.put(DBCONTRACT, "FGT: serial FGT60F" + "0000000001 AccountID jane@" + "example.com Company Acme" + "Holdings contracts FMWR COMP expiry 2030-01-01\n");
        return ssh;
    }

    private static FmgRun runFortiManager(Ssh ssh, LifecycleReadSwitch sw, boolean withSsh) {
        List<String> lines = new ArrayList<>();
        FortiManagerExecutor executor = new FortiManagerExecutor(new Rpc(), ref -> new Credentials("user", "pw".toCharArray()))
                .withLifecycleReads(sw).withLifecycleSink(lines::add);
        if (withSsh) {
            executor.withSsh(ssh);
        }
        HttpsVendorExecutor.InventoryOutcome outcome = executor.inventory(new Target("192.0.2.50", 443), "cred", DEVICE);
        assertTrue(outcome instanceof HttpsVendorExecutor.InventoryOutcome.Completed, String.valueOf(outcome));
        return new FmgRun(outcome.toString(), new ArrayList<>(ssh.sent), lines, ssh);
    }

    @Test
    void fortiManagerSwitchOffSendsTheSameCommandsAsBefore() {
        FmgRun plain = runFortiManager(fortiManager(), LifecycleReadSwitch.OFF, true);
        assertEquals(List.of("get system interface"), plain.sent());
        assertTrue(plain.lines().isEmpty());
        assertEquals(List.of("get system interface"), runFortiManager(fortiManager(), LifecycleReadSwitch.from(k -> null), true).sent());
        FmgRun otherDevice = runFortiManager(fortiManager(), LifecycleReadSwitch.from(Map.of("UI2_LIFECYCLE_READS", "fmg_fmupdate_dbcontract",
                "UI2_LIFECYCLE_READ_DEVICES", "dev-9")::get), true);
        assertEquals(List.of("get system interface"), otherDevice.sent());
        assertTrue(otherDevice.lines().isEmpty());
    }

    @Test
    void fortiManagerSendsDbcontractOverTheSessionTheInventoryAlreadyOpensAfterGetSystemInterface() {
        FmgRun off = runFortiManager(fortiManager(), LifecycleReadSwitch.OFF, true);
        FmgRun on = runFortiManager(fortiManager(), on("fmg_fmupdate_dbcontract"), true);
        assertEquals(off.result(), on.result());
        assertEquals(List.of("get system interface", DBCONTRACT), on.sent());
        assertEquals(1, on.ssh().connects, "no session is opened for the new read");
        assertEquals(1, on.ssh().disconnects);
        assertEquals(Duration.ofSeconds(60), on.ssh().timeouts.get(DBCONTRACT));
        assertEquals(1, on.lines().size());
        String line = on.lines().get(0);
        assertTrue(line.startsWith("[LIFECYCLE_MEASURE] kind=fmg_fmupdate_dbcontract device=dev-1 outcome=OK "), line);
        assertTrue(line.contains("shape=FGT: serial ~a9a9 AccountID m16 Company a12 contracts FMWR COMP expiry ~9-9-9"), line);
        for (String secret : List.of("Acme", "jane", "example.com", "FGT60F", "2030")) {
            assertFalse(line.contains(secret), secret + " leaked: " + line);
        }
    }

    @Test
    void fortiManagerWithoutAnSshSessionSendsNothingAndOpensNone() {
        Ssh unused = fortiManager();
        FmgRun noSsh = runFortiManager(unused, on("fmg_fmupdate_dbcontract"), false);
        assertTrue(unused.sent.isEmpty());
        assertEquals(0, unused.connects);
        assertEquals(1, noSsh.lines().size());
        assertTrue(noSsh.lines().get(0).contains("outcome=SKIPPED:no_ssh_session"), noSsh.lines().get(0));
        Ssh refused = fortiManager();
        refused.refuseConnect = true;
        FmgRun r = runFortiManager(refused, on("fmg_fmupdate_dbcontract"), true);
        assertTrue(refused.sent.isEmpty());
        assertEquals(1, refused.connects, "the one connect the inventory already makes; no second attempt");
        assertTrue(r.lines().get(0).contains("outcome=SKIPPED:no_ssh_session"), r.lines().toString());
        assertEquals(runFortiManager(fortiManager(), LifecycleReadSwitch.OFF, false).result(), noSsh.result());
    }

    @Test
    void fortiManagerRefusalsTimeoutsAndExceptionsNeverChangeTheResult() {
        FmgRun off = runFortiManager(fortiManager(), LifecycleReadSwitch.OFF, true);
        Ssh refusal = fortiManager();
        refusal.answers.put(DBCONTRACT, "Unknown action 0\nCommand fail. Return code 1");
        FmgRun a = runFortiManager(refusal, on("fmg_fmupdate_dbcontract"), true);
        assertEquals(off.result(), a.result());
        assertTrue(a.lines().get(0).contains("outcome=ERROR:CliRefusal"), a.lines().get(0));
        Ssh timeout = fortiManager();
        timeout.timeOut.add(DBCONTRACT);
        FmgRun b = runFortiManager(timeout, on("fmg_fmupdate_dbcontract"), true);
        assertEquals(off.result(), b.result());
        assertTrue(b.lines().get(0).contains("outcome=ERROR:TimedOut"), b.lines().get(0));
        Ssh thrown = fortiManager();
        thrown.throwing.add(DBCONTRACT);
        FmgRun c = runFortiManager(thrown, on("fmg_fmupdate_dbcontract"), true);
        assertEquals(off.result(), c.result());
        assertTrue(c.lines().get(0).contains("outcome=ERROR:IllegalStateException"), c.lines().get(0));
        assertEquals(1, thrown.disconnects, "the session is still closed");
        Ssh empty = fortiManager();
        empty.answers.put(DBCONTRACT, "");
        FmgRun d = runFortiManager(empty, on("fmg_fmupdate_dbcontract"), true);
        assertEquals(off.result(), d.result());
        assertTrue(d.lines().get(0).contains("outcome=EMPTY"), d.lines().get(0));
    }
}
