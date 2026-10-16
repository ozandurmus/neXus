package com.securityexpert.nexus.ui2.packetanalysis.ingest;

/** A cancellation flag polled by the ingest (the reference {@code threading.Event.is_set}). */
@FunctionalInterface
public interface Cancellation {

    /** Never cancelled. */
    Cancellation NONE = () -> false;

    boolean isSet();

    /** Set when either flag is set (the reference batch {@code Cancellation(global, file)}). */
    static Cancellation either(Cancellation a, Cancellation b) {
        return () -> a.isSet() || b.isSet();
    }
}
