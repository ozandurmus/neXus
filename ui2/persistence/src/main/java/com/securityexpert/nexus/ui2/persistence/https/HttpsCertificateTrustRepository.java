package com.securityexpert.nexus.ui2.persistence.https;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** Endpoint-scoped TOFU shared by discovery and enrolled devices. No certificate bytes leave the TLS callback. */
public final class HttpsCertificateTrustRepository {
    public enum Decision { FIRST_USE, MATCH, WARN, REFUSE }
    public record Certificate(String fingerprintSha256, String subjectCn, String issuerCn, Instant notAfter) {
        @Override public String toString() { return "Certificate[redacted]"; }
    }
    public record Entry(String trustEntryId, Certificate certificate, String status) {}
    public record Endpoint(String address, int port, boolean strict) {
        @Override public String toString() { return "Endpoint[redacted]"; }
    }
    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public HttpsCertificateTrustRepository(TransactionBoundary transactions) {
        this.transactions = transactions;
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    // Same defaults as the existing HTTPS execution paths; explicit ports always win.
    private static final String ENDPOINTS = "SELECT e.address_ref, d.https_certificate_strict, "
            + "CASE WHEN d.vendor_hint = 'bluecoat' AND d.role = 'management_server' THEN 8082 ELSE 443 END AS default_port "
            + "FROM endpoints e JOIN devices d ON d.device_id = e.device_id ";

    private static Endpoint endpoint(Record row) {
        String address = row.get("address_ref", String.class);
        int colon = address.lastIndexOf(':');
        int port = row.get("default_port", Integer.class);
        if (colon >= 0) {
            port = Integer.parseInt(address.substring(colon + 1));
            address = address.substring(0, colon);
        }
        return new Endpoint(address, port, Boolean.TRUE.equals(row.get("https_certificate_strict", Boolean.class)));
    }

    public Optional<Endpoint> deviceEndpoint(String deviceId) {
        return transactions.inTransaction(dsl -> dsl.fetch(ENDPOINTS
                + "WHERE d.device_id = ? AND d.vendor_hint <> 'palo_alto' "
                + "AND (e.transport_kind = 'https' OR (d.vendor_hint = 'fortinet' AND d.role = 'management_server')) "
                + "ORDER BY e.created_at LIMIT 1", deviceId)
                .stream().map(HttpsCertificateTrustRepository::endpoint).findFirst());
    }

    public Decision observe(String address, int port, Certificate certificate) {
        return audited.inTransaction("system:worker", "https_certificate_observe", dsl -> {
            lock(dsl, address, port);
            var active = dsl.fetchOne("SELECT fingerprint_sha256 FROM https_endpoint_cert_trust "
                    + "WHERE management_address = ? AND management_port = ? AND status = 'ACTIVE'", address, port);
            if (active == null) {
                insert(dsl, address, port, certificate, "ACTIVE");
                return Decision.FIRST_USE;
            }
            if (certificate.fingerprintSha256().equals(active.get(0, String.class))) return Decision.MATCH;
            if (dsl.fetchOne("SELECT trust_entry_id FROM https_endpoint_cert_trust WHERE management_address = ? "
                    + "AND management_port = ? AND fingerprint_sha256 = ? AND status = 'PENDING'",
                    address, port, certificate.fingerprintSha256()) == null)
                insert(dsl, address, port, certificate, "PENDING");
            boolean strict = dsl.fetch(ENDPOINTS + "WHERE d.vendor_hint <> 'palo_alto' "
                    + "AND e.address_ref IN (?, ?)", address, address + ":" + port).stream()
                    .map(HttpsCertificateTrustRepository::endpoint)
                    .anyMatch(e -> e.address().equals(address) && e.port() == port && e.strict());
            return strict ? Decision.REFUSE : Decision.WARN;
        });
    }

    public List<Entry> entries(String address, int port) {
        return transactions.inTransaction(dsl -> dsl.fetch("SELECT trust_entry_id, fingerprint_sha256, subject_cn, "
                + "issuer_cn, not_after, status FROM https_endpoint_cert_trust WHERE management_address = ? "
                + "AND management_port = ? AND status IN ('ACTIVE', 'PENDING') ORDER BY first_seen_at, trust_entry_id", address, port)
                .stream().map(r -> new Entry(r.get("trust_entry_id", String.class),
                        new Certificate(r.get("fingerprint_sha256", String.class), r.get("subject_cn", String.class),
                                r.get("issuer_cn", String.class), r.get("not_after", Timestamp.class).toInstant()),
                        r.get("status", String.class))).toList());
    }

    /** Accept exactly the observation displayed by the UI, never an arbitrary submitted fingerprint. */
    public boolean accept(String address, int port, String pendingId, String actor) {
        return audited.inTransaction(actor, "https_certificate_accept", dsl -> {
            lock(dsl, address, port);
            if (dsl.fetchOne("SELECT trust_entry_id FROM https_endpoint_cert_trust WHERE trust_entry_id = ? "
                    + "AND management_address = ? AND management_port = ? AND status = 'PENDING'",
                    pendingId, address, port) == null) return false;
            dsl.execute("UPDATE https_endpoint_cert_trust SET status = 'SUPERSEDED' WHERE management_address = ? "
                    + "AND management_port = ? AND status IN ('ACTIVE', 'PENDING') AND trust_entry_id <> ?", address, port, pendingId);
            dsl.execute("UPDATE https_endpoint_cert_trust SET status = 'ACTIVE', authorized_by = ?, authorized_at = now() "
                    + "WHERE trust_entry_id = ?", actor, pendingId);
            return true;
        });
    }

    public boolean setStrict(String deviceId, boolean strict, String actor) {
        if (deviceEndpoint(deviceId).isEmpty()) return false;
        return audited.inTransaction(actor, "https_certificate_strict", dsl -> dsl.execute(
                "UPDATE devices SET https_certificate_strict = ? WHERE device_id = ? AND vendor_hint <> 'palo_alto'",
                strict, deviceId) == 1);
    }

    private static void lock(DSLContext dsl, String address, int port) {
        // Serialize first use as well as rotation, even before an endpoint/device row exists.
        dsl.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "https:" + address + ":" + port);
    }

    private static void insert(DSLContext dsl, String address, int port, Certificate c, String status) {
        dsl.execute("INSERT INTO https_endpoint_cert_trust (trust_entry_id, management_address, management_port, "
                + "fingerprint_sha256, subject_cn, issuer_cn, not_after, status, authorized_by, authorized_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'ACTIVE' THEN now() ELSE NULL END)", UUID.randomUUID().toString(), address, port, c.fingerprintSha256(),
                c.subjectCn(), c.issuerCn(), Timestamp.from(c.notAfter()), status,
                "ACTIVE".equals(status) ? "system:worker" : null, status);
    }
}
