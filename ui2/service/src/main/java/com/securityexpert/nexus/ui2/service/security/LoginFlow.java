package com.securityexpert.nexus.ui2.service.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    /** In-memory conflict-token registry: opaque, short-lived, never persisted (C3 §3.4). */
    private final Map<String, PendingConflict> pendingConflicts = new ConcurrentHashMap<>();

    public LoginFlow(SessionRepository sessionRepository, Duration idleTimeout, Duration absoluteLifetime) {
        this.sessionRepository = sessionRepository;
        this.idleTimeout = idleTimeout;
        this.absoluteLifetime = absoluteLifetime;
    }

    private record PendingConflict(String actorFingerprint, String priorSessionId, Instant expiresAt) {
    }

    public sealed interface LoginResult {
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
    public LoginResult login(String actorFingerprint, Instant now) {
        Optional<SessionRecord> existing = sessionRepository.findActiveByActor(actorFingerprint);
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
                new PendingConflict(actorFingerprint, prior.sessionId(), now.plus(CONFLICT_TOKEN_BOUND)));
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
        if (pending == null || now.isAfter(pending.expiresAt())) {
            // Expired or unrecognized token: refuse-by-inaction is the
            // default (C3 §3.4) -- the prior session is left untouched
            // either way, since only "takeover" ever mutates it.
            return new ResolveResult.Refused("conflict_token_expired_or_unknown");
        }
        if (!"takeover".equals(action)) {
            // "refuse": S1 is left completely untouched, no S2 created.
            return new ResolveResult.Refused("login_refused_active_session");
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
