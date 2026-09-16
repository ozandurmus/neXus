package com.securityexpert.nexus.ui2.service.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.persistence.identity.*;

import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;

/**
 * The login / login-resolve flow (C3 §3.4, contract §5): single-active
 * -session with an explicit takeover/refuse choice, refuse-by-inaction as
 * the default. Servlet-independent and side-effect-free beyond the
 * injected {@link SessionRepository} — testable without a database.
 */
public final class LoginFlow {

    public static final String ACTION_SESSION_LOGIN = "session_login";
    public static final String ACTION_SESSION_LOGIN_TAKEOVER = "session_login_takeover";

    private static final Duration CONFLICT_TOKEN_BOUND = Duration.ofMinutes(2);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionRepository sessionRepository;
    private final Duration idleTimeout;
    private final Duration absoluteLifetime;

    private RoleBindingRepository bindings;
    private GroupReferenceCipher cipher;
    private boolean directoryPostureEnabled;

    public LoginFlow(SessionRepository sessions, Duration idle, Duration absolute,
            RoleBindingRepository bindings, GroupReferenceCipher cipher, boolean directoryPostureEnabled) {
        this(sessions, idle, absolute);
        this.bindings = bindings;
        this.cipher = cipher;
        this.directoryPostureEnabled = directoryPostureEnabled;
    }

    /** In-memory conflict-token registry: opaque, short-lived, never persisted (C3 §3.4). */
    private final Map<String, PendingConflict> pendingConflicts = new ConcurrentHashMap<>();

    public LoginFlow(SessionRepository sessionRepository, Duration idleTimeout, Duration absoluteLifetime) {
        this.sessionRepository = sessionRepository;
        this.idleTimeout = idleTimeout;
        this.absoluteLifetime = absoluteLifetime;
    }

    private record PendingDirectory(ActorAuthzStateRecord encryptedProof, DirectoryObservation.Publication publication) { }

    private record PendingConflict(String actorFingerprint, String priorSessionId, Instant expiresAt, PendingDirectory directory) {
    }

    public sealed interface LoginResult {
        record DirectoryUnavailable() implements LoginResult { }

        record NewSession(String rawCookieValue, SessionRecord session) implements LoginResult {
        }

        record Conflict(String conflictToken, SessionRecord priorSession) implements LoginResult {
        }
    }

    /**
     * Called after a successful bind (C3 §3.4's flow starts after "bind
     * succeeds, group set resolved, access group present"). This class
     * never touches LDAP or the password.
     */
    public LoginResult login(AttemptOutcome.Success success, Instant now) {
        if (success.directoryObservation() == null) return login(success.resolvedActorFingerprint(), now);
        if (!directoryPostureEnabled || bindings == null || cipher == null) return new LoginResult.DirectoryUnavailable();
        DirectoryObservation proof = success.directoryObservation();
        if (now.isBefore(proof.resolvedAt()) || !now.isBefore(proof.validUntil())) return new LoginResult.DirectoryUnavailable();
        PendingDirectory directory = new PendingDirectory(new ActorAuthzStateRecord(success.resolvedActorFingerprint(),
                proof.groupReferences(), proof.resolvedAt(), proof.validUntil(), proof.profileId(),
                cipher.encryptDirectory(proof.principalReference(), proof.profileId(), DirectoryBindingKind.DIRECTORY_PRINCIPAL),
                cipher.keyId()), proof.publication());
        AtomicReference<LoginResult> result = new AtomicReference<>(new LoginResult.DirectoryUnavailable());
        long started = System.nanoTime();
        try {
            bindings.directoryMutation(tx -> {
                proof.publication().ifCurrent(() -> {
                    Instant at = now.plusNanos(System.nanoTime() - started);
                    if (!compatible(tx, directory, at)) return;
                    LoginResult login = new LoginFlow(tx.sessions(), idleTimeout, absoluteLifetime)
                            .login(success.resolvedActorFingerprint(), at);
                    if (login instanceof LoginResult.Conflict conflict) {
                        pendingConflicts.put(conflict.conflictToken(), new PendingConflict(success.resolvedActorFingerprint(),
                                conflict.priorSession().sessionId(), at.plus(CONFLICT_TOKEN_BOUND), directory));
                    } else {
                        tx.actors().upsertDirectory(directory.encryptedProof());
                    }
                    result.set(login);
                });
                return null;
            });
        } catch (RuntimeException e) { return new LoginResult.DirectoryUnavailable(); }
        return result.get();
    }

    private boolean compatible(DirectoryMutationRepositories tx, PendingDirectory directory, Instant now) {
        ActorAuthzStateRecord proof = directory.encryptedProof();
        if (!proof.isFresh(now) || new LocalIdentityResolver(tx.locals()).resolve(proof.actorFingerprint()).isPresent()) return false;
        var previous = tx.actors().find(proof.actorFingerprint());
        if (previous.isEmpty()) return tx.sessions().findActiveByActor(proof.actorFingerprint()).isEmpty();
        if (!previous.get().hasDirectoryProof() || !proof.directoryProfileId().equals(previous.get().directoryProfileId())) return false;
        return cipher.decryptDirectory(proof.principalReferenceEncrypted(), proof.directoryProfileId(),
                DirectoryBindingKind.DIRECTORY_PRINCIPAL, proof.principalReferenceKeyId()).equals(
                cipher.decryptDirectory(previous.get().principalReferenceEncrypted(), previous.get().directoryProfileId(),
                DirectoryBindingKind.DIRECTORY_PRINCIPAL, previous.get().principalReferenceKeyId()));
    }

    public LoginResult login(String actorFingerprint, Instant now) {
        Optional<SessionRecord> existing = sessionRepository.findActiveByActor(actorFingerprint, now);
        if (existing.isEmpty()) {
            String rawCookie = randomCookieValue();
            String sessionId = SessionHasher.hash(rawCookie);
            String csrfSecret = randomCookieValue();
            SessionRecord created = sessionRepository.createActive(sessionId, actorFingerprint, csrfSecret, now,
                    idleTimeout, absoluteLifetime, ACTION_SESSION_LOGIN);
            return new LoginResult.NewSession(rawCookie, created);
        }
        SessionRecord prior = existing.get();
        String conflictToken = randomCookieValue();
        pendingConflicts.put(conflictToken,
                new PendingConflict(actorFingerprint, prior.sessionId(), now.plus(CONFLICT_TOKEN_BOUND), null));
        return new LoginResult.Conflict(conflictToken, prior);
    }

    public sealed interface ResolveResult {
        record TakenOver(String rawCookieValue, SessionRecord session) implements ResolveResult {
        }

        /** Explicit refuse, or an expired/unresolved conflict token -- C3 §3.4's "default is refuse-by-inaction". */
        record Refused(String reasonCode) implements ResolveResult {
        }
    }

    public ResolveResult resolve(String conflictToken, String action, Instant now) {
        PendingConflict pending = pendingConflicts.remove(conflictToken);
        if (pending == null || !now.isBefore(pending.expiresAt())) {
            // Expired or unrecognized token: refuse-by-inaction is the
            // default (C3 §3.4) -- the prior session is left untouched
            // either way, since only "takeover" ever mutates it.
            return new ResolveResult.Refused("conflict_token_expired_or_unknown");
        }
        if (!"takeover".equals(action)) {
            // "refuse": S1 is left completely untouched, no S2 created.
            return new ResolveResult.Refused("login_refused_active_session");
        }
        if (pending.directory() != null) {
            AtomicReference<ResolveResult> result = new AtomicReference<>(new ResolveResult.Refused("directory_unavailable"));
            long started = System.nanoTime();
            try {
                bindings.directoryMutation(tx -> {
                    pending.directory().publication().ifCurrent(() -> {
                        Instant at = now.plusNanos(System.nanoTime() - started);
                        if (!at.isBefore(pending.expiresAt()) || !compatible(tx, pending.directory(), at)
                                || tx.sessions().findBySessionId(pending.priorSessionId()).filter(s -> s.isActive(at)
                                    && s.actorFingerprint().equals(pending.actorFingerprint())).isEmpty()) {
                            return;
                        }
                        String cookie = randomCookieValue();
                        SessionRecord session = tx.sessions().takeover(pending.priorSessionId(), SessionHasher.hash(cookie),
                                pending.actorFingerprint(), randomCookieValue(), at, idleTimeout, absoluteLifetime,
                                ACTION_SESSION_LOGIN_TAKEOVER);
                        tx.actors().upsertDirectory(pending.directory().encryptedProof());
                        result.set(new ResolveResult.TakenOver(cookie, session));
                    });
                    return null;
                });
            } catch (RuntimeException e) { return new ResolveResult.Refused("directory_unavailable"); }
            return result.get();
        }
        String rawCookie = randomCookieValue();
        String newSessionId = SessionHasher.hash(rawCookie);
        String csrfSecret = randomCookieValue();
        SessionRecord session = sessionRepository.takeover(pending.priorSessionId(), newSessionId,
                pending.actorFingerprint(), csrfSecret, now, idleTimeout, absoluteLifetime,
                ACTION_SESSION_LOGIN_TAKEOVER);
        return new ResolveResult.TakenOver(rawCookie, session);
    }

    private static String randomCookieValue() {
        byte[] bytes = new byte[32]; // 256-bit, C3 §3.6
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static Map<String, Object> conflictBody(LoginResult.Conflict conflict) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "LOGIN_CONFLICT");
        body.put("conflict_token", conflict.conflictToken());
        Map<String, Object> priorSession = new LinkedHashMap<>();
        priorSession.put("created_at", conflict.priorSession().createdAt().toString());
        priorSession.put("last_seen_at", conflict.priorSession().lastSeenAt().toString());
        body.put("prior_session", priorSession);
        return body;
    }
}
