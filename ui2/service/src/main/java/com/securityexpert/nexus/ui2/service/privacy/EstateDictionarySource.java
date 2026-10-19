package com.securityexpert.nexus.ui2.service.privacy;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.sanitizer.core.EstateDictionary;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateIdentity;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.SanitizerKey;

/**
 * The estate dictionary the masked system-log view and the Data Sanitizer share: built from the identity values the
 * service knows ({@code SystemLogReadConfiguration#estateIdentities}), read at most once per TTL, and kept when a
 * refresh fails. Fail closed: when it cannot be built and no earlier one is cached, {@link #get()} throws
 * {@link UnavailableException} and the caller refuses its masked output rather than serve it with one pass missing.
 * Only keyed digests are kept ({@link EstateDictionary}); nothing about the values is logged. Thread-safe.
 */
public final class EstateDictionarySource implements Supplier<EstateDictionary> {

    /** The dictionary could not be built and none is cached. */
    public static final class UnavailableException extends IllegalStateException {
        UnavailableException(Throwable cause) {
            super("the estate identity dictionary is unavailable (" + cause.getClass().getSimpleName() + ")", null);
        }
    }

    private static final System.Logger LOG = System.getLogger(EstateDictionarySource.class.getName());

    private final Mappers base;
    private final Supplier<? extends List<EstateIdentity>> estate;
    private final long ttlNanos;
    private volatile EstateDictionary dictionary;
    private volatile long expiresAtNanos;

    /**
     * @param privacyKey the product's privacy HMAC key ({@code HmacKeyProvider#getSecretKey()})
     * @param estate the identity values (serials, addresses, names); only their keyed digests are kept
     */
    public EstateDictionarySource(byte[] privacyKey, Supplier<? extends List<EstateIdentity>> estate, Duration ttl) {
        this.base = new Mappers(SanitizerKey.of(Objects.requireNonNull(privacyKey, "privacyKey")), KnownNames.NONE);
        this.estate = estate == null ? List::of : estate;
        this.ttlNanos = Objects.requireNonNull(ttl, "ttl").toNanos();
    }

    /** No estate (tests, and wiring without device tables): always the empty dictionary. */
    public static EstateDictionarySource none(byte[] privacyKey) {
        return new EstateDictionarySource(privacyKey, List::of, Duration.ofDays(1));
    }

    /** @throws UnavailableException when the dictionary cannot be built and none is cached */
    @Override
    public EstateDictionary get() {
        EstateDictionary current = dictionary;
        long now = System.nanoTime();
        if (current != null && now - expiresAtNanos < 0) return current;
        synchronized (this) {
            current = dictionary;
            if (current != null && now - expiresAtNanos < 0) return current;
            try {
                EstateDictionary fresh = EstateDictionary.build(estate.get(), base);
                dictionary = fresh;
                expiresAtNanos = System.nanoTime() + ttlNanos;
                return fresh;
            } catch (RuntimeException e) {
                if (current != null) {
                    // Keep serving with the previous dictionary; the next request tries again.
                    LOG.log(System.Logger.Level.WARNING, "estate dictionary refresh failed, previous one kept cause={0}",
                            e.getClass().getSimpleName());
                    return current;
                }
                LOG.log(System.Logger.Level.ERROR, "estate dictionary unavailable cause={0}", e.getClass().getSimpleName());
                throw new UnavailableException(e);
            }
        }
    }

    @Override
    public String toString() {
        return "EstateDictionarySource[" + (dictionary == null ? "unbuilt" : dictionary.size()) + "]";
    }
}
