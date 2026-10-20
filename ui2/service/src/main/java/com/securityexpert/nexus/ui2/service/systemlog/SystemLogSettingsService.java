package com.securityexpert.nexus.ui2.service.systemlog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.Settings;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogSettingsRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogSettingsRepository.Row;

/**
 * System-log collection settings (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 14, {@code GET/PUT
 * /api/v2/config/system-logs}; section 5.3 retention and caps, section 2.3 {@code include_management_servers}, section
 * 8.3 {@code spike_data_enabled}). The documented keys are the closed list {@link Key}; a body naming any other key, a
 * value of the wrong type, or a value outside the bounds of the V160 CHECK constraints is refused as a whole and
 * nothing is written. A write replaces the row in one audited transaction ({@link SystemLogSettingsRepository#WRITE_ACTION})
 * and answers the row as stored.
 */
public final class SystemLogSettingsService {
    /** The documented keys with the V160 bounds (the contract's section 5.3 defaults sit inside them). */
    public enum Key {
        RAW_RETENTION_DAYS("raw_retention_days", Type.INTEGER, 1, 3650),
        DEVICE_CAP_BYTES("device_cap_bytes", Type.INTEGER, 64L * 1024 * 1024, 1024L * 1024 * 1024 * 1024),
        HCP_RETENTION_DAYS("hcp_retention_days", Type.INTEGER, 1, 3650),
        SPIKE_DATA_RETENTION_DAYS("spike_data_retention_days", Type.INTEGER, 1, 3650),
        SPIKE_DATA_CAP_BYTES("spike_data_cap_bytes", Type.INTEGER, 1024L * 1024, 1024L * 1024 * 1024 * 1024),
        LEDGER_RETENTION_DAYS("ledger_retention_days", Type.INTEGER, 1, 3650),
        INCLUDE_MANAGEMENT_SERVERS("include_management_servers", Type.BOOLEAN, 0, 0),
        SPIKE_DATA_ENABLED("spike_data_enabled", Type.BOOLEAN, 0, 0);

        public enum Type { INTEGER, BOOLEAN }

        public final String key;
        public final Type type;
        public final long min;
        public final long max;

        Key(String key, Type type, long min, long max) {
            this.key = key;
            this.type = type;
            this.min = min;
            this.max = max;
        }

        static Key of(String key) {
            for (Key k : values()) if (k.key.equals(key)) return k;
            return null;
        }
    }

    /** A refusal: the status, a stable code, a sentence, and the per-key problems (empty for a non-validation refusal). */
    public record Refusal(int status, String code, String message, List<String> problems) {
        public Refusal {
            problems = List.copyOf(problems);
        }
    }

    /** The answer to an update: the stored view, or the refusal. */
    public record Result(Map<String, Object> body, Refusal refusal) {
        public boolean isOk() {
            return refusal == null;
        }

        static Result ok(Map<String, Object> body) {
            return new Result(body, null);
        }

        static Result refused(int status, String code, String message, List<String> problems) {
            return new Result(null, new Refusal(status, code, message, problems));
        }
    }

    private final SystemLogSettingsRepository repository;

    public SystemLogSettingsService(SystemLogSettingsRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /** The stored row as the routes answer it; {@code canWrite} is the caller's own write permission, decided by the controller. */
    public Map<String, Object> view(boolean canWrite) {
        return view(repository.read(), canWrite);
    }

    /**
     * Applies {@code body} (documented keys only, any subset) over the stored row and writes the result under the
     * actor. Every problem is reported at once; an empty or unknown-only body is refused, so a misspelt key never
     * silently does nothing.
     */
    public Result update(String actorFingerprint, Map<String, Object> body) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return Result.refused(403, "SYSTEM_LOG_SETTINGS_NOT_PERMITTED", "No authenticated session.", List.of());
        }
        if (body == null || body.isEmpty()) {
            return Result.refused(400, "SYSTEM_LOG_SETTINGS_EMPTY", "The body names no setting.", List.of());
        }
        List<String> problems = new ArrayList<>();
        Map<Key, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            Key key = Key.of(entry.getKey());
            if (key == null) {
                problems.add(entry.getKey() + ": not a system-log setting");
                continue;
            }
            Object value = entry.getValue();
            switch (key.type) {
                case BOOLEAN -> {
                    if (value instanceof Boolean b) values.put(key, b);
                    else problems.add(key.key + ": must be true or false");
                }
                case INTEGER -> {
                    Long n = integral(value);
                    if (n == null) problems.add(key.key + ": must be a whole number");
                    else if (n < key.min || n > key.max) problems.add(key.key + ": must be between " + key.min + " and " + key.max);
                    else values.put(key, n);
                }
                default -> throw new IllegalStateException(key.type.name());
            }
        }
        if (!problems.isEmpty()) {
            return Result.refused(400, "SYSTEM_LOG_SETTINGS_INVALID", "Nothing was changed: " + String.join("; ", problems) + ".",
                    problems);
        }
        Settings current = repository.read().settings();
        Settings next = new Settings(
                intOf(values, Key.RAW_RETENTION_DAYS, current.rawRetentionDays()),
                boolOf(values, Key.INCLUDE_MANAGEMENT_SERVERS, current.includeManagementServers()),
                longOf(values, Key.DEVICE_CAP_BYTES, current.deviceCapBytes()),
                intOf(values, Key.HCP_RETENTION_DAYS, current.hcpRetentionDays()),
                intOf(values, Key.SPIKE_DATA_RETENTION_DAYS, current.spikeDataRetentionDays()),
                longOf(values, Key.SPIKE_DATA_CAP_BYTES, current.spikeDataCapBytes()),
                intOf(values, Key.LEDGER_RETENTION_DAYS, current.ledgerRetentionDays()),
                boolOf(values, Key.SPIKE_DATA_ENABLED, current.spikeDataEnabled()));
        return Result.ok(view(repository.write(actorFingerprint, next), true));
    }

    static Map<String, Object> view(Row row, boolean canWrite) {
        Settings s = row.settings();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(Key.RAW_RETENTION_DAYS.key, s.rawRetentionDays());
        body.put(Key.DEVICE_CAP_BYTES.key, s.deviceCapBytes());
        body.put(Key.HCP_RETENTION_DAYS.key, s.hcpRetentionDays());
        body.put(Key.SPIKE_DATA_RETENTION_DAYS.key, s.spikeDataRetentionDays());
        body.put(Key.SPIKE_DATA_CAP_BYTES.key, s.spikeDataCapBytes());
        body.put(Key.LEDGER_RETENTION_DAYS.key, s.ledgerRetentionDays());
        body.put(Key.INCLUDE_MANAGEMENT_SERVERS.key, s.includeManagementServers());
        body.put(Key.SPIKE_DATA_ENABLED.key, s.spikeDataEnabled());
        body.put("updated_at", row.updatedAt() == null ? null : row.updatedAt().toString());
        body.put("updated_by", row.updatedBy());
        body.put("can_write", canWrite);
        Map<String, Object> bounds = new LinkedHashMap<>();
        for (Key key : Key.values()) {
            Map<String, Object> bound = new LinkedHashMap<>();
            bound.put("type", key.type.name().toLowerCase(java.util.Locale.ROOT));
            if (key.type == Key.Type.INTEGER) {
                bound.put("min", key.min);
                bound.put("max", key.max);
            }
            bounds.put(key.key, bound);
        }
        body.put("bounds", bounds);
        return body;
    }

    /** A JSON number is a whole number when it has no fractional part; a string is never a number here. */
    private static Long integral(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof java.math.BigInteger big) {
            return big.bitLength() < 64 ? big.longValue() : null;
        }
        if (value instanceof Number n) {
            BigDecimal d = new BigDecimal(n.toString());
            try {
                return d.longValueExact();
            } catch (ArithmeticException notWhole) {
                return null;
            }
        }
        return null;
    }

    private static int intOf(Map<Key, Object> values, Key key, int current) {
        return values.containsKey(key) ? (int) (long) (Long) values.get(key) : current;
    }

    private static long longOf(Map<Key, Object> values, Key key, long current) {
        return values.containsKey(key) ? (Long) values.get(key) : current;
    }

    private static boolean boolOf(Map<Key, Object> values, Key key, boolean current) {
        return values.containsKey(key) ? (Boolean) values.get(key) : current;
    }
}
