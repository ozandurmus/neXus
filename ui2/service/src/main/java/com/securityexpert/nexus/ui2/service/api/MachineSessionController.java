package com.securityexpert.nexus.ui2.service.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.LocalMechanism;
import com.securityexpert.nexus.ui2.service.security.LocalRoleTokenResolver;
import com.securityexpert.nexus.ui2.service.security.LoginFlow;

@RestController
public final class MachineSessionController {
    public static final String PURPOSE_E2E = "e2e";
    public static final String PURPOSE_PROBE = "probe";

    private static final String NAME = "aiview-e2e";
    private static final Set<String> ROLES = Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER);

    private final byte[] expectedDigest;
    private final byte[] probeDigest;
    private final LocalCredentialsRepository identities;
    private final LocalRoleTokenResolver roles;
    private final SessionRepository sessions;
    private final LoginFlow loginFlow;
    private final SessionCookieWriter cookieWriter;

    public MachineSessionController(String digest,
            LocalCredentialsRepository identities, LocalRoleTokenResolver roles, SessionRepository sessions,
            LoginFlow loginFlow, SessionCookieWriter cookieWriter) {
        this(digest, "", identities, roles, sessions, loginFlow, cookieWriter);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MachineSessionController(@Value("${UI2_E2E_MACHINE_TOKEN_SHA256:}") String digest,
            @Value("${UI2_PROBE_MACHINE_TOKEN_SHA256:}") String probeDigest,
            LocalCredentialsRepository identities, LocalRoleTokenResolver roles, SessionRepository sessions,
            LoginFlow loginFlow, SessionCookieWriter cookieWriter) {
        this.expectedDigest = parseDigest(digest);
        this.probeDigest = parseDigest(probeDigest);
        this.identities = identities;
        this.roles = roles;
        this.sessions = sessions;
        this.loginFlow = loginFlow;
        this.cookieWriter = cookieWriter;
    }

    public ResponseEntity<Map<String, String>> login(String token, HttpServletResponse response) {
        return login(token, null, response);
    }

    @PostMapping("/internal/machine-session")
    public ResponseEntity<Map<String, String>> login(
            @RequestHeader(value = "X-Nexus-Machine-Token", required = false) String token,
            @RequestHeader(value = "X-Nexus-Machine-Purpose", required = false) String purpose,
            HttpServletResponse response) {
        if (expectedDigest == null && probeDigest == null) return ResponseEntity.notFound().build();
        byte[] actual = token == null ? new byte[32] : sha256(token);
        boolean matchesE2e = expectedDigest != null && MessageDigest.isEqual(expectedDigest, actual);
        boolean matchesProbe = probeDigest != null && MessageDigest.isEqual(probeDigest, actual);
        String resolvedPurpose = resolvePurpose(matchesE2e, matchesProbe, purpose);
        if (resolvedPurpose == null) return refused("machine_session_token_refused");
        var identity = identities.findByName(NAME);
        if (identity.isEmpty() || !identity.get().enabled()
                || !Set.copyOf(roles.resolve(identity.get().localIdentityId())).equals(ROLES)) {
            return refused("machine_session_roles_refused");
        }
        String actor = PURPOSE_PROBE.equals(resolvedPurpose)
                ? LocalMechanism.probeActorFingerprintFor(identity.get().localIdentityId())
                : LocalMechanism.actorFingerprintFor(identity.get().localIdentityId());
        var session = loginFlow.machineLogin(actor, Instant.now());
        cookieWriter.write(response, session.rawCookieValue());
        return ResponseEntity.ok(Map.of("csrf_token", session.session().csrfSecret()));
    }

    private static String resolvePurpose(boolean matchesE2e, boolean matchesProbe, String purpose) {
        if (matchesE2e && matchesProbe) return null;
        if (matchesProbe) {
            return (purpose == null || PURPOSE_PROBE.equals(purpose)) ? PURPOSE_PROBE : null;
        }
        if (matchesE2e) {
            if (purpose == null || PURPOSE_E2E.equals(purpose)) return PURPOSE_E2E;
            if (PURPOSE_PROBE.equals(purpose)) return PURPOSE_PROBE;
        }
        return null;
    }

    private static byte[] parseDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) return null;
        try { return HexFormat.of().parseHex(digest); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private ResponseEntity<Map<String, String>> refused(String actionId) {
        sessions.auditMachineRefusal(actionId);
        return ResponseEntity.status(403).body(Map.of("error", "MACHINE_SESSION_REFUSED"));
    }

    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
