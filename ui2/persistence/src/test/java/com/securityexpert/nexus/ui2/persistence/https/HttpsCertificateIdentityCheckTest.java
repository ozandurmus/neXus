package com.securityexpert.nexus.ui2.persistence.https;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository.Certificate;
import com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository.Decision;

/** The device header's "Identity verified" follows the last pinned comparison (PO decision 2026-10-18, V165). */
class HttpsCertificateIdentityCheckTest {
    private static final String ADDRESS = "192.0.2.10";
    private static final Certificate CERT = new Certificate("a".repeat(64), "FW-TANGO-04", "Synthetic issuer", Instant.parse("2030-01-01T00:00:00Z"));

    private static final class Store implements TransactionBoundary {
        String active;
        boolean failRecording;
        /** address_ref of the device that owns the endpoint; null = no device owns it. */
        String ownerAddressRef = ADDRESS + ":8443";
        final List<String> sql = new ArrayList<>();
        final List<Object[]> checks = new ArrayList<>();
        final DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            String query = ctx.sql(); sql.add(query);
            DSLContext create = DSL.using(SQLDialect.POSTGRES);
            String[][] data = {{"empty"}};
            if (query.startsWith("SELECT fingerprint_sha256"))
                data = active == null ? new String[][]{{"fingerprint_sha256"}} : new String[][]{{"fingerprint_sha256"}, {active}};
            else if (query.startsWith("SELECT trust_entry_id")) data = new String[][]{{"trust_entry_id"}};
            else if (query.startsWith("SELECT e.address_ref"))
                data = new String[][]{{"address_ref", "https_certificate_strict", "default_port"}, {ADDRESS + ":8443", "false", "443"}};
            else if (query.startsWith("SELECT e.device_id")) {
                if (failRecording) throw new java.sql.SQLException("synthetic: table missing");
                data = ownerAddressRef == null ? new String[][]{{"device_id", "address_ref", "https_certificate_strict", "default_port"}}
                        : new String[][]{{"device_id", "address_ref", "https_certificate_strict", "default_port"},
                            {"device-fixture", ownerAddressRef, "false", "443"}};
            } else if (query.startsWith("INSERT INTO device_identity_check")) checks.add(ctx.bindings());
            var rows = create.fetchFromStringData(data);
            return new MockResult[]{query.startsWith("SELECT") ? new MockResult(rows.size(), rows) : new MockResult(1, null)};
        }), SQLDialect.POSTGRES);
        @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(dsl); }
    }

    @Test void aMatchingCertificateRecordsAMatchForTheDeviceThatOwnsTheEndpoint() {
        var store = new Store(); store.active = CERT.fingerprintSha256();
        assertEquals(Decision.MATCH, new HttpsCertificateTrustRepository(store).observe(ADDRESS, 8443, CERT));
        assertEquals(1, store.checks.size());
        assertEquals(Arrays.asList("HTTPS_CERTIFICATE", "MATCH", "device-fixture"), Arrays.asList(store.checks.getFirst()));
    }

    @Test void aDifferentCertificateRecordsAMismatchWhetherWarnedOrRefused() {
        var store = new Store(); store.active = "b".repeat(64);
        assertEquals(Decision.WARN, new HttpsCertificateTrustRepository(store).observe(ADDRESS, 8443, CERT));
        assertEquals("MISMATCH", store.checks.getFirst()[1]);
    }

    @Test void pinningOnFirstUseIsNotAMatch() {
        var store = new Store();
        assertEquals(Decision.FIRST_USE, new HttpsCertificateTrustRepository(store).observe(ADDRESS, 8443, CERT));
        assertEquals("FIRST_USE", store.checks.getFirst()[1]);
    }

    @Test void aDeviceOnAnotherPortOfTheSameAddressIsNotRecorded() {
        var store = new Store(); store.active = CERT.fingerprintSha256(); store.ownerAddressRef = ADDRESS + ":9443";
        new HttpsCertificateTrustRepository(store).observe(ADDRESS, 8443, CERT);
        assertTrue(store.checks.isEmpty(), "a device on another port did not present this certificate");
    }

    @Test void noOwningDeviceMeansNothingIsRecordedAndTheDecisionStands() {
        var store = new Store(); store.active = CERT.fingerprintSha256(); store.ownerAddressRef = null;
        assertEquals(Decision.MATCH, new HttpsCertificateTrustRepository(store).observe(ADDRESS, 8443, CERT));
        assertTrue(store.checks.isEmpty());
    }

    @Test void theRecordingQueryExcludesPaloAltoAndNeverCarriesTheFingerprint() {
        var store = new Store(); store.active = CERT.fingerprintSha256();
        new HttpsCertificateTrustRepository(store).observe(ADDRESS, 8443, CERT);
        String query = store.sql.stream().filter(s -> s.startsWith("SELECT e.device_id")).findFirst().orElseThrow();
        assertTrue(query.contains("d.vendor_hint <> 'palo_alto'"));
        assertTrue(store.checks.stream().flatMap(Arrays::stream).noneMatch(b -> String.valueOf(b).contains(CERT.fingerprintSha256())));
    }

    @Test void aFailedRecordingNeverChangesTheTrustDecision() {
        var store = new Store(); store.active = CERT.fingerprintSha256(); store.failRecording = true;
        assertEquals(Decision.MATCH, new HttpsCertificateTrustRepository(store).observe(ADDRESS, 8443, CERT));
        store.active = "b".repeat(64);
        assertEquals(Decision.WARN, new HttpsCertificateTrustRepository(store).observe(ADDRESS, 8443, CERT));
    }
}
