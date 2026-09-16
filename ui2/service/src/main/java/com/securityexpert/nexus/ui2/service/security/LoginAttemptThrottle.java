package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** C3 source/identity admission before LDAP. Exact opaque submitted identity, no normalization. */
public final class LoginAttemptThrottle {
    private record Window(Instant expires, int used) { }
    private final Map<String, Window> sources = new HashMap<>();
    private final Map<String, Window> identities = new HashMap<>();

    public synchronized boolean admit(String source, String identity, Instant now) {
        sources.values().removeIf(window -> !now.isBefore(window.expires()));
        identities.values().removeIf(window -> !now.isBefore(window.expires()));
        String sourceKey = key(source);
        String identityKey = key(identity);
        Window s = sources.getOrDefault(sourceKey, new Window(now.plusSeconds(60), 0));
        Window i = identities.getOrDefault(identityKey, new Window(now.plusSeconds(300), 0));
        if (s.used() >= 10 || i.used() >= 5 || sources.size() >= 10000 || identities.size() >= 10000) return false;
        sources.put(sourceKey, new Window(s.expires(), s.used() + 1));
        // Reserve before the call, so simultaneous binds cannot exceed the failed-attempt ceiling.
        identities.put(identityKey, new Window(i.expires(), i.used() + 1));
        return true;
    }

    public synchronized void succeeded(String identity) {
        String key = key(identity);
        Window window = identities.get(key);
        if (window != null) identities.put(key, new Window(window.expires(), Math.max(0, window.used() - 1)));
    }

    private static String key(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("login_throttle_unavailable"); }
    }
}
