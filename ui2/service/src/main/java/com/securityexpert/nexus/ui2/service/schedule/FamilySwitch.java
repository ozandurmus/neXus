package com.securityexpert.nexus.ui2.service.schedule;

/** What a scheduler asks at the start of every tick: is my family switched on (V203)? */
@FunctionalInterface
public interface FamilySwitch {
    /** Every family on: for unit tests that exercise a scheduler's own logic. */
    FamilySwitch ALWAYS_ON = family -> true;

    boolean isOn(ScheduledJobFamily family);

    /** True when {@code family} may submit work now; otherwise logs the skip under {@code tag}. */
    default boolean allows(ScheduledJobFamily family, String tag) {
        if (isOn(family)) return true;
        java.util.logging.Logger.getLogger(FamilySwitch.class.getName()).info(
                "[" + tag + "] skipped: scheduled family " + family.key() + " is switched OFF (Administration > Platform > Scheduled jobs)");
        return false;
    }
}
