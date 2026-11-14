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
 * <p>Failure behaviour: the source keeps the previous dictionary when a refresh fails after a successful load. When no
 * dictionary was ever built the answer is {@link #UNAVAILABLE} (fail closed: a cold pod with a failing store is the
 * leak window), and the next attempt waits {@link #RETRY_NANOS} (no read storm against a failing store). Only masked
 * (aiview) paths call this; raw operator views never reach {@code maskText}.</p>
 */
public final class FreeTextEstate {
    private static final System.Logger LOG = System.getLogger(FreeTextEstate.class.getName());
    /** What a free-text field shows while the dictionary has never loaded. */
    public static final String UNAVAILABLE = "<masked: estate dictionary unavailable>";
    static final long RETRY_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

    private final Supplier<EstateDictionary> dictionary;
    private volatile long retryAfterNanos;
    private volatile boolean waiting;
    private volatile boolean everLoaded;

    public FreeTextEstate(Supplier<EstateDictionary> dictionary) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
    }

    /** {@code text} with every known serial and administrator name replaced; unchanged when none is known. */
    public String mask(String text) {
        if (text == null || text.isBlank()) return text;
        if (waiting && System.nanoTime() - retryAfterNanos < 0) return everLoaded ? text : UNAVAILABLE;
        EstateDictionary current;
        try {
            current = dictionary.get();
            waiting = false;
            everLoaded = true;
        } catch (RuntimeException unavailable) {
            LOG.log(System.Logger.Level.WARNING, "free-text estate dictionary unavailable cause={0}",
                    unavailable.getClass().getSimpleName());
            retryAfterNanos = System.nanoTime() + RETRY_NANOS;
            waiting = true;
            return everLoaded ? text : UNAVAILABLE;
        }
        return current.replacer().replace(text, new Counts());
    }
}
