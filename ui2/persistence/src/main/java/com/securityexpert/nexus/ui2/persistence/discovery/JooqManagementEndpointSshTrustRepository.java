package com.securityexpert.nexus.ui2.persistence.discovery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** C10: rotation and its sanitized audit are one database transaction. */
public final class JooqManagementEndpointSshTrustRepository implements ManagementEndpointSshTrustRepository {
    private final TransactionBoundary transactions;

    public JooqManagementEndpointSshTrustRepository(TransactionBoundary transactions) {
        this.transactions = transactions;
    }

    @Override
    public Optional<String> findActiveFingerprint(String address, int port, String algorithm) {
        return transactions.inTransaction(dsl -> Optional.ofNullable(dsl.fetchOne(
                "SELECT fingerprint_sha256 FROM management_endpoint_ssh_trust WHERE vendor = 'CHECK_POINT' "
                + "AND management_address = ? AND management_port = ? AND key_algorithm = ? AND status = 'ACTIVE'",
                address, port, algorithm)).map(row -> row.get(0, String.class)));
    }

    @Override
    public List<String> findActiveAlgorithms(String address, int port) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "SELECT key_algorithm FROM management_endpoint_ssh_trust WHERE vendor = 'CHECK_POINT' "
                + "AND management_address = ? AND management_port = ? AND status = 'ACTIVE' ORDER BY key_algorithm",
                address, port).getValues(0, String.class));
    }

    @Override
    public boolean enroll(String address, int port, String algorithm, String fingerprint,
            String actorFingerprint, Instant observedAt, boolean reEnroll) {
        return transactions.inTransaction(dsl -> {
            dsl.execute("SELECT set_config('app.actor_fingerprint', ?, true)", actorFingerprint);
            dsl.execute("SELECT set_config('app.action_id', ?, true)",
                    reEnroll ? "discovery_ssh_trust_re_enroll" : "discovery_ssh_trust_enroll");
            var existing = dsl.fetchOne("SELECT trust_entry_id FROM management_endpoint_ssh_trust "
                    + "WHERE management_address = ? AND management_port = ? AND key_algorithm = ? "
                    + "AND status = 'ACTIVE' FOR UPDATE", address, port, algorithm);
            if ((existing != null) != reEnroll) {
                return false;
            }
            String id = UUID.randomUUID().toString();
            if (existing != null) {
                dsl.execute("UPDATE management_endpoint_ssh_trust SET status = 'SUPERSEDED', superseded_by = ? "
                        + "WHERE trust_entry_id = ?", id, existing.get(0, String.class));
            }
            dsl.execute("INSERT INTO management_endpoint_ssh_trust (trust_entry_id, vendor, management_address, "
                    + "management_port, key_algorithm, fingerprint_sha256, status, authorized_by, authorized_at, observed_at) "
                    + "VALUES (?, 'CHECK_POINT', ?, ?, ?, ?, 'ACTIVE', ?, CURRENT_TIMESTAMP, ?)",
                    id, address, port, algorithm, fingerprint, actorFingerprint,
                    java.sql.Timestamp.from(observedAt));
            return true;
        });
    }
}
