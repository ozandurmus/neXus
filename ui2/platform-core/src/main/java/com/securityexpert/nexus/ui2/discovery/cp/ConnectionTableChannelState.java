package com.securityexpert.nexus.ui2.discovery.cp;

/**
 * CS-1: the four states the management server's own connection table can
 * report for one address. Deliberately named for what each state observes,
 * never for a liveness meaning (CS-2, LV-1 to LV-3) -- Check13LivenessVocabularyTest
 * enforces that these constant names never drift into up/down vocabulary.
 */
public enum ConnectionTableChannelState {

    /** CS-1: the management server's monitoring channel to this address is currently established. */
    ESTABLISHED,

    /** CS-1/CS-3: two agreeing observations found an attempt to establish the channel that has not completed. */
    FAILING_TO_COMPLETE,

    /** CS-1/CS-3: two observations of this address disagreed; never resolved by recency or preference. */
    IN_TRANSITION,

    /** CS-1: the connection table carries no entry at all for this address. */
    ABSENT
}
