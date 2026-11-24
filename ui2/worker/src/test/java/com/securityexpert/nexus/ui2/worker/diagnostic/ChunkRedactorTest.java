package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.systemlog.SourceFamily;
import com.securityexpert.nexus.ui2.worker.systemlog.redaction.LogRedactor;

/**
 * Pins {@link ChunkRedactor}: the {@code of(LogRedactor)} adapter and the fail-closed {@code NOT_WIRED} default.
 * Secret-shaped values are assembled at runtime so no literal credential appears in the source.
 */
class ChunkRedactorTest {

    private static final String R = LogRedactor.REDACTED;
    private static final String VALUE = "syn" + "thetic" + "-" + "pass" + "-9";

    private final ChunkRedactor redactor = ChunkRedactor.of(new LogRedactor());

    private ChunkRedactor.Redaction run(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return redactor.redact(SourceFamily.CP_MESSAGES, data, 0, data.length);
    }

    private String text(ChunkRedactor.Redaction r) {
        return new String(r.bytes(), StandardCharsets.UTF_8);
    }

    @Test
    void aCleanLinePassesUnchangedAndUncounted() {
        String line = "Oct 17 10:00:00 2026 gw1 kernel: interface eth1 link up 192.0.2.10";
        ChunkRedactor.Redaction r = run(line);
        assertEquals(line, text(r));
        assertEquals(0, r.redactionCount());
        assertEquals(Map.of(), r.countsByRule());
        assertEquals(LogRedactor.RULE_SET_ID, r.ruleSetId());
    }

    @Test
    void aSecretShapedValueIsReplacedAndTheRestOfTheLineStays() {
        ChunkRedactor.Redaction r = run("sync job auth_key=" + VALUE + " ok");
        assertEquals("sync job auth_key=" + R + " ok", text(r));
        assertEquals(1, r.redactionCount());
        assertEquals(1, r.countsByRule().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void emptyInputIsReturnedEmptyAndUncounted() {
        ChunkRedactor.Redaction r = run("");
        assertArrayEquals(new byte[0], r.bytes());
        assertEquals(0, r.redactionCount());
    }

    @Test
    void nullInputIsRejectedWithoutTouchingTheData() {
        assertThrows(NullPointerException.class, () -> redactor.redact(SourceFamily.CP_MESSAGES, null, 0, 0));
        assertThrows(NullPointerException.class, () -> ChunkRedactor.of(null));
        assertThrows(NullPointerException.class, () -> redactor.redact(null, new byte[0], 0, 0));
    }

    @Test
    void aLineWhereTheRedactedMarkerIsAlreadyPresentIsNotReplacedAgain() {
        String line = "sync job auth_key=" + R + " ok";
        ChunkRedactor.Redaction r = run(line);
        assertEquals(line, text(r));
        assertEquals(0, r.redactionCount());
    }

    @Test
    void notWiredFailsClosedAndNeverReturnsBytes() {
        byte[] data = "anything".getBytes(StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ChunkRedactor.NOT_WIRED.redact(SourceFamily.CP_MESSAGES, data, 0, data.length));
        assertEquals("REDACTOR_MISSING", e.getMessage());
    }
}
