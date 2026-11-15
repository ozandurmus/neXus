package com.securityexpert.nexus.ui2.service.security;

import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.identity.LocalPasswordPolicySettings;

/**
 * The minimum a new local password must satisfy to be storable (C3A
 * contract §4, PW-1/PW-2/PW-3). With the configurable policy off (the
 * default) that is all there is. With it on ({@link LocalPasswordPolicySettings#enabled()})
 * the administrator's minimum length and character-class rules are added on
 * top; reuse and maximum age are checked by their owners (the change service
 * and the session gate), not here.
 */
final class LocalPasswordPolicy {

    /** PW-1. */
    static final int MIN_LENGTH = 12;

    private LocalPasswordPolicy() {
    }

    /**
     * @return the closed-vocabulary violation code, or empty if storable.
     *          Every check here runs before any hash is computed (PW-2:
     *          "an empty password must never reach the hashing function at
     *          all") -- the caller must not hash first and validate after.
     */
    static Optional<String> violation(char[] newPassword, String localIdentityName) {
        return violation(newPassword, localIdentityName, LocalPasswordPolicySettings.DEFAULTS);
    }

    /** As above, plus the configured rules when {@code policy.enabled()}; identical to the two-argument form when off. */
    static Optional<String> violation(char[] newPassword, String localIdentityName, LocalPasswordPolicySettings policy) {
        Optional<String> base = baseViolation(newPassword, localIdentityName);
        if (base.isPresent() || !policy.enabled()) {
            return base;
        }
        if (newPassword.length < policy.minLength()) {
            return Optional.of("password_too_short");
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean symbol = false;
        for (char c : newPassword) {
            if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else if (!Character.isWhitespace(c)) {
                symbol = true;
            }
        }
        if (policy.requireUpper() && !upper) {
            return Optional.of("password_missing_uppercase");
        }
        if (policy.requireLower() && !lower) {
            return Optional.of("password_missing_lowercase");
        }
        if (policy.requireDigit() && !digit) {
            return Optional.of("password_missing_digit");
        }
        if (policy.requireSymbol() && !symbol) {
            return Optional.of("password_missing_symbol");
        }
        return Optional.empty();
    }

    /** A sentence for a violation code; never contains any part of the password. */
    static String describe(String code, LocalPasswordPolicySettings policy) {
        int min = policy.enabled() ? policy.minLength() : MIN_LENGTH;
        return switch (code) {
            case "password_too_short" -> "The password must be at least " + min + " characters long.";
            case "password_equals_identity_name" -> "The password must not be the same as the account name.";
            case "password_missing_uppercase" -> "The password must contain an uppercase letter.";
            case "password_missing_lowercase" -> "The password must contain a lowercase letter.";
            case "password_missing_digit" -> "The password must contain a digit.";
            case "password_missing_symbol" -> "The password must contain a symbol (a character that is not a letter, digit or space).";
            case "password_reused" -> "The password was used recently; choose one that is not among the last "
                    + policy.historyCount() + " passwords.";
            default -> "The password does not meet the password policy.";
        };
    }

    private static Optional<String> baseViolation(char[] newPassword, String localIdentityName) {
        if (newPassword.length < MIN_LENGTH) {
            // PW-2's empty case is length 0, already covered by PW-1's floor.
            return Optional.of("password_too_short");
        }
        if (equalsIgnoreCase(newPassword, localIdentityName)) {
            return Optional.of("password_equals_identity_name"); // PW-3
        }
        return Optional.empty();
    }

    /** Case-insensitive comparison without ever allocating a {@code String} copy of the password. */
    private static boolean equalsIgnoreCase(char[] password, String name) {
        if (password.length != name.length()) {
            return false;
        }
        for (int i = 0; i < password.length; i++) {
            if (Character.toLowerCase(password[i]) != Character.toLowerCase(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
