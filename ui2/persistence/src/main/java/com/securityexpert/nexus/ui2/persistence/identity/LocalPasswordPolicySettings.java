package com.securityexpert.nexus.ui2.persistence.identity;

/**
 * The configurable local-account password policy (backlog ui2_password_policy_configurable). {@code enabled = false}
 * is the default and means the original rules only (the 12-character floor and not-equal-to-the-account-name);
 * every other field is read only while {@code enabled} is true. {@code historyCount} and {@code maxAgeDays} of 0 mean
 * that rule is off.
 */
public record LocalPasswordPolicySettings(boolean enabled, int minLength, boolean requireUpper, boolean requireLower,
        boolean requireDigit, boolean requireSymbol, int historyCount, int maxAgeDays) {

    public static final int FLOOR_MIN_LENGTH = 12;
    public static final int MAX_MIN_LENGTH = 128;
    public static final int MAX_HISTORY_COUNT = 24;
    public static final int MAX_AGE_DAYS_LIMIT = 3650;

    public static final LocalPasswordPolicySettings DEFAULTS =
            new LocalPasswordPolicySettings(false, FLOOR_MIN_LENGTH, false, false, false, false, 0, 0);
}
