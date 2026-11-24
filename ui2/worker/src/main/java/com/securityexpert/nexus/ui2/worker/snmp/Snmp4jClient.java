package com.securityexpert.nexus.ui2.worker.snmp;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.MessageException;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.CounterSupport;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthHMAC128SHA224;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthHMAC256SHA384;
import org.snmp4j.security.AuthHMAC384SHA512;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES192;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;

import com.securityexpert.nexus.ui2.worker.credential.StoreBackedSnmpCredentialResolver.Material;

/**
 * SNMPv3 USM client over SNMP4J. Each poll builds its own USM, message dispatcher and UDP socket and closes them, so no
 * localized key outlives the poll and no global SNMP4J state is used. Only {@link PDU#GET} and {@link PDU#GETBULK} PDUs are
 * built ({@link #send} refuses any other type), and only OIDs the vendor profile permits are asked for.
 */
public final class Snmp4jClient implements SnmpClient {

    /**
     * usmStats* report counters (RFC 3414 usmStats, 1.3.6.1.6.3.15.1.1): .1 unsupported level, .3 unknown user, .5 wrong
     * digest, .6 decryption error. (.2 not in time window and .4 unknown engine id are handled inside SNMP4J.)
     */
    private static final List<String> AUTH_REPORTS = List.of("1.3.6.1.6.3.15.1.1.1", "1.3.6.1.6.3.15.1.1.3",
            "1.3.6.1.6.3.15.1.1.5", "1.3.6.1.6.3.15.1.1.6");
    private static final Set<Integer> AUTH_STATUS = Set.of(SnmpConstants.SNMPv3_USM_UNSUPPORTED_SECURITY_LEVEL,
            SnmpConstants.SNMPv3_USM_UNKNOWN_SECURITY_NAME, SnmpConstants.SNMPv3_USM_DECRYPTION_ERROR,
            SnmpConstants.SNMPv3_USM_AUTHENTICATION_ERROR, SnmpConstants.SNMPv3_USM_AUTHENTICATION_FAILURE,
            SnmpConstants.SNMPv3_USM_UNSUPPORTED_AUTHPROTOCOL, SnmpConstants.SNMPv3_USM_UNSUPPORTED_PRIVPROTOCOL);

    /** Credential store names (V114) of the protocols the design allows: SHA-2 authentication, AES privacy. */
    static final Map<String, OID> AUTH = Map.of("SHA-224", AuthHMAC128SHA224.ID, "SHA-256", AuthHMAC192SHA256.ID,
            "SHA-384", AuthHMAC256SHA384.ID, "SHA-512", AuthHMAC384SHA512.ID);
    static final Map<String, OID> PRIV = Map.of("AES-128", PrivAES128.ID, "AES-192", PrivAES192.ID,
            "AES-256", PrivAES256.ID);

    private static final byte[] LOCAL_ENGINE_ID = MPv3.createLocalEngineID();

    private final int port;

    /** The production client: UDP 161. */
    public Snmp4jClient() {
        this(PORT);
    }

    /** Tests only: an in-process responder on a loopback port. */
    Snmp4jClient(int port) {
        this.port = port;
    }

    private static SecurityProtocols protocols() {
        SecurityProtocols p = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
        p.addAuthenticationProtocol(new AuthHMAC128SHA224());
        p.addAuthenticationProtocol(new AuthHMAC192SHA256());
        p.addAuthenticationProtocol(new AuthHMAC256SHA384());
        p.addAuthenticationProtocol(new AuthHMAC384SHA512());
        p.addPrivacyProtocol(new PrivAES128());
        p.addPrivacyProtocol(new PrivAES192());
        p.addPrivacyProtocol(new PrivAES256());
        return p;
    }

    /** True when the credential is SNMPv3 authPriv with an allowed authentication and privacy protocol. */
    public static boolean suitable(Material m) {
        return m != null && m.settings() != null && "authPriv".equals(m.settings().securityLevel())
                && AUTH.containsKey(m.settings().authProtocol()) && PRIV.containsKey(m.settings().privProtocol())
                && m.username() != null && !m.username().isBlank()
                && m.communityOrAuthSecret().length > 0 && m.privacySecret().length > 0;
    }

    @Override
    public SnmpAnswer poll(String host, Material credential, SnmpVendorProfile profile) {
        if (!suitable(credential)) throw new IllegalArgumentException("SNMP_CREDENTIAL_UNSUITABLE");
        for (String oid : profile.getOids()) requirePermitted(profile, oid);
        for (String oid : profile.walkColumns()) requirePermitted(profile, oid);
        SecurityProtocols protocols = protocols();
        // This poll's own USM counters. An agent that refuses us may answer with a report this side cannot authenticate
        // (some agents sign usmStatsWrongDigests with the user's key); SNMP4J then drops it and counts it here, so the
        // refusal is still seen even though no response reaches the request.
        CounterSupport counters = new CounterSupport();
        java.util.concurrent.atomic.AtomicBoolean refusedLocally = new java.util.concurrent.atomic.AtomicBoolean();
        counters.addCounterListener(event -> {
            String oid = event.getOid().toDottedString();
            for (String counter : AUTH_REPORTS) if (SnmpOids.under(oid, counter)) refusedLocally.set(true);
        });
        USM usm = new USM(protocols, new OctetString(LOCAL_ENGINE_ID), 0, counters);
        SecurityModels models = new SecurityModels();
        models.addSecurityModel(usm);
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv3(LOCAL_ENGINE_ID, new DefaultPDUFactory(PDU.GET), protocols, models,
                counters));
        byte[] auth = bytes(credential.communityOrAuthSecret());
        byte[] priv = bytes(credential.privacySecret());
        Snmp snmp = null;
        try {
            snmp = new Snmp(dispatcher, new DefaultUdpTransportMapping());
            snmp.listen();
            OctetString user = new OctetString(credential.username());
            usm.addUser(new UsmUser(user, AUTH.get(credential.settings().authProtocol()), new OctetString(auth),
                    PRIV.get(credential.settings().privProtocol()), new OctetString(priv)));
            UserTarget<UdpAddress> target = new UserTarget<>();
            target.setAddress(new UdpAddress(InetAddress.getByName(host), port));
            target.setVersion(SnmpConstants.version3);
            target.setSecurityLevel(SecurityLevel.AUTH_PRIV);
            target.setSecurityName(user);
            target.setTimeout(TIMEOUT_MS);
            target.setRetries(RETRIES);

            Map<String, SnmpValue> values = new LinkedHashMap<>();
            ScopedPDU get = new ScopedPDU();
            get.setType(PDU.GET);
            for (String oid : profile.getOids()) get.add(new VariableBinding(new OID(oid)));
            if (!get.getVariableBindings().isEmpty()) {
                Step step = exchange(snmp, get, target);
                if (step.answer instanceof SnmpAnswer.NoAnswer && refusedLocally.get()) return new SnmpAnswer.AuthRefused();
                if (step.answer != null) return step.answer;
                for (VariableBinding vb : step.response.getVariableBindings()) {
                    String oid = vb.getOid().toDottedString();
                    if (profile.permits(oid)) values.put(oid, value(vb.getVariable()));
                }
            }
            if (!profile.walkColumns().isEmpty()) {
                SnmpAnswer refused = walk(snmp, target, profile, values);
                if (refused != null) return refused;
                if (values.isEmpty() && refusedLocally.get()) return new SnmpAnswer.AuthRefused();
            }
            return new SnmpAnswer.Answered(values);
        } catch (IOException | RuntimeException failed) {
            return new SnmpAnswer.NoAnswer();
        } finally {
            Arrays.fill(auth, (byte) 0);
            Arrays.fill(priv, (byte) 0);
            usm.removeAllUsers();
            if (snmp != null) {
                try {
                    snmp.close();
                } catch (IOException ignored) {
                    // the socket is gone either way
                }
            }
        }
    }

    /**
     * Bounded GETBULK walk of the profile's columns in one stream: at most {@link #MAX_BULK_REQUESTS} requests of
     * {@link #MAX_REPETITIONS} repetitions; a column ends at the first OID outside it, an exception value or end of view.
     * A walk that stops early keeps what arrived (the parser leaves what it cannot use UNKNOWN).
     */
    private SnmpAnswer walk(Snmp snmp, UserTarget<UdpAddress> target, SnmpVendorProfile profile,
            Map<String, SnmpValue> values) throws IOException {
        Map<String, String> next = new LinkedHashMap<>();
        for (String column : profile.walkColumns()) next.put(column, column);
        for (int request = 0; request < MAX_BULK_REQUESTS && !next.isEmpty(); request++) {
            List<String> columns = new ArrayList<>(next.keySet());
            ScopedPDU bulk = new ScopedPDU();
            bulk.setType(PDU.GETBULK);
            bulk.setNonRepeaters(0);
            bulk.setMaxRepetitions(MAX_REPETITIONS);
            for (String column : columns) bulk.add(new VariableBinding(new OID(next.get(column))));
            Step step = exchange(snmp, bulk, target);
            if (step.answer instanceof SnmpAnswer.AuthRefused) return step.answer;
            if (step.answer != null) return null;
            List<? extends VariableBinding> vbs = step.response.getVariableBindings();
            Map<String, Boolean> ended = new HashMap<>();
            for (int i = 0; i < vbs.size(); i++) {
                String column = columns.get(i % columns.size());
                if (Boolean.TRUE.equals(ended.get(column))) continue;
                VariableBinding vb = vbs.get(i);
                String oid = vb.getOid().toDottedString();
                if (vb.getVariable().isException() || !SnmpOids.under(oid, column) || oid.equals(column)
                        || !profile.permits(oid) || values.containsKey(oid)) {
                    ended.put(column, true);
                    continue;
                }
                values.put(oid, value(vb.getVariable()));
                next.put(column, oid);
            }
            // A column that got no varbind in this answer (a short answer) is ended too, never re-asked in a loop.
            for (int c = 0; c < columns.size(); c++) {
                String column = columns.get(c);
                if (Boolean.TRUE.equals(ended.get(column)) || c >= vbs.size()) next.remove(column);
            }
        }
        return null;
    }

    private record Step(PDU response, SnmpAnswer answer) {}

    private static Step exchange(Snmp snmp, PDU pdu, UserTarget<UdpAddress> target) throws IOException {
        ResponseEvent<UdpAddress> event;
        try {
            event = send(snmp, pdu, target);
        } catch (MessageException refused) {
            return new Step(null, AUTH_STATUS.contains(refused.getSnmp4jErrorStatus())
                    ? new SnmpAnswer.AuthRefused() : new SnmpAnswer.NoAnswer());
        }
        if (event == null) return new Step(null, new SnmpAnswer.NoAnswer());
        if (event.getError() instanceof MessageException refused) {
            return new Step(null, AUTH_STATUS.contains(refused.getSnmp4jErrorStatus())
                    ? new SnmpAnswer.AuthRefused() : new SnmpAnswer.NoAnswer());
        }
        PDU response = event.getResponse();
        if (response == null) return new Step(null, new SnmpAnswer.NoAnswer());
        if (response.getType() == PDU.REPORT) {
            return new Step(null, authReport(response) ? new SnmpAnswer.AuthRefused() : new SnmpAnswer.NoAnswer());
        }
        if (response.getType() != PDU.RESPONSE || response.getErrorStatus() != PDU.noError) {
            return new Step(null, new SnmpAnswer.NoAnswer());
        }
        return new Step(response, null);
    }

    static boolean authReport(PDU report) {
        for (VariableBinding vb : report.getVariableBindings()) {
            String oid = vb.getOid().toDottedString();
            for (String counter : AUTH_REPORTS) if (SnmpOids.under(oid, counter)) return true;
        }
        return false;
    }

    /** The only send path. Read-only by construction: any PDU type other than GET or GETBULK is refused. */
    static ResponseEvent<UdpAddress> send(Snmp snmp, PDU pdu, UserTarget<UdpAddress> target) throws IOException {
        requireReadOnly(pdu.getType());
        return snmp.send(pdu, target);
    }

    static void requireReadOnly(int pduType) {
        if (pduType != PDU.GET && pduType != PDU.GETBULK) throw new IllegalStateException("SNMP_WRITE_REFUSED");
    }

    private static void requirePermitted(SnmpVendorProfile profile, String oid) {
        if (!profile.permits(oid)) throw new IllegalStateException("SNMP_OID_NOT_GATED");
    }

    static SnmpValue value(Variable v) {
        if (v == null || v.isException()) return SnmpValue.MISSING;
        return switch (v.getSyntax()) {
            case org.snmp4j.smi.SMIConstants.SYNTAX_INTEGER32, org.snmp4j.smi.SMIConstants.SYNTAX_COUNTER32,
                    org.snmp4j.smi.SMIConstants.SYNTAX_GAUGE32, org.snmp4j.smi.SMIConstants.SYNTAX_TIMETICKS,
                    org.snmp4j.smi.SMIConstants.SYNTAX_COUNTER64 -> SnmpValue.number(v.toLong());
            case org.snmp4j.smi.SMIConstants.SYNTAX_OCTET_STRING -> SnmpValue.text(((OctetString) v).toString());
            case org.snmp4j.smi.SMIConstants.SYNTAX_OBJECT_IDENTIFIER -> SnmpValue.oid(((OID) v).toDottedString());
            default -> SnmpValue.MISSING;
        };
    }

    private static byte[] bytes(char[] chars) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] out = new byte[encoded.remaining()];
        encoded.get(out);
        if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        return out;
    }
}
