package com.securityexpert.nexus.ui2.platform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DiagnosticTextTest {
    @Test void maskedPreservesSecretRedactionPlaceholdersWithoutTokenizingThem() {
        String value=String.join("-","synthetic","value");
        String raw="Status: UP\nWarning! password "+value+"\ntoken: "+value+"\n"
                +String.join(" ", "-----BEGIN", "PRIVATE", "KEY-----")+"\n"+value+"\n"+String.join(" ", "-----END", "PRIVATE", "KEY-----")+"\ninvented-host 192.0.2.10";
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
                +"\n"+String.join(" ", "-----BEGIN", "PRIVATE", "KEY-----")+"\n"+key+"\n"+String.join(" ", "-----END", "PRIVATE", "KEY-----");
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

    @Test void sizesWithUnitSuffixesMonthAndWeekdayAbbreviationsAndDictionaryWordsStayReadable() {
        String sizes = "289G 4.0K 512M 100B 0B 1.5T 289GiB 64KB 512MB 100% 47.5% 100Mbps 250kpps 12ms";
        assertEquals(sizes, DiagnosticText.masked(sizes, token -> "SN-OVERMASK"));

        String months = "Jan Feb Mar Apr May Jun Jul Aug Sep Sept Oct Nov Dec "
                + "January February March April June July August September October November December";
        assertEquals(months, DiagnosticText.masked(months, token -> "SN-OVERMASK"));

        String weekdays = "Mon Tue Tues Wed Thu Thur Thurs Fri Sat Sun "
                + "Monday Tuesday Wednesday Thursday Friday Saturday Sunday";
        assertEquals(weekdays, DiagnosticText.masked(weekdays, token -> "SN-OVERMASK"));

        String logListing = "289G\t/var/log/opt/CPsuite-R81.20/fw1/log/\n"
                + "total 289G\n"
                + "drwxrwx--- 4 admin root 4.0K Oct 31 23:59 .\n"
                + "-rw-rw---- 1 admin root 512M Oct 31 23:59 fw.log\n"
                + "-rw-rw---- 1 admin root 64KB Jan 15 08:30 fw.logptr\n"
                + "-rw-rw---- 1 admin root 12M Mon Feb 02 10:15:01 2026 2026-02-02_000000.log\n"
                + "Overall Status:                 0\n"
                + "Overall Status Description:     Security Gateway is reporting logs as defined\n"
                + "Local Logging Mode Status:      0\n"
                + "Local Logging Mode Description: Logs are written locally\n"
                + "Local Logging Sending Rate:     0\n"
                + "Local Logging Writing Rate:     42\n"
                + "<dir>/var/log/opt/CPsuite-R81.20/fw1/log</dir>\n"
                + "<file>fw1logs.cpbak</file>";
        assertEquals(logListing, DiagnosticText.masked(logListing, token -> "SN-OVERMASK"));
    }

    @Test void runtimeBuiltSyntheticSerialsIpsHostnamesAndUsernamesStayMasked() {
        var rng = new java.util.Random(424242L);
        String alphaSerial = buildAlphaNumericToken(rng, "SNX", 8);
        String vendorSerial = buildAlphaNumericToken(rng, "CZJ", 7);
        String numericSerial = buildNumericToken(rng, 10);
        String unknownHost = "edge-" + buildAlphaNumericToken(rng, "gw", 5).toLowerCase(java.util.Locale.ROOT);
        String fqdnHost = unknownHost + ".corp.local";
        String operatorUser = "op_" + buildAlphaNumericToken(rng, "usr", 4).toLowerCase(java.util.Locale.ROOT);
        String ipv4 = "192.0.2." + (10 + rng.nextInt(200));
        String ipv6 = "2001:db8::" + Integer.toHexString(100 + rng.nextInt(800));

        String raw = "289G Jan Mon ordinary readable dictionary words "
                + alphaSerial + " " + vendorSerial + " " + numericSerial + " "
                + unknownHost + " " + fqdnHost + " "
                + ipv4 + " " + ipv6 + " "
                + "user=" + operatorUser + " user=admin for invalid user root hostname=active serial=12345";
        String masked = DiagnosticText.masked(raw,
                java.util.Map.of("known-fw-01", "FW-TANGO-04"),
                token -> "SN-MASKED");

        assertTrue(masked.startsWith("289G Jan Mon ordinary readable dictionary words "),
                "Expected sizes, calendar abbreviations, and dictionary words to stay readable: " + masked);
        assertFalse(masked.contains(alphaSerial), "Alpha-numeric serial must be masked");
        assertFalse(masked.contains(vendorSerial), "Vendor-style serial must be masked");
        assertFalse(masked.contains(numericSerial), "Long numeric serial must be masked");
        assertFalse(masked.contains(unknownHost), "Unknown hostname must be masked");
        assertFalse(masked.contains(fqdnHost), "FQDN hostname must be masked");
        assertFalse(masked.contains(ipv4), "IPv4 address must be masked");
        assertFalse(masked.contains(ipv6), "IPv6 address must be masked");
        assertFalse(masked.contains(operatorUser), "Operator username must be masked");
        assertTrue(masked.contains("user=SN-MASKED user=SN-MASKED for invalid user SN-MASKED hostname=SN-MASKED serial=SN-MASKED"),
                "Inline user/host/serial values must be masked even when dictionary words or short numbers: " + masked);
    }

    private static String buildAlphaNumericToken(java.util.Random rng, String prefix, int length) {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String buildNumericToken(java.util.Random rng, int digits) {
        StringBuilder sb = new StringBuilder(digits);
        sb.append(1 + rng.nextInt(9));
        for (int i = 1; i < digits; i++) {
            sb.append(rng.nextInt(10));
        }
        return sb.toString();
    }
}
