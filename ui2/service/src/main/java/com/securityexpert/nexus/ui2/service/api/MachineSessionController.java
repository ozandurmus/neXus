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
    private static final String NAME = "aiview-e2e";
    private static final Set<String> ROLES = Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER);

    private final byte[] expectedDigest;
    private final LocalCredentialsRepository identities;
    private final LocalRoleTokenResolver roles;
    private final SessionRepository sessions;
    private final LoginFlow loginFlow;
    private final SessionCookieWriter cookieWriter;

    public MachineSessionController(@Value("${UI2_E2E_MACHINE_TOKEN_SHA256:}") String digest,
            LocalCredentialsRepository identities, LocalRoleTokenResolver roles, SessionRepository sessions,
            LoginFlow loginFlow, SessionCookieWriter cookieWriter) {
        byte[] parsed;
        try { parsed = digest.matches("[0-9a-f]{64}") ? HexFormat.of().parseHex(digest) : null; }
        catch (IllegalArgumentException ignored) { parsed = null; }
        this.expectedDigest = parsed;
        this.identities = identities;
        this.roles = roles;
        this.sessions = sessions;
        this.loginFlow = loginFlow;
        this.cookieWriter = cookieWriter;
    }

    @PostMapping("/internal/machine-session")
    public ResponseEntity<Map<String, String>> login(
            @RequestHeader(value = "X-Nexus-Machine-Token", required = false) String token,
            HttpServletResponse response) {
        if (expectedDigest == null) return ResponseEntity.notFound().build();
        byte[] actual = token == null ? new byte[32] : sha256(token);
        if (!MessageDigest.isEqual(expectedDigest, actual)) return refused("machine_session_token_refused");
        var identity = identities.findByName(NAME);
        if (identity.isEmpty() || !identity.get().enabled()
                || !Set.copyOf(roles.resolve(identity.get().localIdentityId())).equals(ROLES)) {
            return refused("machine_session_roles_refused");
        }
        var session = loginFlow.machineLogin(LocalMechanism.actorFingerprintFor(identity.get().localIdentityId()),
                Instant.now());
        cookieWriter.write(response, session.rawCookieValue());
        return ResponseEntity.ok(Map.of("csrf_token", session.session().csrfSecret()));
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
