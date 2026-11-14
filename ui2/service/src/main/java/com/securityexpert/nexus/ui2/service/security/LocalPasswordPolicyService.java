package com.securityexpert.nexus.ui2.service.security;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalPasswordPolicyRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalPasswordPolicySettings;

/**
 * The administrator-facing side of the configurable local password policy ({@code GET/PUT /api/v2/config/local-password-policy}):
 * a closed list of keys, whole-body validation (nothing is written when any key is invalid), one audited write
 * ({@link LocalPasswordPolicyRepository#WRITE_ACTION}). Also answers the maximum-age question for the session gate,
 * from a short cache so a gated request never costs a database round trip of its own.
 */
public final class LocalPasswordPolicyService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(15);

    public enum Key {
        ENABLED("enabled", true, 0, 0),
        MIN_LENGTH("min_length", false, LocalPasswordPolicySettings.FLOOR_MIN_LENGTH, LocalPasswordPolicySettings.MAX_MIN_LENGTH),
        REQUIRE_UPPER("require_upper", true, 0, 0),
        REQUIRE_LOWER("require_lower", true, 0, 0),
        REQUIRE_DIGIT("require_digit", true, 0, 0),
        REQUIRE_SYMBOL("require_symbol", true, 0, 0),
        HISTORY_COUNT("history_count", false, 0, LocalPasswordPolicySettings.MAX_HISTORY_COUNT),
        MAX_AGE_DAYS("max_age_days", false, 0, LocalPasswordPolicySettings.MAX_AGE_DAYS_LIMIT);

        public final String key;
        public final boolean bool;
        public final int min;
        public final int max;

        Key(String key, boolean bool, int min, int max) {
            this.key = key;
            this.bool = bool;
            this.min = min;
            this.max = max;
        }

        static Key of(String key) {
            for (Key k : values()) {
                if (k.key.equals(key)) {
                    return k;
                }
            }
            return null;
        }
    }

    public record Refusal(int status, String code, String message, List<String> problems) {
        public Refusal {
            problems = List.copyOf(problems);
        }
    }

    public record Result(Map<String, Object> body, Refusal refusal) {
        public boolean isOk() {
            return refusal == null;
        }
    }

    private final LocalPasswordPolicyRepository repository;
    private volatile LocalPasswordPolicySettings cached;
    private volatile Instant cachedAt = Instant.MIN;

    public LocalPasswordPolicyService(LocalPasswordPolicyRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Map<String, Object> view(boolean canWrite) {
        return view(repository.read(), canWrite);
    }

    public Result update(String actorFingerprint, Map<String, Object> body) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return refused(403, "LOCAL_PASSWORD_POLICY_NOT_PERMITTED", "No authenticated session.", List.of());
        }
        if (body == null || body.isEmpty()) {
            return refused(400, "LOCAL_PASSWORD_POLICY_EMPTY", "The body names no setting.", List.of());
        }
        List<String> problems = new ArrayList<>();
        Map<Key, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            Key key = Key.of(entry.getKey());
            if (key == null) {
                problems.add(entry.getKey() + ": not a password-policy setting");
                continue;
            }
            Object value = entry.getValue();
            if (key.bool) {
                if (value instanceof Boolean b) {
                    values.put(key, b);
                } else {
                    problems.add(key.key + ": must be true or false");
                }
            } else {
                Integer n = integral(value);
                if (n == null) {
                    problems.add(key.key + ": must be a whole number");
                } else if (n < key.min || n > key.max) {
                    problems.add(key.key + ": must be between " + key.min + " and " + key.max);
                } else {
                    values.put(key, n);
                }
            }
        }
        if (!problems.isEmpty()) {
            return refused(400, "LOCAL_PASSWORD_POLICY_INVALID",
                    "Nothing was changed: " + String.join("; ", problems) + ".", problems);
        }
        LocalPasswordPolicySettings cur = repository.read().settings();
        LocalPasswordPolicySettings next = new LocalPasswordPolicySettings(
                bool(values, Key.ENABLED, cur.enabled()), num(values, Key.MIN_LENGTH, cur.minLength()),
                bool(values, Key.REQUIRE_UPPER, cur.requireUpper()), bool(values, Key.REQUIRE_LOWER, cur.requireLower()),
                bool(values, Key.REQUIRE_DIGIT, cur.requireDigit()), bool(values, Key.REQUIRE_SYMBOL, cur.requireSymbol()),
                num(values, Key.HISTORY_COUNT, cur.historyCount()), num(values, Key.MAX_AGE_DAYS, cur.maxAgeDays()));
        var stored = repository.write(actorFingerprint, next);
        cached = null;
        return new Result(view(stored, true), null);
    }

    /**
     * Whether policy-enforced expiry applies to {@code record} now: the policy is on, a maximum age is set, and the
     * password was set longer ago than that. Expiry only routes the identity to the password-change screen (the
     * must-change gate); the identity can always change its password with the current one, so it never locks anyone out.
     */
    public boolean passwordExpired(LocalCredentialRecord record, Instant now) {
        LocalPasswordPolicySettings s = settings(now);
        return s.enabled() && s.maxAgeDays() > 0
                && record.passwordSetAt().plus(Duration.ofDays(s.maxAgeDays())).isBefore(now);
    }

    private LocalPasswordPolicySettings settings(Instant now) {
        LocalPasswordPolicySettings c = cached;
        if (c != null && cachedAt.plus(CACHE_TTL).isAfter(now)) {
            return c;
        }
        try {
            c = repository.read().settings();
        } catch (RuntimeException unreadable) {
            // Fail open: an unreadable policy never forces anyone to the change screen.
            return LocalPasswordPolicySettings.DEFAULTS;
        }
        cached = c;
        cachedAt = now;
        return c;
    }

    static Map<String, Object> view(LocalPasswordPolicyRepository.Row row, boolean canWrite) {
        LocalPasswordPolicySettings s = row.settings();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(Key.ENABLED.key, s.enabled());
        body.put(Key.MIN_LENGTH.key, s.minLength());
        body.put(Key.REQUIRE_UPPER.key, s.requireUpper());
        body.put(Key.REQUIRE_LOWER.key, s.requireLower());
        body.put(Key.REQUIRE_DIGIT.key, s.requireDigit());
        body.put(Key.REQUIRE_SYMBOL.key, s.requireSymbol());
        body.put(Key.HISTORY_COUNT.key, s.historyCount());
        body.put(Key.MAX_AGE_DAYS.key, s.maxAgeDays());
        body.put("updated_at", row.updatedAt() == null ? null : row.updatedAt().toString());
        body.put("updated_by", row.updatedBy());
        body.put("can_write", canWrite);
        Map<String, Object> bounds = new LinkedHashMap<>();
        for (Key key : Key.values()) {
            Map<String, Object> bound = new LinkedHashMap<>();
            bound.put("type", key.bool ? "boolean" : "integer");
            if (!key.bool) {
                bound.put("min", key.min);
                bound.put("max", key.max);
            }
            bounds.put(key.key, bound);
        }
        body.put("bounds", bounds);
        return body;
    }

    private static Result refused(int status, String code, String message, List<String> problems) {
        return new Result(null, new Refusal(status, code, message, problems));
    }

    private static Integer integral(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            long n = ((Number) value).longValue();
            return n < Integer.MIN_VALUE || n > Integer.MAX_VALUE ? null : (int) n;
        }
        if (value instanceof Number n) {
            try {
                return new BigDecimal(n.toString()).intValueExact();
            } catch (ArithmeticException notWhole) {
                return null;
            }
        }
        return null;
    }

    private static boolean bool(Map<Key, Object> values, Key key, boolean current) {
        return values.containsKey(key) ? (Boolean) values.get(key) : current;
    }

    private static int num(Map<Key, Object> values, Key key, int current) {
        return values.containsKey(key) ? (Integer) values.get(key) : current;
    }
}
