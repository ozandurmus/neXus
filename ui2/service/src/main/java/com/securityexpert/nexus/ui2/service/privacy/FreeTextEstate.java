package com.securityexpert.nexus.ui2.service.privacy;

import java.util.Objects;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.sanitizer.core.Counts;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateDictionary;

/**
 * The serials and administrator names that {@link TopologyNamePseudonymizer#maskText} removes from free text
 * (masking audit 2026-11-15). Hostnames reach the text dictionary through the device directory; serials and user names
 * have no key of their own in a free-text field, so without this a serial or a user name inside a job reason, an
 * approval reason or a message went to aiview raw. The forms are the ones the key-based rules and the change feed
 * already give ({@code SN-} and {@code USR-} tokens), from the same keyed dictionary the system-log view uses.
 *
 * <p>Failure behaviour matches the device-directory read: the source keeps the previous dictionary when a refresh
 * fails, and when none was ever built the text is returned as it was and the next attempt waits {@link #RETRY_NANOS}
 * (no read storm against a failing store).</p>
 */
public final class FreeTextEstate {
    private static final System.Logger LOG = System.getLogger(FreeTextEstate.class.getName());
    static final long RETRY_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

    private final Supplier<EstateDictionary> dictionary;
    private volatile long retryAfterNanos;
    private volatile boolean waiting;

    public FreeTextEstate(Supplier<EstateDictionary> dictionary) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
    }

    /** {@code text} with every known serial and administrator name replaced; unchanged when none is known. */
    public String mask(String text) {
        if (text == null || text.isBlank()) return text;
        if (waiting && System.nanoTime() - retryAfterNanos < 0) return text;
        EstateDictionary current;
        try {
            current = dictionary.get();
            waiting = false;
        } catch (RuntimeException unavailable) {
            LOG.log(System.Logger.Level.WARNING, "free-text estate dictionary unavailable cause={0}",
                    unavailable.getClass().getSimpleName());
            retryAfterNanos = System.nanoTime() + RETRY_NANOS;
            waiting = true;
            return text;
        }
        return current.replacer().replace(text, new Counts());
    }
}
