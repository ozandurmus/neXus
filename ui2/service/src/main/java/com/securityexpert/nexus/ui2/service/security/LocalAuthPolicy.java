package com.securityexpert.nexus.ui2.service.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.identity.LocalAuthModeRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalBreakGlassEventRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RootIdentityRepository;

/**
 * Local-account scope (migration plan Step 3, PO decision D4 2026-11-15). In {@code ALL_LOCAL} (the default) nothing
 * here changes a login. In {@code BREAK_GLASS_ONLY} an interactive local password sign-in is allowed only for the root
 * identity (nexusadmin) and {@code aiview}; {@code aiview-e2e} is a machine-session identity and every other local account is
 * refused. {@link LocalMechanism} asks this only after the Argon2 verification, so a refusal costs the same time as a
 * wrong password, and never counts toward the 5-strike lockout (the password was right).
 *
 * <p>Break-glass use leaves an audited event ({@code local_break_glass_login_succeeded}); a sign-in by the root identity also
 * raises the alert (syslog ERROR line and the {@code login_security} mail digest). A correct password refused by the mode is
 * audited as {@code local_non_break_glass_refused}. Events are written only in {@code BREAK_GLASS_ONLY}: in
 * {@code ALL_LOCAL} the root identity is an everyday administrator and its sign-in is already audited as before.</p>
 *
 * <p>The mode is read through a short cache. An unreadable setting keeps the last known mode (the default on a cold start)
 * so a database hiccup never locks anyone out.</p>
 */
public final class LocalAuthPolicy {

    public static final String VERIFICATION_ACCOUNT = "aiview";
    public static final String MACHINE_ONLY_ACCOUNT = "aiview-e2e";
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);
    private static final System.Logger LOG = System.getLogger(LocalAuthPolicy.class.getName());

    private static final java.util.concurrent.ExecutorService REFUSAL_WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "local-auth-refusal-audit");
                t.setDaemon(true);
                return t;
            });

    public enum Kind { ROOT, VERIFICATION, MACHINE_ONLY, OTHER }

    private final LocalAuthModeRepository modes;
    private final RootIdentityRepository root;
    private final LocalBreakGlassEventRepository events;
    private volatile String cachedMode = LocalAuthModeRepository.ALL_LOCAL;
    private volatile Instant cachedAt = Instant.MIN;

    public LocalAuthPolicy(LocalAuthModeRepository modes, RootIdentityRepository root, LocalBreakGlassEventRepository events) {
        this.modes = Objects.requireNonNull(modes, "modes");
        this.root = Objects.requireNonNull(root, "root");
        this.events = Objects.requireNonNull(events, "events");
    }

    public boolean breakGlassOnly(Instant now) {
        if (cachedAt.plus(CACHE_TTL).isBefore(now)) {
            try {
                cachedMode = modes.read().mode();
            } catch (RuntimeException unreadable) {
                LOG.log(System.Logger.Level.WARNING, "[LOCAL_AUTH_MODE_UNREADABLE] keeping mode {0}", cachedMode);
            }
            cachedAt = now;
        }
        return LocalAuthModeRepository.BREAK_GLASS_ONLY.equals(cachedMode);
    }

    /** Called after a write so the next login sees the new mode at once. */
    public void invalidate() {
        cachedAt = Instant.MIN;
    }

    public Kind kind(LocalCredentialRecord record) {
        Optional<String> rootId;
        try {
            rootId = root.rootLocalIdentityId();
        } catch (RuntimeException unreadable) {
            rootId = Optional.empty();
        }
        if (rootId.isPresent() && rootId.get().equals(record.localIdentityId())) {
            return Kind.ROOT;
        }
        if (VERIFICATION_ACCOUNT.equals(record.localIdentityName())) {
            return Kind.VERIFICATION;
        }
        if (MACHINE_ONLY_ACCOUNT.equals(record.localIdentityName())) {
            return Kind.MACHINE_ONLY;
        }
        return Kind.OTHER;
    }

    /** True when the verified identity must be refused an interactive local sign-in; records the refusal. */
    public boolean refuses(LocalCredentialRecord record, String actorFingerprint, Instant now) {
        if (!breakGlassOnly(now)) {
            return false;
        }
        Kind kind = kind(record);
        if (kind == Kind.ROOT || kind == Kind.VERIFICATION) {
            return false;
        }
        // Off the request thread: the extra insert must not make a right-password refusal measurably slower than a wrong one.
        REFUSAL_WRITER.execute(() -> safely(() -> events.recordRefused(actorFingerprint)));
        return true;
    }

    /** Called after an interactive local sign-in succeeded. */
    public void loggedIn(LocalCredentialRecord record, String actorFingerprint, Instant now) {
        if (!breakGlassOnly(now)) {
            return;
        }
        Kind kind = kind(record);
        if (kind == Kind.ROOT || kind == Kind.VERIFICATION) {
            safely(() -> events.recordLoginSucceeded(actorFingerprint, kind.name(), kind == Kind.ROOT));
        }
    }

    /** The sign-in already committed its own audited row; a failing extra event must not turn it into an error. */
    private static void safely(Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "[BREAK_GLASS_EVENT_FAILED] {0}", e.getClass().getSimpleName());
        }
    }
}
