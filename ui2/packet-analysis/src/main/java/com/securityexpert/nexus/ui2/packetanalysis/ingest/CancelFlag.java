package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import java.util.concurrent.atomic.AtomicBoolean;

/** A settable {@link Cancellation} (the reference {@code threading.Event}). */
public final class CancelFlag implements Cancellation {

    private final AtomicBoolean set = new AtomicBoolean();

    public void set() {
        set.set(true);
    }

    @Override
    public boolean isSet() {
        return set.get();
    }
}
