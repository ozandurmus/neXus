package com.securityexpert.nexus.ui2.worker;

import java.util.function.BooleanSupplier;

/** Cooperative cancellation at step boundaries; an in-flight call is allowed to finish. */
public final class JobCancellationScope implements AutoCloseable {
    private static final ThreadLocal<BooleanSupplier> ACTIVE = new ThreadLocal<>();
    private final BooleanSupplier previous;
    public JobCancellationScope(BooleanSupplier requested) {
        previous = ACTIVE.get();
        ACTIVE.set(requested);
    }
    public static BooleanSupplier requested() {
        var requested = ACTIVE.get();
        return requested == null ? () -> false : requested;
    }
    public static void check() {
        var requested = ACTIVE.get();
        if (requested != null && requested.getAsBoolean()) throw new Cancelled();
    }
    public static final class Cancelled extends IllegalStateException {
        public Cancelled() { super("CANCELLED"); }
    }
    @Override public void close() {
        if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
    }
}
