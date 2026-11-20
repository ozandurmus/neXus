package com.securityexpert.nexus.ui2.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Script output masking ({@code SCRIPT_EXECUTION_CONTRACT.md} Amendment 2026-11-18 (b) item 6; review finding 12).
 * Secret-shaped values are assembled at run time.
 */
class ScriptOutputMaskingTest {

    private static String secret() {
        return "Zq" + "9!" + "s3cr" + "et/v@lue+" + Integer.toString(31337, 36);
    }

    @Test
    void masksEveryEncodedFormOfASecretAndTheUsername() {
        String s = secret();
        String user = "svc" + "_backup";
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        byte[] bn = (s + "\n").getBytes(StandardCharsets.UTF_8);
        List<String> forms = List.of(
                s,
                Base64.getEncoder().encodeToString(b),
                Base64.getEncoder().encodeToString(bn),
                Base64.getUrlEncoder().withoutPadding().encodeToString(bn),
                URLEncoder.encode(s, StandardCharsets.UTF_8),
                HexFormat.of().formatHex(b),
                HexFormat.of().formatHex(b).toUpperCase(Locale.ROOT),
                ScriptOutputMasking.jsonEscape("x\"" + s).substring(3),
                "{\"pw\":\"" + ScriptOutputMasking.jsonEscape(s) + "\"}");
        for (String form : forms) {
            String out = ScriptOutputMasking.mask("before " + form + " after", List.of(s, user));
            assertFalse(out.contains(s), form);
            assertFalse(out.contains(form), form);
            assertTrue(out.contains(ScriptOutputMasking.REDACTED_PLACEHOLDER), form);
        }
        assertFalse(ScriptOutputMasking.mask("login as " + user + " ok", List.of(s, user)).contains(user));

        // 76-column MIME Base64 (GNU base64 default wrapping for secrets >= 57 bytes)
        String longSecret = s + "-extended-" + "x".repeat(55);
        String mimeWrapped = Base64.getMimeEncoder(76, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(longSecret.getBytes(StandardCharsets.UTF_8));
        assertTrue(mimeWrapped.contains("\n"));
        String maskedMime = ScriptOutputMasking.mask("wrapped=" + mimeWrapped + "\n", List.of(longSecret));
        assertFalse(maskedMime.contains(mimeWrapped.split("\n")[0]));
        assertTrue(maskedMime.contains(ScriptOutputMasking.REDACTED_PLACEHOLDER));
    }

    @Test
    void masksBeforeTruncatingSoASecretAcrossTheCutLeavesNoPrefix() {
        String s = secret();
        int cut = ScriptExecutionJob.MAX_STREAM_BYTES;
        // The secret starts 5 bytes before the 256 KiB cut: truncate-then-mask would keep its first 5 characters.
        String stdout = "a".repeat(cut - 5) + s + "b".repeat(1000);
        ScriptOutputMasking.MaskedRunOutput out = ScriptOutputMasking.maskThenTruncate(stdout, "", false, false, List.of(s));
        assertTrue(out.stdoutTruncated());
        assertTrue(out.stdoutBytes() <= cut);
        assertFalse(out.maskedStdout().endsWith(s.substring(0, 5)), "no readable prefix of the secret at the cut");
        assertFalse(new String(out.combinedUtf8Bytes(), StandardCharsets.UTF_8).contains(s.substring(0, 5)));
        assertTrue(out.inlineTail().length() <= ScriptExecutionJob.MAX_TAIL_BYTES);
    }

    @Test
    void shortValuesAreNotMaskedAndTheLimitIsStated() {
        assertEquals("ab ok", ScriptOutputMasking.mask("ab ok", List.of("ab")));
        assertEquals(3, ScriptOutputMasking.MIN_MASKED_LENGTH);
    }

    @Test
    void aliasesCollideIntoSuffixesWithAWarningNeverARefusal() {
        var assignment = ScriptCredentialAliases.assign(List.of(
                new ScriptCredentialAliases.DeclaredRef("r1", "Vault Reader"),
                new ScriptCredentialAliases.DeclaredRef("r2", "vault-reader"),
                new ScriptCredentialAliases.DeclaredRef("r3", "VAULT.READER"),
                new ScriptCredentialAliases.DeclaredRef("r4", ""),
                new ScriptCredentialAliases.DeclaredRef("9x", "")));
        assertEquals(List.of("VAULT_READER", "VAULT_READER_2", "VAULT_READER_3", "R4", "CRED_9X"),
                List.copyOf(assignment.aliasByRef().values()));
        assertEquals(2, assignment.warnings().size());
    }

    @Test
    void runAsAllowlistAdmitsRolesOnlyNeverSecurityAdminOrAUser() {
        for (String ok : List.of("role:operator", "role:viewer", "role:replay_viewer")) {
            assertTrue(ScriptExecutionJob.isAllowedRunAs(ok), ok);
            assertFalse(ScriptExecutionJob.isForbiddenRunAs(ok), ok);
        }
        for (String bad : List.of("role:security_admin", "security_admin", "alice", "role:operation_admin", "", " ")) {
            assertTrue(ScriptExecutionJob.isForbiddenRunAs(bad), bad);
        }
        assertEquals(java.util.Optional.empty(),
                ScriptExecutionJob.extractSessionRunAs("script_session:s1:j1:alice"));
        assertEquals(java.util.Optional.of("role:viewer"),
                ScriptExecutionJob.extractSessionRunAs("script_session:s1:j1:role:viewer"));
        assertTrue(ScriptExecutionJob.isMachineRunAs("role:replay_viewer"));
        assertFalse(ScriptExecutionJob.isMachineRunAs("role:viewer"));
        assertFalse(ScriptExecutionJob.isMachineRunAs("role:operator"));
    }
}
