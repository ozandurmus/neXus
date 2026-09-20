package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Append-only, hash-chained ledger recording all state transitions for scheduled failover executions.
 * Enforces single-use authorization consumption to prevent replay or resurrection attacks.
 *
 * <p>CF-P0.7: the class previously claimed storage-enforced {@code UNIQUE(grant_id)} while
 * consuming grants against a plain {@code ConcurrentHashMap} -- the claim was false, and every
 * consumed grant was lost on restart. This class now persists both the transition chain and grant
 * consumption to {@code failover_schedule_ledger} / {@code failover_grant_consumption} via
 * {@link JdbcTemplate}, with {@link DuplicateKeyException} on the grant table's primary key
 * translated to the single-use invariant violation.</p>
 *
 * <p>CF-P1.4: within a single JVM instance, {@link #recordTransition} serializes chain
 * construction (read current tip, compute the next hash, insert) under this instance's monitor --
 * the minimum viable fix the review named until cross-instance CAS/locking lands; an enforced
 * singleton deployment guard covers the remaining gap (see the Engine Final Review, §9 item 2).</p>
 *
 * <p>The lightweight no-arg constructor keeps the previous in-memory-only behavior for unit tests
 * that never wire a Spring {@link JdbcTemplate}; Spring-managed instances always resolve the
 * {@link JdbcTemplate} constructor and are durable by default.</p>
 */
@Component
public class FailoverScheduleLedger {

    public record TransitionEntry(
        long index,
        String prevHash,
        String entryHash,
        String scheduleId,
        FailoverScheduleStatus fromStatus,
        FailoverScheduleStatus toStatus,
        String attemptId,
        String actorId,
        Instant timestamp,
        String details
    ) {}

    public record GrantConsumption(
        String grantId,
        String scheduleId,
        String attemptId,
        Instant consumedAt
    ) {}

    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    /** {@code failover_schedule_ledger.attempt_id} is {@code NOT NULL}; transitions with no attempt in flight (schedule creation, cancellation) record this sentinel instead of null. */
    static final String NO_ATTEMPT = "NONE";

    private static final String SELECT_COLUMNS =
        "entry_index, prev_hash, entry_hash, schedule_id, from_status, to_status, attempt_id, actor_id, details, created_at ";

    private final JdbcTemplate jdbcTemplate;

    // In-memory fallback store, used only when no JdbcTemplate is wired (lightweight unit tests).
    private final List<TransitionEntry> memoryLedger = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, GrantConsumption> memoryConsumedGrants = new ConcurrentHashMap<>();

    @Autowired
    public FailoverScheduleLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /** Lightweight unit-test constructor: falls back to an in-process, non-durable ledger. */
    public FailoverScheduleLedger() {
        this.jdbcTemplate = null;
    }

    private boolean isDurable() {
        return jdbcTemplate != null;
    }

    public synchronized TransitionEntry recordTransition(
        String scheduleId,
        FailoverScheduleStatus fromStatus,
        FailoverScheduleStatus toStatus,
        String attemptId,
        String actorId,
        String details
    ) {
        Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        Objects.requireNonNull(toStatus, "toStatus must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");

        String effectiveAttemptId = attemptId != null ? attemptId : NO_ATTEMPT;
        String sanitizedDetails = details != null ? details : "";
        Instant now = Instant.now();

        if (isDurable()) {
            return recordTransitionDurable(scheduleId, fromStatus, toStatus, effectiveAttemptId, actorId, sanitizedDetails, now);
        }
        return recordTransitionInMemory(scheduleId, fromStatus, toStatus, effectiveAttemptId, actorId, sanitizedDetails, now);
    }

    private TransitionEntry recordTransitionDurable(
        String scheduleId, FailoverScheduleStatus fromStatus, FailoverScheduleStatus toStatus,
        String effectiveAttemptId, String actorId, String sanitizedDetails, Instant now
    ) {
        List<Map<String, Object>> tip = jdbcTemplate.queryForList(
            "SELECT entry_index, entry_hash FROM failover_schedule_ledger ORDER BY entry_index DESC LIMIT 1");

        long nextIndex;
        String prevHash;
        if (tip.isEmpty()) {
            nextIndex = 0L;
            prevHash = GENESIS_HASH;
        } else {
            nextIndex = ((Number) tip.get(0).get("entry_index")).longValue() + 1L;
            prevHash = (String) tip.get(0).get("entry_hash");
        }

        String entryHash = computeHash(prevHash, nextIndex, scheduleId, fromStatus, toStatus, effectiveAttemptId, actorId, sanitizedDetails, now);

        jdbcTemplate.update(
            "INSERT INTO failover_schedule_ledger " +
                "(entry_index, prev_hash, entry_hash, schedule_id, from_status, to_status, attempt_id, actor_id, details, created_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
            nextIndex, prevHash, entryHash, scheduleId,
            fromStatus != null ? fromStatus.name() : null,
            toStatus.name(), effectiveAttemptId, actorId, sanitizedDetails, Timestamp.from(now)
        );

        return new TransitionEntry(nextIndex, prevHash, entryHash, scheduleId, fromStatus, toStatus, effectiveAttemptId, actorId, now, sanitizedDetails);
    }

    private TransitionEntry recordTransitionInMemory(
        String scheduleId, FailoverScheduleStatus fromStatus, FailoverScheduleStatus toStatus,
        String effectiveAttemptId, String actorId, String sanitizedDetails, Instant now
    ) {
        long nextIndex = memoryLedger.size();
        String prevHash = memoryLedger.isEmpty() ? GENESIS_HASH : memoryLedger.get(memoryLedger.size() - 1).entryHash();
        String entryHash = computeHash(prevHash, nextIndex, scheduleId, fromStatus, toStatus, effectiveAttemptId, actorId, sanitizedDetails, now);

        TransitionEntry entry = new TransitionEntry(
            nextIndex, prevHash, entryHash, scheduleId, fromStatus, toStatus, effectiveAttemptId, actorId, now, sanitizedDetails
        );
        memoryLedger.add(entry);
        return entry;
    }

    /**
     * Enforces storage-level single-use consumption of an authorization grant.
     * Throws IllegalStateException if the grant has already been consumed.
     */
    public void consumeGrant(String grantId, String scheduleId, String attemptId) {
        Objects.requireNonNull(grantId, "grantId must not be null");
        Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        String effectiveAttemptId = attemptId != null ? attemptId : NO_ATTEMPT;

        if (isDurable()) {
            try {
                jdbcTemplate.update(
                    "INSERT INTO failover_grant_consumption (grant_id, schedule_id, attempt_id, consumed_at) VALUES (?,?,?,?)",
                    grantId, scheduleId, effectiveAttemptId, Timestamp.from(Instant.now()));
            } catch (DuplicateKeyException e) {
                throw new IllegalStateException(
                    "Authorization grant " + grantId + " has already been consumed (single-use grant invariant)", e);
            }
            return;
        }

        GrantConsumption inserted = new GrantConsumption(grantId, scheduleId, effectiveAttemptId, Instant.now());
        GrantConsumption existing = memoryConsumedGrants.putIfAbsent(grantId, inserted);
        if (existing != null) {
            throw new IllegalStateException("Authorization grant " + grantId + " has already been consumed at " +
                existing.consumedAt() + " by schedule " + existing.scheduleId() + " (single-use grant invariant)");
        }
    }

    public boolean isGrantConsumed(String grantId) {
        Objects.requireNonNull(grantId, "grantId must not be null");
        if (isDurable()) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM failover_grant_consumption WHERE grant_id = ?", Integer.class, grantId);
            return count != null && count > 0;
        }
        return memoryConsumedGrants.containsKey(grantId);
    }

    public List<TransitionEntry> getTransitionsForSchedule(String scheduleId) {
        Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        if (isDurable()) {
            return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + "FROM failover_schedule_ledger WHERE schedule_id = ? ORDER BY entry_index",
                (rs, rowNum) -> mapRow(rs), scheduleId);
        }

        List<TransitionEntry> result = new ArrayList<>();
        for (TransitionEntry entry : List.copyOf(memoryLedger)) {
            if (entry.scheduleId().equals(scheduleId)) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public boolean verifyChainIntegrity() {
        List<TransitionEntry> entries = allEntriesOrdered();
        String expectedPrev = GENESIS_HASH;
        for (int i = 0; i < entries.size(); i++) {
            TransitionEntry entry = entries.get(i);
            if (entry.index() != i) {
                return false;
            }
            if (!entry.prevHash().equals(expectedPrev)) {
                return false;
            }
            String computed = computeHash(
                entry.prevHash(), entry.index(), entry.scheduleId(), entry.fromStatus(), entry.toStatus(),
                entry.attemptId(), entry.actorId(), entry.details(), entry.timestamp()
            );
            if (!entry.entryHash().equals(computed)) {
                return false;
            }
            expectedPrev = entry.entryHash();
        }
        return true;
    }

    private List<TransitionEntry> allEntriesOrdered() {
        if (isDurable()) {
            return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + "FROM failover_schedule_ledger ORDER BY entry_index",
                (rs, rowNum) -> mapRow(rs));
        }
        return List.copyOf(memoryLedger);
    }

    private static TransitionEntry mapRow(ResultSet rs) throws SQLException {
        String fromStatusColumn = rs.getString("from_status");
        return new TransitionEntry(
            rs.getLong("entry_index"),
            rs.getString("prev_hash"),
            rs.getString("entry_hash"),
            rs.getString("schedule_id"),
            fromStatusColumn != null ? FailoverScheduleStatus.valueOf(fromStatusColumn) : null,
            FailoverScheduleStatus.valueOf(rs.getString("to_status")),
            rs.getString("attempt_id"),
            rs.getString("actor_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("details")
        );
    }

    private static String computeHash(
        String prevHash,
        long index,
        String scheduleId,
        FailoverScheduleStatus fromStatus,
        FailoverScheduleStatus toStatus,
        String attemptId,
        String actorId,
        String details,
        Instant timestamp
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = prevHash + "|" + index + "|" + scheduleId + "|" +
                (fromStatus != null ? fromStatus.name() : "NULL") + "|" +
                toStatus.name() + "|" +
                (attemptId != null ? attemptId : "NULL") + "|" +
                actorId + "|" +
                details + "|" +
                timestamp.toString();
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available: " + e.getMessage(), e);
        }
    }
}
