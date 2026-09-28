package com.securityexpert.nexus.ui2.platform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DiagnosticTextTest {
    @Test void secretsNeverReachEitherProjectionAndUnknownTokensStayMasked() {
        String raw="Hostname: synthetic-private-name\nStatus: UP\ninet addr:192.0.2.10\npassword: synthetic-secret\n-----BEGIN PRIVATE KEY-----\nsynthetic-key\n-----END PRIVATE KEY-----";
        String admin=DiagnosticText.scrubSecrets(raw);
        assertTrue(admin.contains("synthetic-private-name"));
        assertFalse(admin.contains("synthetic-secret"));
        assertFalse(admin.contains("synthetic-key"));
        String ai=DiagnosticText.masked(raw, token -> "[MASKED]");
        assertTrue(ai.contains("Status: UP"));
        assertEquals(admin.lines().count(),ai.lines().count());
        assertFalse(ai.contains("192.0.2.10"));
        assertFalse(ai.contains("synthetic"));
    }
    @Test void secretBearingLinesAndEncBlobsAreRemovedBeforeStorage() {
        String safe=DiagnosticText.scrubSecrets("Status: UP\nPSK: synthetic-value\ncommunity synthetic-value\n"
            + "api_key=synthetic-value\nkey synthetic-value\nset value ENC(synthetic-value)");
        assertTrue(safe.contains("Status: UP"));
        assertFalse(safe.contains("synthetic-value"));
        assertEquals(5,safe.lines().filter(line -> line.equals("[SECRET REDACTED]")).count());
        assertEquals(262_144,DiagnosticText.MAX_BYTES);
    }
}
