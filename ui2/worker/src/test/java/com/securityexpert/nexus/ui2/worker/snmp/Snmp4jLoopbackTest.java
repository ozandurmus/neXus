package com.securityexpert.nexus.ui2.worker.snmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;

import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Metrics;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.SnmpSettings;
import com.securityexpert.nexus.ui2.worker.credential.StoreBackedSnmpCredentialResolver.Material;
import com.securityexpert.nexus.ui2.worker.snmp.SnmpClient.SnmpAnswer;

/**
 * {@link Snmp4jClient} against a real SNMPv3 agent on loopback UDP (SNMP4J's own USM on the agent side, its own engine
 * id, authPriv SHA-256/AES-128): engine discovery, the GET of the Check Point scalars, the Palo Alto multi-column
 * GETBULK walk ending at the column boundary, refusals for a wrong password and an unknown user, and a mute agent.
 * Every PDU the agent receives is recorded, so the test also proves no SET or GETNEXT ever reaches the wire.
 */
class Snmp4jLoopbackTest {
    private static final String USER = "synthetic-snmp-user";
    private static final String AUTH = "synthetic-" + "auth-secret-1";
    private static final String PRIV = "synthetic-" + "priv-secret-1";

    private Snmp agent;
    private final List<Integer> received = new CopyOnWriteArrayList<>();
    private final NavigableMap<OID, Variable> mib = new ConcurrentSkipListMap<>();
    private volatile boolean mute;

    private static final org.snmp4j.SNMP4JSettings.ReportSecurityLevelStrategy DEFAULT_REPORTS =
            org.snmp4j.SNMP4JSettings.getReportSecurityLevelStrategy();

    @AfterEach
    void stop() throws IOException {
        if (agent != null) agent.close();
        org.snmp4j.SNMP4JSettings.setReportSecurityLevelStrategy(DEFAULT_REPORTS);
    }

    /**
     * How the agent sends a refusal report. noAuthNoPrivIfNeeded: as net-snmp does (and as the M0 measurement saw on the
     * Check Point and Palo Alto agents), the report goes out unauthenticated when the request could not be authenticated.
     * standard: SNMP4J's own default, which signs a wrong-digest report with the user's key, or sends nothing for an
     * unknown user.
     */
    static java.util.stream.Stream<org.snmp4j.SNMP4JSettings.ReportSecurityLevelStrategy> reportStyles() {
        return java.util.stream.Stream.of(org.snmp4j.SNMP4JSettings.ReportSecurityLevelStrategy.noAuthNoPrivIfNeeded,
                org.snmp4j.SNMP4JSettings.ReportSecurityLevelStrategy.standard);
    }

    /** Starts the agent on an ephemeral loopback port and returns the port. */
    private int startAgent() throws IOException {
        byte[] engineId = MPv3.createLocalEngineID(new OctetString("loopback-agent"));
        SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
        protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
        protocols.addPrivacyProtocol(new PrivAES128());
        USM usm = new USM(protocols, new OctetString(engineId), 0);
        usm.addUser(new UsmUser(new OctetString(USER), AuthHMAC192SHA256.ID, new OctetString(AUTH), PrivAES128.ID,
                new OctetString(PRIV)));
        SecurityModels models = new SecurityModels();
        models.addSecurityModel(usm);
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv3(engineId, new DefaultPDUFactory(), protocols, models, new CounterSupport()));
        DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping(new UdpAddress("127.0.0.1/0"));
        agent = new Snmp(dispatcher, transport);
        agent.addCommandResponder(this::respond);
        agent.listen();
        return transport.getListenAddress().getPort();
    }

    private <A extends org.snmp4j.smi.Address> void respond(CommandResponderEvent<A> event) {
        PDU request = event.getPDU();
        received.add(request.getType());
        if (mute) return;
        ScopedPDU response = new ScopedPDU();
        response.setType(PDU.RESPONSE);
        response.setRequestID(request.getRequestID());
        // A v3 answer carries the request's context, or the manager drops it as a mismatch (RFC 3412 section 7.2.10).
        response.setContextEngineID(((ScopedPDU) request).getContextEngineID());
        response.setContextName(((ScopedPDU) request).getContextName());
        if (request.getType() == PDU.GET) {
            for (VariableBinding vb : request.getVariableBindings()) {
                Variable v = mib.get(vb.getOid());
                response.add(new VariableBinding(vb.getOid(), v == null ? Null.noSuchObject : v));
            }
        } else if (request.getType() == PDU.GETBULK) {
            List<OID> cursors = new ArrayList<>();
            for (VariableBinding vb : request.getVariableBindings()) cursors.add(vb.getOid());
            for (int rep = 0; rep < request.getMaxRepetitions(); rep++) {
                for (int c = 0; c < cursors.size(); c++) {
                    Map.Entry<OID, Variable> next = cursors.get(c) == null ? null : mib.higherEntry(cursors.get(c));
                    if (next == null) {
                        response.add(new VariableBinding(cursors.get(c) == null ? new OID("0.0") : cursors.get(c),
                                Null.endOfMibView));
                        cursors.set(c, null);
                    } else {
                        response.add(new VariableBinding(next.getKey(), next.getValue()));
                        cursors.set(c, next.getKey());
                    }
                }
            }
        } else {
            response.setErrorStatus(PDU.genErr);
        }
        try {
            event.getMessageDispatcher().returnResponsePdu(event.getMessageProcessingModel(), event.getSecurityModel(),
                    event.getSecurityName(), event.getSecurityLevel(), response, event.getMaxSizeResponsePDU(),
                    event.getStateReference(), new StatusInformation());
        } catch (org.snmp4j.MessageException failed) {
            throw new IllegalStateException(failed);
        }
        event.setProcessed(true);
    }

    private static Material credential(String user, String auth, String priv) {
        return new Material(CredentialKind.SNMP_V3, user, new SnmpSettings("authPriv", "SHA-256", "AES-128"),
                auth.toCharArray(), priv.toCharArray());
    }

    private void put(String oid, Variable v) {
        mib.put(new OID(oid), v);
    }

    @Test
    void checkPointScalarsArriveByGetOverAuthPriv() throws IOException {
        int port = startAgent();
        put(SnmpOids.SYS_UPTIME, new TimeTicks(123_456));
        put(SnmpOids.CP_PROC_USAGE, new Integer32(37));
        put(SnmpOids.CP_MEM_TOTAL_REAL64, new Counter64(16_000_000_000L));
        put(SnmpOids.CP_MEM_ACTIVE_REAL64, new OctetString("6000000000")); // the DisplayString variant of some MIB copies
        put(SnmpOids.CP_FW_NUM_CONN, new Gauge32(48_213));
        put("1.3.6.1.4.1.2620.1.6.7.4.5.0", new Counter64(1)); // memFreeReal64: present, never asked for

        SnmpAnswer answer = new Snmp4jClient(port).poll("127.0.0.1", credential(USER, AUTH, PRIV), SnmpVendorProfile.CHECK_POINT);

        var answered = assertInstanceOf(SnmpAnswer.Answered.class, answer);
        assertEquals(5, answered.values().size());
        assertFalse(answered.values().containsKey("1.3.6.1.4.1.2620.1.6.7.4.5.0"));
        Metrics m = SnmpMetricParser.parse(SnmpVendorProfile.CHECK_POINT, answered);
        assertEquals(123_456L, m.sysUptimeCs());
        assertEquals(new BigDecimal("37.00"), m.cpuPct());
        assertEquals(new BigDecimal("37.50"), m.memPct());
        assertEquals(48_213L, m.sessions());
        assertTrue(received.stream().allMatch(t -> t == PDU.GET), received.toString());
    }

    @Test
    void paloAltoColumnsAreWalkedByGetBulkAndStopAtTheColumnBoundary() throws IOException {
        int port = startAgent();
        put(SnmpOids.SYS_UPTIME, new TimeTicks(9_876));
        put(SnmpOids.PAN_SESSION_ACTIVE, new Integer32(5_120));
        // 20 processor rows: more than one GETBULK of 16 repetitions.
        for (int i = 1; i <= 20; i++) put(SnmpOids.HR_PROCESSOR_LOAD + "." + i, new Integer32(i == 1 ? 12 : 40 + i));
        put("1.3.6.1.2.1.25.3.3.1.3.1", new Integer32(99)); // the next column after hrProcessorLoad: not ours
        put(SnmpOids.HR_STORAGE_ENTRY + ".1.1", new Integer32(1)); // hrStorageIndex: not asked for
        put(SnmpOids.HR_STORAGE_TYPE + ".1", new OID("1.3.6.1.2.1.25.2.1.3"));
        put(SnmpOids.HR_STORAGE_TYPE + ".2", new OID(SnmpOids.HR_STORAGE_RAM));
        put(SnmpOids.HR_STORAGE_SIZE + ".1", new Integer32(900));
        put(SnmpOids.HR_STORAGE_SIZE + ".2", new Integer32(4000));
        put(SnmpOids.HR_STORAGE_USED + ".1", new Integer32(800));
        put(SnmpOids.HR_STORAGE_USED + ".2", new Integer32(1000));
        put("1.3.6.1.2.1.25.2.3.1.7.1", new Integer32(0)); // hrStorageAllocationFailures: past the last column

        SnmpAnswer answer = new Snmp4jClient(port).poll("127.0.0.1", credential(USER, AUTH, PRIV), SnmpVendorProfile.PALO_ALTO);

        var answered = assertInstanceOf(SnmpAnswer.Answered.class, answer);
        assertTrue(answered.values().keySet().stream().allMatch(SnmpVendorProfile.PALO_ALTO::permits));
        assertFalse(answered.values().containsKey("1.3.6.1.2.1.25.3.3.1.3.1"));
        assertFalse(answered.values().containsKey("1.3.6.1.2.1.25.2.3.1.7.1"));
        assertEquals(20, SnmpMetricParser.column(answered, SnmpOids.HR_PROCESSOR_LOAD).size());
        Metrics m = SnmpMetricParser.parse(SnmpVendorProfile.PALO_ALTO, answered);
        assertEquals(new BigDecimal("12.00"), m.cpuPct());
        assertEquals(new BigDecimal("60.00"), m.cpuDataplanePct());
        assertEquals(new BigDecimal("25.00"), m.memPct());
        assertEquals(5_120L, m.sessions());
        assertTrue(m.complete());
        long bulks = received.stream().filter(t -> t == PDU.GETBULK).count();
        assertTrue(bulks >= 2 && bulks <= SnmpClient.MAX_BULK_REQUESTS, "bulk requests " + bulks);
        assertTrue(received.stream().allMatch(t -> t == PDU.GET || t == PDU.GETBULK), received.toString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("reportStyles")
    void aWrongAuthenticationPasswordIsAnAuthRefusal(org.snmp4j.SNMP4JSettings.ReportSecurityLevelStrategy style)
            throws IOException {
        org.snmp4j.SNMP4JSettings.setReportSecurityLevelStrategy(style);
        int port = startAgent();
        put(SnmpOids.SYS_UPTIME, new TimeTicks(1));
        assertInstanceOf(SnmpAnswer.AuthRefused.class, new Snmp4jClient(port).poll("127.0.0.1",
                credential(USER, "synthetic-" + "wrong-auth-1", PRIV), SnmpVendorProfile.CHECK_POINT));
        assertTrue(received.isEmpty(), "the agent never accepted a request");
    }

    @Test
    void anUnknownUserIsAnAuthRefusalWhenTheAgentReportsItAsNetSnmpDoes() throws IOException {
        org.snmp4j.SNMP4JSettings.setReportSecurityLevelStrategy(
                org.snmp4j.SNMP4JSettings.ReportSecurityLevelStrategy.noAuthNoPrivIfNeeded);
        int port = startAgent();
        put(SnmpOids.SYS_UPTIME, new TimeTicks(1));
        assertInstanceOf(SnmpAnswer.AuthRefused.class, new Snmp4jClient(port).poll("127.0.0.1",
                credential("synthetic-unknown-user", AUTH, PRIV), SnmpVendorProfile.PALO_ALTO));
        assertTrue(received.isEmpty());
    }

    @Test
    void anAgentThatStaysSilentForAnUnknownUserIsNoAnswer() throws IOException {
        // SNMP4J's default agent cannot send this report at all; silence is only ever NoAnswer, never a guessed refusal.
        int port = startAgent();
        put(SnmpOids.SYS_UPTIME, new TimeTicks(1));
        assertInstanceOf(SnmpAnswer.NoAnswer.class, new Snmp4jClient(port).poll("127.0.0.1",
                credential("synthetic-unknown-user", AUTH, PRIV), SnmpVendorProfile.CHECK_POINT));
    }

    @Test
    void anAgentThatNeverAnswersIsNoAnswer() throws IOException {
        int port = startAgent();
        mute = true;
        long start = System.nanoTime();
        assertInstanceOf(SnmpAnswer.NoAnswer.class,
                new Snmp4jClient(port).poll("127.0.0.1", credential(USER, AUTH, PRIV), SnmpVendorProfile.CHECK_POINT));
        assertTrue(System.nanoTime() - start < 20_000_000_000L);
    }
}
