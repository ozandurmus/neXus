package com.securityexpert.nexus.ui2.worker.systemlog.redaction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.systemlog.SourceFamily;

/**
 * The Check Point text rules (contract section 12). Synthetic lines only: no text here comes from a device. Every
 * rule has positive cases (the value goes, the rest of the line stays) and negative cases (ordinary prose that
 * mentions the same words is untouched).
 */
class LogRedactorTest {

    private static final String R = LogRedactor.REDACTED;
    private final LogRedactor redactor = new LogRedactor();

    private RedactionResult run(SourceFamily family, String text) {
        return redactor.redact(family, text.getBytes(StandardCharsets.UTF_8));
    }

    private String red(String text) {
        return new String(run(SourceFamily.CP_MESSAGES, text).bytes(), StandardCharsets.UTF_8);
    }

    private int count(String text) {
        return run(SourceFamily.CP_MESSAGES, text).redactionCount();
    }

    private void unchanged(String... lines) {
        for (String line : lines) {
            assertEquals(line, red(line), "must stay: " + line);
            assertEquals(0, count(line), "must not count: " + line);
        }
    }

    private static final String PFX = "Oct 17 10:00:00 2026 gw1 clish[2211]: ";

    // ---- Gaia clish audit ------------------------------------------------------------------------------------

    @Test
    void gaiaUserPasswordHashIsRedactedAndTheCommandStays() {
        RedactionResult r = run(SourceFamily.CP_MESSAGES, PFX + "User admin ran: set user monitor password-hash $1$abcdefgh$AbCdEfGhIjKlMnOpQrStU. end");
        assertEquals(PFX + "User admin ran: set user monitor password-hash " + R + " end", new String(r.bytes(), StandardCharsets.UTF_8));
        assertEquals(Map.of("gaia_user_password", 1), r.countsByRule());
        assertEquals(1, r.redactionCount());
        assertEquals("cp-text-v1", r.ruleSetId());
    }

    @Test
    void gaiaClearPasswordAndExpertPasswordFormsAreRedacted() {
        assertEquals(PFX + "set user admin password " + R, red(PFX + "set user admin password Sup3rS3cret!"));
        assertEquals(PFX + "add user bob password " + R + " uid 0", red(PFX + "add user bob password Pa55w0rd uid 0"));
        assertEquals(PFX + "set expert-password " + R, red(PFX + "set expert-password hunter2"));
    }

    @Test
    void expertPasswordHashAndOtherPasswordHashKeywordsAreRedactedWhenTheValueIsHashShaped() {
        RedactionResult r = run(SourceFamily.CP_MESSAGES, PFX + "set expert-password-hash $6$salt1234$HASHhashHASHhash9");
        assertEquals(PFX + "set expert-password-hash " + R, new String(r.bytes(), StandardCharsets.UTF_8));
        assertEquals(Map.of("gaia_password_hash", 1), r.countsByRule());
        assertEquals(PFX + "set grub-password-hash " + R, red(PFX + "set grub-password-hash $1$xxxxxxxx$yyyyyyyyyyyyyyyyyyyyyy"));
        assertEquals(PFX + "password-hash: " + R, red(PFX + "password-hash: 0123abcd4567ef89"));
    }

    @Test
    void passwordCommandsWithoutAValueAndPasswordProseAreNotTouched() {
        unchanged(
                PFX + "set user admin password-controls complexity 2",
                PFX + "set password-controls min-password-length 8",
                PFX + "password-hash changed for user admin",
                PFX + "set user admin password",
                PFX + "password policy updated",
                PFX + "Accepted password for admin from 192.0.2.4 port 22 ssh2",
                PFX + "Failed password for invalid user guest from 192.0.2.4",
                PFX + "COMMAND=/usr/bin/passwd admin",
                "passwd[812]: pam_unix(passwd:chauthtok): password changed for admin",
                PFX + "Enter password:");
    }

    // ---- SNMP -------------------------------------------------------------------------------------------------

    @Test
    void snmpCommunityStringsAreRedactedInClishAndSnmpdForms() {
        assertEquals(PFX + "set snmp community " + R + " read-only", red(PFX + "set snmp community pubL1c read-only"));
        assertEquals(PFX + "add snmp community " + R + " read-write", red(PFX + "add snmp community privW0rd read-write"));
        assertEquals("rocommunity " + R + " 192.0.2.0/24", red("rocommunity s3cr3tRO 192.0.2.0/24"));
        assertEquals("rwcommunity6 " + R, red("rwcommunity6 s3cr3tRW"));
        assertEquals("com2sec notConfigUser default " + R, red("com2sec notConfigUser default s3cr3t"));
        assertEquals("trap2sink 192.0.2.10 " + R, red("trap2sink 192.0.2.10 trapSecret"));
        assertEquals("set snmp traps receiver 192.0.2.10 community " + R, red("set snmp traps receiver 192.0.2.10 community trapSecret"));
        assertEquals("snmpd: community=" + R + " from 192.0.2.8", red("snmpd: community=pubL1c from 192.0.2.8"));
    }

    @Test
    void snmpCommunityProseAndKeywordsWithoutAValueStay() {
        unchanged(
                PFX + "set snmp community read-only",
                PFX + "SNMP community string changed",
                PFX + "snmpd[100]: authentication failure from 192.0.2.7",
                PFX + "set snmp agent on",
                PFX + "show snmp community");
    }

    @Test
    void snmpUsmAuthAndPrivStringsAreRedacted() {
        RedactionResult r = run(SourceFamily.CP_MESSAGES, PFX
                + "add snmp usm user bob security-level authPriv auth-pass-phrase AuthPhrase123 privacy-pass-phrase PrivPhrase456");
        assertEquals(PFX + "add snmp usm user bob security-level authPriv auth-pass-phrase " + R + " privacy-pass-phrase " + R,
                new String(r.bytes(), StandardCharsets.UTF_8));
        assertEquals(Map.of("snmp_usm", 2), r.countsByRule());
        assertEquals("add snmp usm user bob auth-pass-phrase-hashed " + R, red("add snmp usm user bob auth-pass-phrase-hashed 0a1b2c3d4e5f"));
        assertEquals("createUser bob SHA \"" + R + "\" AES \"" + R + "\"", red("createUser bob SHA \"auth pass\" AES \"priv pass\""));
        assertEquals("createUser bob MD5 " + R, red("createUser bob MD5 authsecret"));
        assertEquals(2, count("createUser bob MD5 authsecret DES privsecret"));
    }

    @Test
    void snmpUsmWordsWithoutAValueStay() {
        unchanged(PFX + "usm user bob authentication failed", "createUser bob", PFX + "authentication-password mismatch for user bob",
                PFX + "snmpd: usm: unknown user name");
    }

    // ---- RADIUS / TACACS --------------------------------------------------------------------------------------

    @Test
    void radiusAndTacacsSecretsAreRedacted() {
        assertEquals(PFX + "add aaa radius-servers priority 1 host 192.0.2.5 key " + R + " port 1812 timeout 3",
                red(PFX + "add aaa radius-servers priority 1 host 192.0.2.5 key RadKey123 port 1812 timeout 3"));
        assertEquals(PFX + "set aaa tacacs-servers priority 1 server 192.0.2.6 key " + R, red(PFX + "set aaa tacacs-servers priority 1 server 192.0.2.6 key TacKey9"));
        assertEquals("tacacs+: server 192.0.2.6 secret=" + R, red("tacacs+: server 192.0.2.6 secret=TacKey9"));
        assertEquals("pam_radius_auth: server 192.0.2.5 shared secret " + R, red("pam_radius_auth: server 192.0.2.5 shared secret s3cr3tRAD"));
    }

    @Test
    void radiusAndTacacsProseStays() {
        unchanged(
                PFX + "RADIUS server 192.0.2.5 key mismatch",
                PFX + "pam_radius_auth: shared secret is wrong",
                PFX + "tacacs server 192.0.2.6 timed out",
                PFX + "radius request failed: no response");
    }

    // ---- password= / passwd / key= ----------------------------------------------------------------------------

    @Test
    void assignmentsRedactOnlyTheValue() {
        assertEquals("login failed password=" + R + " user=admin", red("login failed password=Hunter2 user=admin"));
        assertEquals("cfg passwd=\"" + R + "\" next", red("cfg passwd=\"a b c\" next"));
        assertEquals("cfg passwd='" + R + "' next", red("cfg passwd='a b c' next"));
        assertEquals("{\"user\":\"x\",\"password\":\"" + R + "\"}", red("{\"user\":\"x\",\"password\":\"abc\"}"));
        assertEquals("GET /login?user=a&key=" + R + "&x=1", red("GET /login?user=a&key=Abc123&x=1"));
        assertEquals("api_key=" + R, red("api_key=ZZZ999"));
        assertEquals("shared_secret=" + R + ", ok", red("shared_secret=abcdef, ok"));
        assertEquals("pre-shared-key=" + R, red("pre-shared-key=aabbcc"));
        assertEquals("snmp-community-string: " + R, red("snmp-community-string: pubL1c"));
        assertEquals("Secret: " + R, red("Secret: s3cr3t"));
        assertEquals("(password=" + R + ")", red("(password=abc)"));
        assertEquals("password = " + R, red("password = abc"));
        assertEquals("token=" + R + " key=" + R, red("token=abc key=def"));
    }

    @Test
    void assignmentNegativesAreLeftAlone() {
        unchanged(
                "key exchange completed",
                "password policy updated",
                "secret rotation due",
                "Key: format invalid",
                "key==5",
                "monkey=3",
                "keyboard=us",
                "key_id=5",
                "key-lifetime=3600",
                "tokens=4",
                "password=",
                "password=none",
                "password=(null)",
                "key=null",
                "publickey authentication ok",
                "Accepted publickey for admin from 192.0.2.4 port 5555 ssh2: RSA SHA256:AbCdEf",
                "pam_unix(sshd:auth): authentication failure; logname= uid=0 rhost=192.0.2.4",
                "Key: ",
                "the key was rotated");
    }

    @Test
    void cliFlagsAreRedacted() {
        assertEquals("run tool --password " + R + " --verbose", red("run tool --password Abc123 --verbose"));
        assertEquals("run tool --secret=" + R, red("run tool --secret=Abc123"));
        unchanged("run tool --password --verbose", "run tool --passwordless", "run --keyfile /etc/k");
    }

    @Test
    void credentialsInUrlsAreRedacted() {
        assertEquals("fetch https://admin:" + R + "@192.0.2.9/path failed", red("fetch https://admin:Pa55w0rd@192.0.2.9/path failed"));
        unchanged("fetch https://192.0.2.9:8443/path@x failed", "mailto:admin@example.com", "fetch https://admin@192.0.2.9/p");
    }

    // ---- blobs, hex, hashes -----------------------------------------------------------------------------------

    @Test
    void base64AndJwtBlobsAfterAuthKeywordsAreRedacted() {
        assertEquals("hdr Authorization: Basic " + R, red("hdr Authorization: Basic dXNlcjpwYXNzd29yZDEyMw=="));
        assertEquals("hdr Authorization: Bearer " + R + " tail", red("hdr Authorization: Bearer eyJhbGciOiJI.eyJzdWIiOiIx.abcDEF123 tail"));
        assertEquals("auth " + R, red("auth QWxhZGRpbjpvcGVuIHNlc2FtZQ12"));
        assertEquals("credentials: " + R, red("credentials: Zm9vOmJhcjEyMzQ1Njc4OQ=="));
    }

    @Test
    void authKeywordsFollowedByOrdinaryWordsOrPathsStay() {
        unchanged(
                "auth /usr/lib/security/pam_unix.so",
                "token expired",
                "basic configuration loaded",
                "bearer authentication",
                "auth abcdefghijklmnopqrstuvwxyz",
                "Authorization: Negotiate",
                "authentication failed for user admin");
    }

    @Test
    void longHexAfterSecretVocabularyIsRedacted() {
        assertEquals("ike: negotiated key " + R + " done", red("ike: negotiated key 0123456789abcdef0123456789abcdef done"));
        assertEquals("secret is 0x" + R, red("secret is 0x0011223344556677aabb"));
        assertEquals("session_key=" + R, red("session_key=aabbccddeeff00112233"));
        unchanged("key 1234567890123456", "key exchange completed", "key 0123456789abcde", "hash 0123456789abcdef0123456789abcdef");
    }

    @Test
    void cryptHashesAreRedactedWherever() {
        assertEquals("useradd: hash " + R + " ok", red("useradd: hash $6$rounds=5000$saltsalt$AbCdEfGhIjKlMnOpQrStUvWxYz0123456789 ok"));
        assertEquals("x " + R, red("x $1$abcdefgh$AbCdEfGhIjKlMnOpQrStU."));
        unchanged("cost $5 and $6 total", "echo $1$2$3", "price $1$ each");
    }

    @Test
    void pemPrivateKeyBlocksAreRedactedLineByLine() {
        String body = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDabcdefghijklmnop";
        String in = "k: -----BEGIN RSA PRIVATE KEY-----\n" + body + "\n" + body + "\nAg==\n-----END RSA PRIVATE KEY-----\nafter\n";
        RedactionResult r = run(SourceFamily.CP_MESSAGES, in);
        assertEquals("k: -----BEGIN RSA PRIVATE KEY-----\n" + R + "\n" + R + "\n" + R + "\n-----END RSA PRIVATE KEY-----\nafter\n",
                new String(r.bytes(), StandardCharsets.UTF_8));
        assertEquals(Map.of("pem_private_key", 3), r.countsByRule());
        // a syslog prefix on each body line is kept
        assertEquals(PFX + "-----BEGIN PRIVATE KEY-----\n" + PFX + R + "\n" + PFX + "-----END PRIVATE KEY-----",
                red(PFX + "-----BEGIN PRIVATE KEY-----\n" + PFX + body + "\n" + PFX + "-----END PRIVATE KEY-----"));
        // one-line form
        assertEquals("x -----BEGIN PRIVATE KEY-----" + R + "-----END PRIVATE KEY----- y",
                red("x -----BEGIN PRIVATE KEY-----MIIEvQIBADANBg-----END PRIVATE KEY----- y"));
        // a public certificate is not private key material
        unchanged("-----BEGIN CERTIFICATE-----\nMIIDdzCCAl+gAwIBAgIEAgAAuTANBgkqhkiG9w0BAQUFADBaMQswCQYDVQQGEwJJ\n-----END CERTIFICATE-----");
    }

    @Test
    void aStandaloneLongBase64LineLosesItsBodyEvenWithoutABeginLine() {
        String body = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDabcdefghijklmnop";
        assertEquals(R + "\nordinary\n", red(body + "\nordinary\n"));
        unchanged("/var/log/some/very/long/path/with/many/segments/and/more/segments/x.log", "short");
    }

    // ---- check point fwk.elg vocabulary ----------------------------------------------------------------------

    @Test
    void fwkElgVocabularyLinesKeepTheirNonSecretText() {
        String prefix = "[17 Oct 10:15:01][fw4_0];[cpu_2];";
        RedactionResult r = run(SourceFamily.CP_FWK_ELG, prefix + "fw_vpn: IKE negotiated, key=AAbb11cc22 lifetime 3600, peer 192.0.2.1\n"
                + prefix + "vpn_encrypt: password policy updated\n"
                + prefix + "fwlddist: secret: s3cr3tvalue received from 192.0.2.3\n"
                + "continuation line without header, key exchange completed\n"
                + prefix + "cpas: token=abc123 refreshed\n");
        assertEquals(prefix + "fw_vpn: IKE negotiated, key=" + R + " lifetime 3600, peer 192.0.2.1\n"
                + prefix + "vpn_encrypt: password policy updated\n"
                + prefix + "fwlddist: secret: " + R + " received from 192.0.2.3\n"
                + "continuation line without header, key exchange completed\n"
                + prefix + "cpas: token=" + R + " refreshed\n", new String(r.bytes(), StandardCharsets.UTF_8));
        assertEquals(3, r.redactionCount());
        assertEquals(SourceFamily.CP_FWK_ELG, r.family());
    }

    @Test
    void multiLineRecordsAreHandledLineByLine() {
        String in = "[cpd 1234 5678]@gw1[17 Oct 10:00:00] start of record\n    password=Abc123 continued\n    plain continuation\n";
        assertEquals("[cpd 1234 5678]@gw1[17 Oct 10:00:00] start of record\n    password=" + R + " continued\n    plain continuation\n",
                red(in));
    }

    // ---- counts, ids -----------------------------------------------------------------------------------------

    @Test
    void countsAreSummedPerRuleAndNeverCarryText() {
        RedactionResult r = run(SourceFamily.CP_MESSAGES, "a password=x1 and key=y2\nb token=z3\nc set snmp community cc read-only\n");
        assertEquals(Map.of("secret_assignment", 3, "snmp_community", 1), r.countsByRule());
        assertEquals(4, r.redactionCount());
        assertTrue(r.changed());
        assertFalse(r.countsByRule().toString().contains("x1"));
        assertTrue(RuleId.ids().containsAll(r.countsByRule().keySet()));
    }

    @Test
    void aChunkWithNothingToRedactComesBackEqualWithZeroCounts() {
        byte[] in = "ordinary line one\nordinary line two\n".getBytes(StandardCharsets.UTF_8);
        RedactionResult r = redactor.redact(SourceFamily.CP_MESSAGES, in);
        assertArrayEquals(in, r.bytes());
        assertEquals(0, r.redactionCount());
        assertEquals(Map.of(), r.countsByRule());
        assertFalse(r.changed());
    }

    @Test
    void theRuleSetIdIsStableAndRulesAreListed() {
        assertEquals("cp-text-v1", LogRedactor.RULE_SET_ID);
        assertEquals(12, RuleId.ids().size());
        assertEquals(RuleId.ids().size(), RuleId.ids().stream().distinct().count());
    }

    // ---- idempotence -----------------------------------------------------------------------------------------

    @Test
    void redactingTwiceEqualsRedactingOnceAndCountsNothingTheSecondTime() {
        List<String> corpus = List.of(
                PFX + "set user monitor password-hash $1$abcdefgh$AbCdEfGhIjKlMnOpQrStU.",
                PFX + "set user admin password Sup3rS3cret!",
                PFX + "set expert-password-hash $6$salt1234$HASHhashHASHhash9",
                PFX + "set snmp community pubL1c read-only",
                "rocommunity s3cr3tRO 192.0.2.0/24",
                "createUser bob SHA \"auth pass\" AES \"priv pass\"",
                PFX + "add snmp usm user bob auth-pass-phrase AuthPhrase123 privacy-pass-phrase PrivPhrase456",
                PFX + "add aaa radius-servers priority 1 host 192.0.2.5 key RadKey123 port 1812",
                "login failed password=Hunter2 user=admin",
                "cfg passwd=\"a b c\" next", "{\"password\":\"abc\"}", "GET /login?user=a&key=Abc123&x=1",
                "run tool --password Abc123 --verbose",
                "fetch https://admin:Pa55w0rd@192.0.2.9/path failed",
                "hdr Authorization: Basic dXNlcjpwYXNzd29yZDEyMw==",
                "hdr Authorization: Bearer eyJhbGciOiJI.eyJzdWIiOiIx.abcDEF123 tail",
                "ike: negotiated key 0123456789abcdef0123456789abcdef done",
                "useradd: hash $6$rounds=5000$saltsalt$AbCdEfGhIjKlMnOpQrStUvWxYz0123456789 ok",
                "-----BEGIN RSA PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDabcdefghijklmnop\nAg==\n-----END RSA PRIVATE KEY-----\n",
                "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDabcdefghijklmnop\n",
                "a: password=x,\r\nb: key=\"q r\" \r\n",
                "x password=<redacted> key=<redacted>");
        for (String line : corpus) {
            RedactionResult once = run(SourceFamily.CP_MESSAGES, line);
            RedactionResult twice = redactor.redact(SourceFamily.CP_MESSAGES, once.bytes());
            assertArrayEquals(once.bytes(), twice.bytes(), "not idempotent: " + line);
            assertEquals(0, twice.redactionCount(), "second pass counted: " + line);
        }
    }

    // ---- bytes, UTF-8, line endings --------------------------------------------------------------------------

    @Test
    void crlfAndLfTerminatorsAndAMissingFinalNewlineAreKeptExactly() {
        assertEquals("a password=" + R + "\r\nb\nc key=" + R + "\r\nlast token=" + R, red("a password=x\r\nb\nc key=y\r\nlast token=z"));
        assertEquals("\r\n\n\r\n", red("\r\n\n\r\n"));
        assertEquals("", red(""));
        assertEquals("password=" + R + "\r", red("password=abc\r"));
    }

    @Test
    void utf8TextPassesThroughAndAMultibyteSecretGoesWithItsValue() {
        assertEquals("café 中文 password=" + R + " ñ end", red("café 中文 password=pässwörd€ ñ end"));
        assertEquals("emoji 😀 line", red("emoji 😀 line"));
        assertEquals("﻿bom password=" + R, red("﻿bom password=abc"));
        assertEquals("päss word=é", red("päss word=é"));
    }

    @Test
    void invalidUtf8AndBinaryBytesPassThroughUnchangedAndAreNeverDropped() {
        byte[] prefix = {(byte) 0xFF, (byte) 0xFE, (byte) 0xC3, (byte) 0x28, 0x00, (byte) 0x80, ' '};
        byte[] secretLine = "password=abc\n".getBytes(StandardCharsets.UTF_8);
        byte[] tail = {(byte) 0xE4, (byte) 0xB8}; // truncated 3-byte sequence at the chunk end, no newline
        byte[] in = concat(prefix, secretLine, prefix, tail);
        RedactionResult r = redactor.redact(SourceFamily.CP_MESSAGES, in);
        byte[] expected = concat(prefix, "password=<redacted>\n".getBytes(StandardCharsets.UTF_8), prefix, tail);
        assertArrayEquals(expected, r.bytes());
        assertEquals(1, r.redactionCount());
        // invalid bytes inside the secret value are part of the value
        byte[] withBadValue = concat("password=".getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xFF, (byte) 0xC3}, " ok".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("password=<redacted> ok".getBytes(StandardCharsets.UTF_8), redactor.redact(SourceFamily.CP_MESSAGES, withBadValue).bytes());
        // all 256 byte values in a secret-free line survive
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        all[10] = 'x'; // no newline inside; an LF would only split lines
        assertArrayEquals(all, redactor.redact(SourceFamily.CP_MESSAGES, all).bytes());
    }

    @Test
    void offsetAndLengthSelectTheChunkAndAreChecked() {
        byte[] data = "XXpassword=abc\nYY".getBytes(StandardCharsets.UTF_8);
        RedactionResult r = redactor.redact(SourceFamily.CP_CPD_ELG, data, 2, 13);
        assertEquals("password=<redacted>\n", new String(r.bytes(), StandardCharsets.UTF_8));
        assertThrows(IndexOutOfBoundsException.class, () -> redactor.redact(SourceFamily.CP_CPD_ELG, data, 10, 20));
        assertThrows(IndexOutOfBoundsException.class, () -> redactor.redact(SourceFamily.CP_CPD_ELG, data, -1, 2));
        assertThrows(NullPointerException.class, () -> redactor.redact(null, data));
    }

    @Test
    void theInputArrayIsNeverModified() {
        byte[] in = "password=abc\n".getBytes(StandardCharsets.UTF_8);
        byte[] copy = in.clone();
        redactor.redact(SourceFamily.CP_MESSAGES, in);
        assertArrayEquals(copy, in);
    }

    @Test
    void everySourceFamilyUsesTheSameRules() {
        for (SourceFamily family : SourceFamily.values()) {
            RedactionResult r = run(family, "x password=abc y\n");
            assertEquals("x password=<redacted> y\n", new String(r.bytes(), StandardCharsets.UTF_8));
            assertEquals(family, r.family());
        }
    }

    @Test
    void aVeryLongLineWithoutSecretsIsLinear() {
        String line = "word ".repeat(200_000) + "\n";
        long t0 = System.nanoTime();
        assertEquals(line, red(line));
        String hot = ("password=abcdef " + "a-".repeat(1000)).repeat(500) + "\n";
        red(hot);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000, "redaction must stay fast");
    }

    private static byte[] concat(byte[]... parts) {
        List<Byte> all = new ArrayList<>();
        for (byte[] p : parts) {
            for (byte b : p) {
                all.add(b);
            }
        }
        byte[] out = new byte[all.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = all.get(i);
        }
        return out;
    }
}
