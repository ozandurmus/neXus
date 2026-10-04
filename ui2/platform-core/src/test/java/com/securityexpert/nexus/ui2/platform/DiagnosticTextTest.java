package com.securityexpert.nexus.ui2.platform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DiagnosticTextTest {
    @Test void maskedPreservesSecretRedactionPlaceholdersWithoutTokenizingThem() {
        String value=String.join("-","synthetic","value");
        String raw="Status: UP\nWarning! password "+value+"\ntoken: "+value+"\n"
                +"-----BEGIN PRIVATE KEY-----\n"+value+"\n-----END PRIVATE KEY-----\ninvented-host 192.0.2.10";
        String expected="Status: UP\n"+"[SECRET REDACTED]\n".repeat(5)+"[MASKED] [MASKED]";
        java.util.function.Function<String,String> maskToken=token -> {
            assertNotEquals("SECRET",token);
            assertNotEquals("REDACTED",token);
            return "[MASKED]";
        };
        assertEquals(expected,DiagnosticText.masked(raw,maskToken));
        assertEquals(expected,DiagnosticText.masked(DiagnosticText.scrubSecrets(raw),
                java.util.Map.of("REDACTED","invented-replacement"),maskToken));
    }
    @Test void secretsNeverReachEitherProjectionAndUnknownTokensStayMasked() {
        String secret=String.join("-","synthetic","secret");
        String key=String.join("-","synthetic","key");
        String raw="Hostname: synthetic-private-name\nStatus: UP\ninet addr:192.0.2.10\npassword: "+secret
                +"\n-----BEGIN PRIVATE KEY-----\n"+key+"\n-----END PRIVATE KEY-----";
        String admin=DiagnosticText.scrubSecrets(raw);
        assertTrue(admin.contains("synthetic-private-name"));
        assertFalse(admin.contains(secret));
        assertFalse(admin.contains(key));
        String ai=DiagnosticText.masked(raw, token -> "[MASKED]");
        assertTrue(ai.contains("Status: UP"));
        assertEquals(admin.lines().count(),ai.lines().count());
        assertFalse(ai.contains("192.0.2.10"));
        assertFalse(ai.contains("synthetic"));
    }
    @Test void secretBearingLinesAndEncBlobsAreRemovedBeforeStorage() {
        String value=String.join("-","synthetic","value");
        String safe=DiagnosticText.scrubSecrets("Status: UP\nPSK: "+value+"\ncommunity "+value+"\n"
            + "api_key="+value+"\nkey "+value+"\nset value ENC("+value+")");
        assertTrue(safe.contains("Status: UP"));
        assertFalse(safe.contains(value));
        assertEquals(5,safe.lines().filter(line -> line.equals("[SECRET REDACTED]")).count());
        assertEquals(262_144,DiagnosticText.MAX_BYTES);
    }
    @Test void vendorWarningAndCliVocabularyStayReadable() {
        String warning="Warning! Please use vsenv to set the virtual system context before running this command.";
        assertEquals(warning, DiagnosticText.masked(warning, token -> "[MASKED]"));
        assertEquals("arp -an; clish -c; bash -lc",
                DiagnosticText.masked("arp -an; clish -c; bash -lc", token -> "[MASKED]"));
        assertEquals("eth1-01 bond0 wrp128 42 99999 [MASKED] [MASKED]",
                DiagnosticText.masked("eth1-01 bond0 wrp128 42 99999 123456 ABC123456", token -> "[MASKED]"));
        assertEquals("packets: 123456789", DiagnosticText.masked("packets: 123456789", token -> "[MASKED]"));
    }
    @Test void identitiesOverrideVocabularyAndInterfaceExceptions() {
        String raw="system 192.0.2.10 02:00:00:00:00:01 invented@example.invalid SYN123456";
        assertEquals("FW-TANGO-04 [MASKED] [MASKED] [MASKED] [MASKED]",
                DiagnosticText.masked(raw, java.util.Map.of("system", "FW-TANGO-04"), token -> "[MASKED]"));
        assertEquals("Hostname: [MASKED]\nSerial Number = [MASKED]\nUser: [MASKED]\nName = [MASKED]",
                DiagnosticText.masked("Hostname: active\nSerial Number = 12345\nUser: system\nName = eth1-01", token -> "[MASKED]"));
        assertEquals("[MASKED] [MASKED] [MASKED] [MASKED]",
                DiagnosticText.masked("2001:db8::1 fe80::1%eth0 0200.0000.0001 02-00-00-00-00-01", token -> "[MASKED]"));
    }
    @Test void arpKeepsStructureButHidesAddressesAndUnknownNames() {
        assertEquals("? ([MASKED]) at [MASKED] [ether] on eth1-01",
                DiagnosticText.masked("? (192.0.2.10) at 02:00:00:00:00:01 [ether] on eth1-01", token -> "[MASKED]"));
        assertEquals("[MASKED] ([MASKED]) at [MASKED] on bond0",
                DiagnosticText.masked("invented-host (192.0.2.20) at 02:00:00:00:00:02 on bond0", token -> "[MASKED]"));
    }
}
