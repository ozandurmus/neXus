package com.securityexpert.nexus.ui2.worker.snmp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;

import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.SnmpSettings;
import com.securityexpert.nexus.ui2.worker.credential.StoreBackedSnmpCredentialResolver.Material;
import com.securityexpert.nexus.ui2.worker.snmp.SnmpClient.SnmpAnswer;
import com.securityexpert.nexus.ui2.worker.snmp.SnmpClient.SnmpValue;

/** The SNMP4J client: read-only by construction, gated OIDs only, value mapping, USM refusal detection. */
class Snmp4jClientTest {

    private static Material material(String level, String auth, String priv) {
        return new Material(CredentialKind.SNMP_V3, "synthetic-user", new SnmpSettings(level, auth, priv),
                ("synthetic-" + "auth-1").toCharArray(), priv == null ? new char[0] : ("synthetic-" + "priv-1").toCharArray());
    }

    @Test
    void onlyGetAndGetBulkMaySend() {
        assertDoesNotThrow(() -> Snmp4jClient.requireReadOnly(PDU.GET));
        assertDoesNotThrow(() -> Snmp4jClient.requireReadOnly(PDU.GETBULK));
        for (int type : new int[] {PDU.SET, PDU.GETNEXT, PDU.INFORM, PDU.TRAP, PDU.RESPONSE, PDU.REPORT}) {
            assertThrows(IllegalStateException.class, () -> Snmp4jClient.requireReadOnly(type), "type " + type);
        }
    }

    @Test
    void noSourceInTheSnmpPackageNamesASetPdu() throws IOException {
        Path dir = Path.of("src/main/java/com/securityexpert/nexus/ui2/worker/snmp");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String src = Files.readString(f);
                assertFalse(src.contains("PDU.SET"), f.toString());
                assertFalse(src.contains("setType(PDU.GETNEXT"), f.toString());
            }
        }
    }

    @Test
    void theProfilesPermitExactlyTheirGatedOids() {
        for (SnmpVendorProfile p : SnmpVendorProfile.values()) {
            p.getOids().forEach(oid -> assertTrue(p.permits(oid), oid));
            p.walkColumns().forEach(oid -> assertTrue(p.permits(oid), oid));
        }
        assertTrue(SnmpVendorProfile.PALO_ALTO.permits(SnmpOids.HR_STORAGE_USED + ".7"));
        assertFalse(SnmpVendorProfile.PALO_ALTO.permits("1.3.6.1.2.1.1.5.0"), "sysName is not gated");
        assertFalse(SnmpVendorProfile.PALO_ALTO.permits("1.3.6.1.2.1.25.2.3"), "the table above the entry is not gated");
        assertFalse(SnmpVendorProfile.CHECK_POINT.permits(SnmpOids.CP_PROC_USAGE + ".1"), "a GET permits its instance only");
        assertFalse(SnmpVendorProfile.CHECK_POINT.permits("1.3.6.1.4.1.2620.1.6.7.4.5.0"), "memFreeReal64 has no gate row");
        assertFalse(SnmpVendorProfile.CHECK_POINT.permits(SnmpOids.PAN_SESSION_ACTIVE));
    }

    @Test
    void onlyAuthPrivWithSha2AndAesIsSuitable() {
        assertTrue(Snmp4jClient.suitable(material("authPriv", "SHA-256", "AES-128")));
        assertTrue(Snmp4jClient.suitable(material("authPriv", "SHA-512", "AES-256")));
        assertFalse(Snmp4jClient.suitable(material("authPriv", "SHA1", "AES-128")));
        assertFalse(Snmp4jClient.suitable(material("authPriv", "MD5", "AES-128")));
        assertFalse(Snmp4jClient.suitable(material("authPriv", "SHA-256", "DES")));
        assertFalse(Snmp4jClient.suitable(material("authNoPriv", "SHA-256", null)));
        assertFalse(Snmp4jClient.suitable(null));
    }

    @Test
    void aWeakCredentialIsRefusedBeforeAnySocketOpens() {
        assertThrows(IllegalArgumentException.class, () -> new Snmp4jClient().poll("192.0.2.1",
                material("authPriv", "MD5", "DES"), SnmpVendorProfile.CHECK_POINT));
    }

    @Test
    void valuesMapByTheirSyntaxAndExceptionsAreMissing() {
        assertEquals(SnmpValue.number(37), Snmp4jClient.value(new Integer32(37)));
        assertEquals(SnmpValue.number(123), Snmp4jClient.value(new TimeTicks(123)));
        assertEquals(SnmpValue.number(16_000_000_000L), Snmp4jClient.value(new Counter64(16_000_000_000L)));
        assertEquals(SnmpValue.text("8000"), Snmp4jClient.value(new OctetString("8000")));
        assertEquals(SnmpValue.oid(SnmpOids.HR_STORAGE_RAM), Snmp4jClient.value(new OID(SnmpOids.HR_STORAGE_RAM)));
        assertEquals(SnmpValue.MISSING, Snmp4jClient.value(Null.noSuchObject));
        assertEquals(SnmpValue.MISSING, Snmp4jClient.value(Null.noSuchInstance));
        assertEquals(SnmpValue.MISSING, Snmp4jClient.value(Null.endOfMibView));
        assertEquals(SnmpValue.MISSING, Snmp4jClient.value(null));
    }

    @Test
    void usmStatsReportsAreAuthRefusalsAndOtherReportsAreNot() {
        for (String counter : List.of("1.3.6.1.6.3.15.1.1.1.0", "1.3.6.1.6.3.15.1.1.3.0", "1.3.6.1.6.3.15.1.1.5.0",
                "1.3.6.1.6.3.15.1.1.6.0")) {
            ScopedPDU report = new ScopedPDU();
            report.setType(PDU.REPORT);
            report.add(new VariableBinding(new OID(counter), new Integer32(1)));
            assertTrue(Snmp4jClient.authReport(report), counter);
        }
        ScopedPDU timeWindow = new ScopedPDU();
        timeWindow.setType(PDU.REPORT);
        timeWindow.add(new VariableBinding(new OID("1.3.6.1.6.3.15.1.1.2.0"), new Integer32(1)));
        assertFalse(Snmp4jClient.authReport(timeWindow));
    }

    @Test
    void anUnreachableAgentIsNoAnswerAfterTimeoutAndRetry() {
        // 127.0.0.1:161 has no agent in the test environment; the full USM setup runs and the poll ends as NoAnswer.
        long start = System.nanoTime();
        SnmpAnswer answer = new Snmp4jClient().poll("127.0.0.1", material("authPriv", "SHA-256", "AES-128"),
                SnmpVendorProfile.CHECK_POINT);
        assertInstanceOf(SnmpAnswer.NoAnswer.class, answer);
        assertTrue(System.nanoTime() - start < 20_000_000_000L, "bounded by timeout and one retry");
    }
}
