package com.securityexpert.nexus.ui2.service.boot;

/**
 * BOOT-3/BOOT-3a's single named-constant home (C3B contract §2, §4 test 3):
 * the two bootstrap identity names and their documented initial passwords.
 * A shipped default administrator credential is public by construction
 * (BOOT-3), so the passwords are permitted here as constants -- but nowhere
 * else. No other file may re-spell either password literal.
 */
public final class BootstrapCredentialDefaults {

    /** ROLE-1/BOOT-1: the default administrator identity's name. */
    public static final String NEXUSADMIN_NAME = "nexusadmin";

    /** ROLE-3/BOOT-1: the read-only automation identity's name. */
    public static final String CLAUDEADMIN_NAME = "claudeadmin";

    private static final char[] NEXUSADMIN_INITIAL_PASSWORD = "nexusadmin".toCharArray();
    private static final char[] CLAUDEADMIN_INITIAL_PASSWORD = "claudeadmin".toCharArray();

    private BootstrapCredentialDefaults() {
    }

    /** BOOT-3a. A fresh copy per call: the caller consumes (zeroes) its own array. */
    public static char[] nexusadminInitialPassword() {
        return NEXUSADMIN_INITIAL_PASSWORD.clone();
    }

    /** BOOT-3a. A fresh copy per call: the caller consumes (zeroes) its own array. */
    public static char[] claudeadminInitialPassword() {
        return CLAUDEADMIN_INITIAL_PASSWORD.clone();
    }
}
