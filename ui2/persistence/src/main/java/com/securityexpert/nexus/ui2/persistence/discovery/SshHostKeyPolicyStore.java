package com.securityexpert.nexus.ui2.persistence.discovery;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.jooq.DSLContext;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** Mismatch admission and its durable warning commit before credentials are sent. */
public final class SshHostKeyPolicyStore {
    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public SshHostKeyPolicyStore(TransactionBoundary transactions) {
        this.transactions = transactions;
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    public static boolean permits(String mode, String actionClass, boolean rejected, boolean failover) {
        return "WARN".equals(mode) && "read".equals(actionClass) && !rejected && !failover;
    }

    private static String lock(DSLContext db) {
        var row = db.fetchOne("SELECT mode FROM ssh_trust_settings WHERE settings_id = 'default' FOR UPDATE");
        if (row == null) throw new IllegalStateException("SSH_TRUST_SETTINGS_MISSING");
        return row.get(0, String.class);
    }

    public String mode() {
        return transactions.inTransaction(db -> {
            var row = db.fetchOne("SELECT mode FROM ssh_trust_settings WHERE settings_id = 'default'");
            if (row == null) throw new IllegalStateException("SSH_TRUST_SETTINGS_MISSING");
            return row.get(0, String.class);
        });
    }

    public void mode(String mode, String actor) {
        if (!"WARN".equals(mode) && !"STRICT".equals(mode)) throw new IllegalArgumentException("INVALID_MODE");
        audited.inTransaction(actor, "ssh_trust_mode_write", db -> {
            lock(db);
            return db.execute("UPDATE ssh_trust_settings SET mode = ? WHERE settings_id = 'default'", mode);
        });
    }

    public boolean observe(String jobId, long epoch, boolean failover, String endpointId,
            String host, int port, String algorithm, String presented, String expected) {
        return audited.inTransaction("system:worker", "ssh_host_key_mismatch", db -> {
            String mode = lock(db);
            var job = db.fetchOne("SELECT target_device_id, action_class, job_type FROM jobs "
                    + "WHERE job_id = ? AND lease_epoch = ? AND state IN ('CLAIMED', 'EXECUTING')", jobId, epoch);
            String deviceId = job == null ? null : job.get("target_device_id", String.class);
            if (deviceId == null) {
                var endpoint = db.fetchOne("SELECT device_id FROM endpoints WHERE endpoint_id = ?", endpointId);
                if (endpoint != null) deviceId = endpoint.get(0, String.class);
            }
            boolean rejected = !db.fetch("SELECT 1 FROM ssh_host_key_observation "
                    + "WHERE strict_refusal AND (device_id = ? OR (management_address = ? AND management_port = ?))",
                    deviceId, host, port).isEmpty();
            boolean allowed = permits(mode, job == null ? null : job.get("action_class", String.class), rejected,
                    failover);
            db.execute("INSERT INTO ssh_host_key_observation(observation_id, device_id, endpoint_id, management_address, "
                    + "management_port, key_algorithm, presented_fingerprint, expected_fingerprint, last_job_id, connection_allowed) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT "
                    + "(endpoint_id, management_address, management_port, key_algorithm, presented_fingerprint, expected_fingerprint) "
                    + "DO UPDATE SET last_seen = now(), seen_count = ssh_host_key_observation.seen_count + 1, "
                    + "last_job_id = EXCLUDED.last_job_id, connection_allowed = EXCLUDED.connection_allowed, "
                    + "status = CASE WHEN ssh_host_key_observation.status = 'ACCEPTED' THEN 'OPEN' ELSE ssh_host_key_observation.status END",
                    UUID.randomUUID().toString(), deviceId, endpointId, host, port, algorithm, presented, expected, jobId, allowed);
            if (allowed) {
                db.execute("UPDATE jobs SET ssh_identity_unverified = true WHERE job_id = ? AND lease_epoch = ?", jobId, epoch);
                if (deviceId != null) db.execute("UPDATE devices SET ssh_identity_unverified = true WHERE device_id = ?", deviceId);
            }
            return allowed;
        });
    }

    /** Existing discovery candidates are management-plane evidence; direct facts are not substituted. */
    public List<Map<String, Object>> observations(String deviceId) {
        return transactions.inTransaction(db -> db.fetch("SELECT o.observation_id, o.device_id, "
                + "coalesce(d.observed_hostname, c.display_name, 'Unknown device') AS hostname, "
                + "o.key_algorithm, o.presented_fingerprint, left(o.expected_fingerprint, 16) AS expected_fingerprint_prefix, "
                + "o.first_seen, o.last_seen, o.seen_count, o.status, o.strict_refusal, d.ssh_identity_unverified, "
                + "c.model, c.software_version, NULL::text AS serial_number, "
                + "CASE WHEN c.candidate_id IS NULL THEN 'UNKNOWN' ELSE 'MANAGEMENT_DISCOVERY' END AS management_source "
                + "FROM ssh_host_key_observation o LEFT JOIN devices d ON d.device_id = o.device_id "
                + "LEFT JOIN LATERAL (SELECT candidate_id, display_name, model, software_version, stable_identifier "
                + "FROM discovery_candidate c JOIN discovery_run r ON r.run_id = c.run_id WHERE (c.vendor || '|' || coalesce(c.owning_domain, '') || '|' || c.stable_identifier) = d.discovery_match_key "
                + "OR (c.vendor || '|' || c.stable_identifier) = d.discovery_match_key ORDER BY r.created_at DESC LIMIT 1) c ON true "
                + "WHERE (?::text IS NULL OR o.device_id = ?) ORDER BY o.last_seen DESC", deviceId, deviceId)
                .intoMaps());
    }

    public record Acceptance(String address, int port, String algorithm, String fingerprint, Instant seenAt,
            TransactionBoundary transaction) {}

    /** The service supplies its existing independently-verified re-enrollment inside this same transaction. */
    public boolean resolve(String id, String actor, boolean accept, Function<Acceptance, Boolean> reEnroll) {
        return audited.inTransaction(actor, accept ? "ssh_host_key_accept" : "ssh_host_key_reject", db -> {
            lock(db);
            var row = db.fetchOne("SELECT * FROM ssh_host_key_observation WHERE observation_id = ? FOR UPDATE", id);
            if (row == null || "ACCEPTED".equals(row.get("status", String.class))) return false;
            if (accept) {
                var pin = db.fetchOne("SELECT fingerprint_sha256 FROM management_endpoint_ssh_trust "
                        + "WHERE management_address = ? AND management_port = ? AND key_algorithm = ? AND status = 'ACTIVE' FOR UPDATE",
                        row.get("management_address"), row.get("management_port"), row.get("key_algorithm"));
                if (pin == null || !row.get("expected_fingerprint", String.class).equals(pin.get(0, String.class))) return false;
                TransactionBoundary sameTransaction = new TransactionBoundary() {
                    public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(db); }
                };
                if (!reEnroll.apply(new Acceptance(row.get("management_address", String.class),
                        row.get("management_port", Integer.class), row.get("key_algorithm", String.class),
                        row.get("presented_fingerprint", String.class), row.get("last_seen", Timestamp.class).toInstant(), sameTransaction))) {
                    throw new IllegalStateException("SSH_TRUST_RE_ENROLL_FAILED");
                }
                // Re-enrollment sets its own audit action; restore the resolution action.
                db.execute("SELECT set_config('app.action_id', 'ssh_host_key_accept', true)");
            }
            if (accept) {
                db.execute("UPDATE ssh_host_key_observation SET strict_refusal = false WHERE strict_refusal AND "
                        + "(device_id = ? OR (management_address = ? AND management_port = ?))",
                        row.get("device_id"), row.get("management_address"), row.get("management_port"));
            }
            db.execute("UPDATE ssh_host_key_observation SET status = ?, strict_refusal = ? WHERE observation_id = ?",
                    accept ? "ACCEPTED" : "REJECTED", !accept, id);
            return true;
        });
    }
}
