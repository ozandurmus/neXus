package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;

/**
 * Lifecycle reads, group B, Palo Alto (PO 2026-11-10, D2): the license and support reads are sent only to a firewall whose
 * PAN-OS major version, observed by this run's {@code type=version} read, is known and below 12; never to a Panorama; last,
 * 60 s, no retry; and nothing they do changes the inventory result.
 */
class LifecycleReadsPaloAltoTest {

    private static final String DEVICE = "dev-1";
    private static final String HA = "<response><result><enabled>no</enabled></result></response>";
    private static final String IFACE = "<response><result><ifnet><entry><name>ethernet1/1</name><ip>192.0.2.1/24</ip>"
            + "<vsys>1</vsys><fwd>vr:default</fwd><state>up</state></entry></ifnet></result></response>";
    private static final String ROUTES = "<response><result><entry><destination>0.0.0.0/0</destination><nexthop>192.0.2.254</nexthop>"
            + "<interface>ethernet1/1</interface><virtual-router>default</virtual-router><flags>A S</flags></entry></result></response>";
    private static final String LICENSE_BODY = "<response status=\"success\"><result><licenses>"
            + "<entry><feature>Threat" + " Prevention</feature><serial>0123" + "456789</serial><authcode>I" + "1234567</authcode>"
            + "<expires>November 01, 2030</expires><expired>no</expired></entry></licenses></result></response>";
    private static final String SUPPORT_BODY = "<response status=\"success\"><result><SupportInfoResponse><Support>"
            + "<ExpiryDate>November 01, 2030</ExpiryDate><PrimaryEmail>jane" + "@example.com</PrimaryEmail></Support></SupportInfoResponse></result></response>";

    private static LifecycleReadSwitch on(String kinds) {
        return LifecycleReadSwitch.from(Map.of("UI2_LIFECYCLE_READS", kinds, "UI2_LIFECYCLE_READ_DEVICES", DEVICE)::get);
    }

    private static final String BOTH = "pan_license_info,pan_support_info";

    private static String typeVersion(String version) {
        return "<response status=\"success\"><result>" + (version == null ? "" : "<sw-version>" + version + "</sw-version>")
                + "<model>PA-" + "TESTMODEL</model><serial>TESTSER" + "0003</serial></result></response>";
    }

    /** Wraps the scripted double: answers the two lifecycle cmds itself, can throw or fail them, records specs and timeouts. */
    private static final class Spy implements DeviceTransport {
        final ScriptedPaloAltoInventoryTransport inner;
        final Map<String, Object> lifecycle = new HashMap<>();
        final List<String> sent = new ArrayList<>();
        final List<XmlApiSpec> specs = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();

        Spy(ScriptedPaloAltoInventoryTransport inner) {
            this.inner = inner;
        }

        @Override public ConnectResult connect(ConnectionTarget t, ConnectSpec s, Duration d) { return inner.connect(t, s, d); }
        @Override public ExecResult exec(TransportSession s, ExecSpec e, Duration d) { return inner.exec(s, e, d); }
        @Override public FetchResult fetch(TransportSession s, FetchSpec f, Duration d) { return inner.fetch(s, f, d); }
        @Override public void disconnect(TransportSession s) { inner.disconnect(s); }

        @Override
        public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
            String cmd = spec.formParams().get("cmd");
            if (cmd != null) {
                sent.add(cmd);
            } else if (!"keygen".equals(spec.type())) {
                sent.add("type=" + spec.type());
            }
            if (cmd != null && (cmd.startsWith("<request>"))) {
                specs.add(spec);
                timeouts.add(timeout);
                Object scripted = lifecycle.get(cmd);
                if (scripted instanceof RuntimeException e) {
                    throw e;
                }
                if (scripted instanceof XmlApiResult r) {
                    return r;
                }
                return new XmlApiResult.Completed(200, scripted == null ? "" : scripted.toString());
            }
            if (cmd != null && cmd.equals(InventoryReadPlan.PAN_SHOW_JOBS_ALL)) {
                return new XmlApiResult.Completed(200, "<response status=\"success\"><result><job/></result></response>");
            }
            return inner.xmlApiCall(target, spec, timeout);
        }
    }

    private record Run(String result, List<String> sent, List<String> lines, Spy spy) {
    }

    private static Run run(String version, boolean panorama, LifecycleReadSwitch sw, Map<String, Object> lifecycle) {
        Map<String, String> script = new HashMap<>();
        script.put(InventoryReadPlan.PAN_SHOW_HA_STATE, HA);
        if (panorama) {
            script.put(InventoryReadPlan.PAN_SHOW_SYSTEM_INFO, "<response status=\"success\"><result><system><hostname>pano" + "</hostname>"
                    + "<serial>SYNTH" + "0001</serial><model>Panorama</model><sw-version>11.1.4</sw-version></system></result></response>");
        } else {
            script.put(InventoryReadPlan.PAN_TYPE_VERSION, typeVersion(version));
            script.put(InventoryReadPlan.PAN_SHOW_INTERFACE_ALL, IFACE);
            script.put(InventoryReadPlan.PAN_SHOW_ROUTING_ROUTE, ROUTES);
        }
        Spy spy = new Spy(new ScriptedPaloAltoInventoryTransport(script));
        spy.lifecycle.putAll(lifecycle);
        List<String> lines = new ArrayList<>();
        InventoryCapabilityExecutor executor = new InventoryCapabilityExecutor(spy,
                ref -> new PanCredentialMaterial("user", "pw".toCharArray())).withLifecycleReads(sw).withLifecycleSink(lines::add);
        InventoryRequest base = InventoryRequest.paloAlto(new ApiTarget("ep-1", "https://fw.example"), "cred");
        InventoryRequest request = panorama ? new InventoryRequest(base.vendor(), base.connectionTarget(), base.apiTarget(),
                base.credentialRef(), base.trustRuleRef(), base.modelHint(), true) : base;
        InventoryResult result = executor.collect(request, Optional.empty(), false, DEVICE);
        assertTrue(result instanceof InventoryResult.Completed, "expected Completed, got " + result);
        return new Run(result.toString().replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "ID"), spy.sent, lines, spy);
    }

    private static Map<String, Object> bodies() {
        return Map.of(InventoryReadPlan.PAN_REQUEST_LICENSE_INFO, LICENSE_BODY, InventoryReadPlan.PAN_REQUEST_SUPPORT_INFO, SUPPORT_BODY);
    }

    @Test
    void theLiteralsAreTheGateKeys() {
        assertEquals("<request><license><info/></license></request>", InventoryReadPlan.PAN_REQUEST_LICENSE_INFO);
        assertEquals("<request><support><info/></support></request>", InventoryReadPlan.PAN_REQUEST_SUPPORT_INFO);
    }

    @Test
    void withTheSwitchUnsetNothingNewIsSentOnAnyPanOsVersion() {
        Run off = run("11.1.4-h7", false, LifecycleReadSwitch.OFF, Map.of());
        assertTrue(off.sent().stream().noneMatch(c -> c.startsWith("<request>")), off.sent().toString());
        assertTrue(off.lines().isEmpty());
        assertEquals(off.sent(), run("11.1.4-h7", false, LifecycleReadSwitch.from(k -> null), Map.of()).sent());
        Run otherDevice = run("11.1.4-h7", false, LifecycleReadSwitch.from(Map.of("UI2_LIFECYCLE_READS", BOTH,
                "UI2_LIFECYCLE_READ_DEVICES", "dev-9")::get), Map.of());
        assertEquals(off.sent(), otherDevice.sent());
        assertTrue(otherDevice.lines().isEmpty());
        Run nonePanorama = run("x", true, LifecycleReadSwitch.OFF, Map.of());
        assertTrue(nonePanorama.sent().stream().noneMatch(c -> c.startsWith("<request>")));
        assertTrue(nonePanorama.lines().isEmpty());
    }

    @Test
    void belowTwelveSendsBothLiteralsLastAsOpRequestsWithTheGatedTimeoutAndTheResultIsUnchanged() {
        Run off = run("11.1.4-h7", false, LifecycleReadSwitch.OFF, Map.of());
        Run on = run("11.1.4-h7", false, on(BOTH), bodies());
        assertEquals(off.result(), on.result());
        List<String> extra = on.sent().subList(off.sent().size(), on.sent().size());
        assertEquals(List.of(InventoryReadPlan.PAN_REQUEST_LICENSE_INFO, InventoryReadPlan.PAN_REQUEST_SUPPORT_INFO), extra);
        assertEquals(off.sent(), on.sent().subList(0, off.sent().size()), "every existing read first, in the same order");
        for (int i = 0; i < 2; i++) {
            XmlApiSpec spec = on.spy().specs.get(i);
            assertEquals("GET", spec.httpMethod());
            assertEquals("op", spec.type());
            assertEquals(Map.of("X-PAN-KEY", "test-api-key"), spec.headers());
            assertEquals(Duration.ofSeconds(60), on.spy().timeouts.get(i));
        }
        assertEquals(2, on.lines().size());
        assertTrue(on.lines().get(0).startsWith("[LIFECYCLE_MEASURE] kind=pan_license_info device=dev-1 outcome=OK "), on.lines().get(0));
        assertTrue(on.lines().get(0).endsWith("shape=response[status=success]{result*1{licenses*1{entry*1{feature*1,serial*1,authcode*1,expires*1,expired*1}}}}"),
                on.lines().get(0));
        assertTrue(on.lines().get(1).endsWith("shape=response[status=success]{result*1{SupportInfoResponse*1{Support*1{ExpiryDate*1,PrimaryEmail*1}}}}"),
                on.lines().get(1));
        String joined = String.join("\n", on.lines());
        for (String secret : List.of("Threat", "0123456789", "I1234567", "2030", "jane", "example.com", "TESTSER", "pw")) {
            assertFalse(joined.contains(secret), secret + " leaked: " + joined);
        }
    }

    @Test
    void eachKindIsSentAloneWhenOnlyItIsSwitchedOn() {
        Run licenseOnly = run("10.2.9", false, on("pan_license_info"), bodies());
        assertEquals(1, licenseOnly.spy().specs.size());
        assertEquals(InventoryReadPlan.PAN_REQUEST_LICENSE_INFO, licenseOnly.spy().specs.get(0).formParams().get("cmd"));
        Run supportOnly = run("10.2.9", false, on("pan_support_info"), bodies());
        assertEquals(List.of(InventoryReadPlan.PAN_REQUEST_SUPPORT_INFO), supportOnly.spy().specs.stream().map(s -> s.formParams().get("cmd")).toList());
    }

    @Test
    void thePanOsGuardSendsOnlyWhenTheMajorVersionIsKnownAndBelowTwelve() {
        for (String version : List.of("11.1.4-h7", "10.2.9", "9.1.0", "11.9.9", "8.1")) {
            Run r = run(version, false, on(BOTH), bodies());
            assertEquals(2, r.spy().specs.size(), version);
            assertTrue(r.lines().stream().noneMatch(l -> l.contains("SKIPPED")), version);
        }
        for (String version : List.of("12.1.2", "12.0.0", "13.0.1", "12")) {
            Run r = run(version, false, on(BOTH), bodies());
            assertEquals(0, r.spy().specs.size(), version);
            assertEquals(2, r.lines().size(), version);
            assertTrue(r.lines().get(0).contains("kind=pan_license_info") && r.lines().get(0).contains("outcome=SKIPPED:pan_os_12_or_unknown"), r.lines().get(0));
            assertTrue(r.lines().get(1).contains("kind=pan_support_info") && r.lines().get(1).contains("outcome=SKIPPED:pan_os_12_or_unknown"), r.lines().get(1));
        }
    }

    @Test
    void anUnknownOrUnparseableVersionIsTreatedLikeTwelveOrLater() {
        for (String version : new String[] {null, "", "abc", "x.y.z", "-1.0", "unknown"}) {
            Run r = run(version, false, on(BOTH), bodies());
            assertEquals(0, r.spy().specs.size(), "version=" + version);
            assertEquals(2, r.lines().size(), "version=" + version);
            assertTrue(r.lines().stream().allMatch(l -> l.contains("outcome=SKIPPED:pan_os_12_or_unknown")), r.lines().toString());
        }
    }

    @Test
    void aPanoramaNeverGetsEitherReadAndIsToldSo() {
        Run off = run(null, true, LifecycleReadSwitch.OFF, Map.of());
        Run on = run(null, true, on(BOTH), bodies());
        assertEquals(off.sent(), on.sent());
        assertEquals(off.result(), on.result());
        assertEquals(0, on.spy().specs.size());
        assertEquals(2, on.lines().size());
        assertTrue(on.lines().stream().allMatch(l -> l.contains("outcome=SKIPPED:panorama")), on.lines().toString());
        Run licenseOnly = run(null, true, on("pan_license_info"), bodies());
        assertEquals(1, licenseOnly.lines().size());
    }

    @Test
    void noOutcomeOfEitherReadChangesTheInventoryResult() {
        Run off = run("11.1.4-h7", false, LifecycleReadSwitch.OFF, Map.of());
        Map<String, Map<String, Object>> cases = new java.util.LinkedHashMap<>();
        cases.put("ERROR:IllegalStateException", Map.of(InventoryReadPlan.PAN_REQUEST_LICENSE_INFO, new IllegalStateException("synthetic"),
                InventoryReadPlan.PAN_REQUEST_SUPPORT_INFO, new IllegalStateException("synthetic")));
        cases.put("ERROR:XmlApiFailed", Map.of(InventoryReadPlan.PAN_REQUEST_LICENSE_INFO, new XmlApiResult.Failed("timeout"),
                InventoryReadPlan.PAN_REQUEST_SUPPORT_INFO, new XmlApiResult.Failed("refused")));
        cases.put("EMPTY", Map.of(InventoryReadPlan.PAN_REQUEST_LICENSE_INFO, "", InventoryReadPlan.PAN_REQUEST_SUPPORT_INFO, ""));
        cases.put("ERROR:XmlUnusable", Map.of(InventoryReadPlan.PAN_REQUEST_LICENSE_INFO, "<html", InventoryReadPlan.PAN_REQUEST_SUPPORT_INFO,
                "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><response>&e;</response>"));
        for (var entry : cases.entrySet()) {
            Run r = run("11.1.4-h7", false, on(BOTH), entry.getValue());
            assertEquals(off.result(), r.result(), entry.getKey());
            assertEquals(2, r.lines().size(), entry.getKey());
            assertTrue(r.lines().stream().allMatch(l -> l.contains("outcome=" + entry.getKey())), r.lines().toString());
        }
        Run errorReply = run("11.1.4-h7", false, on(BOTH), Map.of(InventoryReadPlan.PAN_REQUEST_LICENSE_INFO,
                "<response status=\"error\" code=\"7\"><msg><line>Invalid " + "object</line></msg></response>"));
        assertEquals(off.result(), errorReply.result());
        assertTrue(errorReply.lines().get(0).contains("shape=response[code=7,status=error]{msg*1{line*1}}"), errorReply.lines().get(0));
    }

    @Test
    void aThrowingSinkNeverChangesTheResult() {
        Map<String, String> script = Map.of(InventoryReadPlan.PAN_SHOW_HA_STATE, HA, InventoryReadPlan.PAN_TYPE_VERSION, typeVersion("11.1.4"),
                InventoryReadPlan.PAN_SHOW_INTERFACE_ALL, IFACE, InventoryReadPlan.PAN_SHOW_ROUTING_ROUTE, ROUTES);
        Spy spy = new Spy(new ScriptedPaloAltoInventoryTransport(script));
        spy.lifecycle.putAll(bodies());
        InventoryCapabilityExecutor executor = new InventoryCapabilityExecutor(spy,
                ref -> new PanCredentialMaterial("user", "pw".toCharArray())).withLifecycleReads(on(BOTH))
                .withLifecycleSink(line -> { throw new IllegalStateException("sink"); });
        InventoryResult result = executor.collect(InventoryRequest.paloAlto(new ApiTarget("ep-1", "https://fw.example"), "cred"),
                Optional.empty(), false, DEVICE);
        assertTrue(result instanceof InventoryResult.Completed);
    }
}
