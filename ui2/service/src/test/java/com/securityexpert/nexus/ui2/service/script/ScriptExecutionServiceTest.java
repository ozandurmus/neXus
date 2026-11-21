package com.securityexpert.nexus.ui2.service.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRecord;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptSecretFinding;
import com.securityexpert.nexus.ui2.persistence.script.ScriptVersionRecord;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.api.ScriptExecutionController;
import com.securityexpert.nexus.ui2.service.api.ScriptExecutionController.CreateScriptRequest;
import com.securityexpert.nexus.ui2.service.api.ScriptExecutionController.NewVersionRequest;
import com.securityexpert.nexus.ui2.service.api.ScriptExecutionController.PatchSettingsRequest;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.CreateScriptCommand;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.NewVersionCommand;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.Outcome;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService.UpdateSettingsCommand;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Unit tests for Script Execution slice 1: validation, supported kinds, {@code .bat}/{@code .cmd} refusal,
 * non-blocking secret-scan warnings, envelope-encrypted write-only script bodies, append-only monotonic versions,
 * five-field cron + server-side next-five-fires, device-egress correlation warnings, declared {@code credential_refs}
 * validation, AIView masking, and V251 migration invariants.
 */
class ScriptExecutionServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private InMemoryScriptRepository scriptRepo;
    private Set<String> validCredentialRefs;
    private Map<String, String> enrolledDeviceEndpoints;
    private FileArtefactStore artefactStore;
    private ScriptExecutionService service;
    private ScriptExecutionController controller;

    @BeforeEach
    void setUp() {
        scriptRepo = new InMemoryScriptRepository();
        validCredentialRefs = Set.of("cred-ref-alpha", "cred-ref-bravo");
        enrolledDeviceEndpoints = new LinkedHashMap<>();
        enrolledDeviceEndpoints.put("192.0.2.10", "dev-tango-04");
        enrolledDeviceEndpoints.put("198.51.100.25", "dev-juliet-06");

        String keyBase64 = Base64.getEncoder().encodeToString(new byte[32]);
        artefactStore = new FileArtefactStore(tempDir.resolve("artefacts"), ArtefactStoreCipher.fromBase64Key(keyBase64));

        CredentialReferenceRepository credRepo = new CredentialReferenceRepository() {
            @Override
            public boolean exists(String credentialReferenceId) {
                return validCredentialRefs.contains(credentialReferenceId);
            }

            @Override
            public Optional<CredentialReferenceRecord> find(String credentialReferenceId) {
                return exists(credentialReferenceId)
                        ? Optional.of(new CredentialReferenceRecord(credentialReferenceId, "ssh_password", "ptr", FIXED_NOW))
                        : Optional.empty();
            }
        };

        DeviceRepository deviceRepo = new StubDeviceRepository(enrolledDeviceEndpoints);
        service = new ScriptExecutionService(scriptRepo, credRepo, deviceRepo, artefactStore, FIXED_CLOCK, 4096);
        byte[] hmacKey = "synthetic-hmac-key-for-script-execution-tests".getBytes(StandardCharsets.UTF_8);
        controller = new ScriptExecutionController(
                service,
                new TopologyNamePseudonymizer(hmacKey),
                new SubnetPreservingIpMasker(hmacKey));
    }

    @Test
    void supportsAllTenContractSection7KindsAndInfersKindFromExtension() {
        Map<String, byte[]> samples = new LinkedHashMap<>();
        samples.put("backup.sh", "#!/usr/bin/env sh\necho ok\n".getBytes(StandardCharsets.UTF_8));
        samples.put("check.bash", "#!/usr/bin/env bash\necho ok\n".getBytes(StandardCharsets.UTF_8));
        samples.put("audit.py", "print('ok')\n".getBytes(StandardCharsets.UTF_8));
        samples.put("tool.jar", new byte[] {0x50, 0x4b, 0x03, 0x04, (byte) 0xff, 0x00, 0x11});
        samples.put("index.js", "console.log('ok');\n".getBytes(StandardCharsets.UTF_8));
        samples.put("mod.mjs", "export const ok = true;\n".getBytes(StandardCharsets.UTF_8));
        samples.put("report.pl", "print \"ok\\n\";\n".getBytes(StandardCharsets.UTF_8));
        samples.put("task.rb", "puts 'ok'\n".getBytes(StandardCharsets.UTF_8));
        samples.put("agent.bin", new byte[] {0x7f, 0x45, 0x4c, 0x46, (byte) 0x80, 0x00});

        for (Map.Entry<String, byte[]> entry : samples.entrySet()) {
            String fileName = entry.getKey();
            String expectedKind = fileName.substring(fileName.lastIndexOf('.') + 1);
            Outcome outcome = service.create(new CreateScriptCommand(
                    null, fileName, null, entry.getValue(), null, false, 120, List.of(), List.of(), "none", null),
                    "actor-secadmin");
            assertTrue(outcome instanceof Outcome.Ok, "Expected Ok for " + fileName + ", got: " + outcome);
            ScriptRecord saved = ((Outcome.Ok) outcome).details().script();
            assertEquals(expectedKind, saved.kind());
            assertEquals(1, saved.currentVersion());
        }
        assertEquals(9, service.list().size());
        // .ps1: technical refusal until PowerShell 7 is in the execution image (Amendment 2026-11-18 (b) item 8).
        Outcome ps1 = service.create(new CreateScriptCommand(
                null, "winstyle.ps1", null, "Write-Output 'ok'\n".getBytes(StandardCharsets.UTF_8), null, false, 120,
                List.of(), List.of(), "none", null), "actor-secadmin");
        assertTrue(ps1 instanceof Outcome.Refused);
        assertEquals(ScriptExecutionService.PS1_REFUSAL_REASON, ((Outcome.Refused) ps1).message());
    }

    @Test
    void refusesBatAndCmdUploadsWithExactContractReason() {
        for (String badFile : List.of("legacy.bat", "LEGACY.BAT", "run.cmd", "RUN.CMD")) {
            Outcome outcome = service.create(new CreateScriptCommand(
                    "win-script", badFile, null, "@echo off\r\n".getBytes(StandardCharsets.UTF_8),
                    null, false, 60, List.of(), List.of(), "none", null),
                    "actor-secadmin");
            assertTrue(outcome instanceof Outcome.Refused, "Expected refusal for " + badFile);
            Outcome.Refused refused = (Outcome.Refused) outcome;
            assertEquals(400, refused.status());
            assertEquals("UNSUPPORTED_SCRIPT_KIND", refused.code());
            assertEquals("cannot run on the Linux runner; use PowerShell (.ps1)", refused.message());
        }

        for (String badKind : List.of("bat", "cmd", ".bat", ".cmd")) {
            Outcome outcome = service.create(new CreateScriptCommand(
                    "win-script", null, badKind, "@echo off\r\n".getBytes(StandardCharsets.UTF_8),
                    null, false, 60, List.of(), List.of(), "none", null),
                    "actor-secadmin");
            assertTrue(outcome instanceof Outcome.Refused);
            assertEquals("cannot run on the Linux runner; use PowerShell (.ps1)", ((Outcome.Refused) outcome).message());
        }
        assertTrue(scriptRepo.findAll().isEmpty(), "Refused .bat/.cmd uploads must never store a record");
    }

    @Test
    void enforcesSizeLimitAndUtf8ValidationOnTextKinds() {
        Outcome empty = service.create(new CreateScriptCommand(
                "empty-script", "empty.sh", null, new byte[0], null, false, 60, List.of(), List.of(), "none", null),
                "actor-secadmin");
        assertTrue(empty instanceof Outcome.Refused);
        assertEquals("EMPTY_SCRIPT_BODY", ((Outcome.Refused) empty).code());

        byte[] tooLarge = new byte[4097];
        java.util.Arrays.fill(tooLarge, (byte) 'a');
        Outcome oversized = service.create(new CreateScriptCommand(
                "oversized", "oversized.sh", null, tooLarge, null, false, 60, List.of(), List.of(), "none", null),
                "actor-secadmin");
        assertTrue(oversized instanceof Outcome.Refused);
        assertEquals("SCRIPT_TOO_LARGE", ((Outcome.Refused) oversized).code());

        byte[] invalidUtf8 = new byte[] {(byte) 0xff, (byte) 0xfe, 0x41};
        Outcome badUtf8 = service.create(new CreateScriptCommand(
                "bad-utf8", "bad.py", null, invalidUtf8, null, false, 60, List.of(), List.of(), "none", null),
                "actor-secadmin");
        assertTrue(badUtf8 instanceof Outcome.Refused);
        assertEquals("INVALID_UTF8", ((Outcome.Refused) badUtf8).code());

        byte[] nulInText = "echo hello\0world\n".getBytes(StandardCharsets.UTF_8);
        Outcome badNul = service.create(new CreateScriptCommand(
                "bad-nul", "bad.sh", null, nulInText, null, false, 60, List.of(), List.of(), "none", null),
                "actor-secadmin");
        assertTrue(badNul instanceof Outcome.Refused);
        assertEquals("INVALID_UTF8", ((Outcome.Refused) badNul).code());
    }

    @Test
    void secretScanProducesWarningWithLineNumbersNeverRefusesEncryptsAtRestAndNeverReturnsBody() throws Exception {
        // Built at runtime so repository privacy / gitleaks scans never see a literal secret in source.
        String runtimeCredValue = "rt_" + "val_" + (1000 + 337);
        String line2 = "api" + "_key = \"" + runtimeCredValue + "\"";
        String line4 = "-----BEGIN " + "OPENSSH " + "PRIVATE KEY-----";
        String scriptText = "#!/usr/bin/env bash\n"
                + line2 + "\n"
                + "password = \"redacted\"\n"
                + line4 + "\n";

        var createReq = new CreateScriptRequest(
                "secret-bearing-script",
                "check.sh",
                "sh",
                scriptText,
                null,
                "0 3 * * *",
                false,
                300,
                List.of(),
                List.of("cred-ref-alpha"),
                "syslog",
                "role:operator");
        var httpReq = authedRequest("actor-secadmin", false);
        var response = controller.create(createReq, httpReq);
        assertEquals(201, response.getStatusCode().value(), "Secret findings must produce a warning, never a refusal");

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        // Verify no route response contains the body or the runtime secret value or envelope keys.
        String serialized = body.toString();
        assertFalse(serialized.contains(runtimeCredValue), "Response must never contain a secret value");
        assertFalse(serialized.contains("#!/usr/bin/env bash"), "Response must never contain the script body");
        assertFalse(body.containsKey("body"), "Response must not have a 'body' field");
        assertFalse(body.containsKey("artefact_ref"), "Response must not expose artefact_ref");
        assertFalse(body.containsKey("wrapped_data_key"), "Response must not expose wrapped_data_key");

        @SuppressWarnings("unchecked")
        Map<String, Object> secretFindings = (Map<String, Object>) body.get("secret_findings");
        assertEquals(2, secretFindings.get("count"));
        assertEquals(List.of(2, 4), secretFindings.get("line_numbers"));

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) body.get("warnings");
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.get(0).contains("[2, 4]"));
        assertTrue(warnings.get(0).contains("CREDENTIAL_LITERAL"));
        assertTrue(warnings.get(0).contains("PRIVATE_KEY_MATERIAL"));
        assertTrue(warnings.get(0).contains("credential_refs"));
        assertFalse(warnings.get(0).contains(runtimeCredValue));

        // Verify GET one and GET list also never return the script body even to security_admin.
        String scriptId = (String) body.get("script_id");
        var getResp = controller.get(scriptId, httpReq);
        assertEquals(200, getResp.getStatusCode().value());
        assertFalse(getResp.getBody().toString().contains("#!/usr/bin/env bash"));
        assertFalse(getResp.getBody().toString().contains(runtimeCredValue));

        var listResp = controller.list(httpReq);
        assertEquals(200, listResp.getStatusCode().value());
        assertFalse(listResp.getBody().toString().contains("#!/usr/bin/env bash"));

        // Verify the stored artefact on disk is encrypted at rest and can be decrypted only with wrappedDataKey.
        ScriptVersionRecord storedVersion = scriptRepo.findVersion(scriptId, 1).orElseThrow();
        Path encryptedFile = tempDir.resolve("artefacts").resolve(storedVersion.artefactRef());
        assertTrue(Files.isRegularFile(encryptedFile), "Encrypted script_body artefact must exist on disk");
        byte[] rawDiskBytes = Files.readAllBytes(encryptedFile);
        assertFalse(new String(rawDiskBytes, StandardCharsets.ISO_8859_1).contains(runtimeCredValue),
                "Ciphertext on disk must not contain plaintext script content");
        try (InputStream decrypted = artefactStore.retrieve(
                new ArtefactRef(storedVersion.artefactRef()), storedVersion.wrappedDataKey(), false)) {
            assertEquals(scriptText, new String(decrypted.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void appendOnlyMonotonicVersioningAndScheduleNextFiveFiresAndDeviceEgressWarning() {
        Outcome created = service.create(new CreateScriptCommand(
                "dr-sync",
                "dr-sync.py",
                null,
                "print('v1')\n".getBytes(StandardCharsets.UTF_8),
                "0 2 * * *",
                false,
                600,
                List.of("192.0.2.10:443", "203.0.113.50:8443"),
                List.of("cred-ref-alpha"),
                "both",
                "role:operator"),
                "actor-secadmin");
        assertTrue(created instanceof Outcome.Ok);
        var detailsV1 = ((Outcome.Ok) created).details();
        String scriptId = detailsV1.script().scriptId();
        assertEquals(1, detailsV1.script().currentVersion());
        assertEquals(List.of("dev-tango-04"), detailsV1.script().egressDeviceTargets(),
                "Egress matching an enrolled device's management IP must record its opaque device_id");
        assertTrue(detailsV1.warnings().stream().anyMatch(w -> w.contains("dev-tango-04") && w.contains("outside Overwatch command gates")));
        // Five-field cron is read in Europe/Istanbul (UTC+3): 02:00 local is 23:00Z the day before.
        assertEquals(List.of(
                Instant.parse("2026-11-18T23:00:00Z"),
                Instant.parse("2026-11-19T23:00:00Z"),
                Instant.parse("2026-11-20T23:00:00Z"),
                Instant.parse("2026-11-21T23:00:00Z"),
                Instant.parse("2026-11-22T23:00:00Z")),
                detailsV1.nextFiveFires());

        // Upload version 2
        Outcome v2 = service.addVersion(scriptId, new NewVersionCommand(
                "dr-sync.py", null, "print('v2-updated')\n".getBytes(StandardCharsets.UTF_8), List.of("cred-ref-alpha", "cred-ref-bravo")),
                "actor-secadmin");
        assertTrue(v2 instanceof Outcome.Ok);
        var detailsV2 = ((Outcome.Ok) v2).details();
        assertEquals(2, detailsV2.script().currentVersion());
        assertEquals(2, detailsV2.versions().size());
        assertEquals(2, detailsV2.versions().get(0).version());
        assertEquals(1, detailsV2.versions().get(1).version());
        assertEquals(List.of("cred-ref-alpha", "cred-ref-bravo"), detailsV2.script().credentialRefs());

        // PATCH settings: update schedule to every 15 minutes and verify next five fires
        Outcome patched = service.updateSettings(scriptId, new UpdateSettingsCommand(
                Optional.of("*/15 * * * *"),
                false,
                Optional.of(true),
                Optional.of(1200),
                Optional.empty(),
                Optional.empty(),
                Optional.of("smtp"),
                Optional.empty()),
                "actor-secadmin");
        assertTrue(patched instanceof Outcome.Ok);
        var detailsPatched = ((Outcome.Ok) patched).details();
        assertTrue(detailsPatched.script().enabled());
        assertEquals(1200, detailsPatched.script().timeoutSeconds());
        assertEquals("smtp", detailsPatched.script().notifyMode());
        assertEquals(List.of(
                Instant.parse("2026-11-18T10:15:00Z"),
                Instant.parse("2026-11-18T10:30:00Z"),
                Instant.parse("2026-11-18T10:45:00Z"),
                Instant.parse("2026-11-18T11:00:00Z"),
                Instant.parse("2026-11-18T11:15:00Z")),
                detailsPatched.nextFiveFires());
    }

    @Test
    void changingCredentialRefsInSettingsRecordsANewVersionWithTheSameBody() {
        // Review finding 10: a run injects its version's refs only, so a refs change is a new version.
        Outcome created = service.create(new CreateScriptCommand(
                "refs-check", "refs.sh", null, "echo ok\n".getBytes(StandardCharsets.UTF_8), null, false, 60,
                List.of(), List.of("cred-ref-alpha"), "none", null), "actor-secadmin");
        String scriptId = ((Outcome.Ok) created).details().script().scriptId();
        ScriptVersionRecord v1 = ((Outcome.Ok) created).details().versions().get(0);
        Outcome patched = service.updateSettings(scriptId, new UpdateSettingsCommand(
                Optional.empty(), false, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(List.of("cred-ref-bravo")), Optional.empty(), Optional.empty()), "actor-secadmin");
        var details = ((Outcome.Ok) patched).details();
        assertEquals(2, details.script().currentVersion());
        ScriptVersionRecord v2 = details.versions().stream().filter(v -> v.version() == 2).findFirst().orElseThrow();
        assertEquals(List.of("cred-ref-bravo"), v2.credentialRefs());
        assertEquals(v1.sha256(), v2.sha256());
        assertEquals(v1.artefactRef(), v2.artefactRef());
        // Settings without a refs change record no new version.
        Outcome again = service.updateSettings(scriptId, new UpdateSettingsCommand(
                Optional.empty(), false, Optional.empty(), Optional.of(90), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()), "actor-secadmin");
        assertEquals(2, ((Outcome.Ok) again).details().script().currentVersion());
    }

    @Test
    void runAsIsARoleFromTheAllowlistOnly() {
        for (String bad : List.of("operator-alice", "role:security_admin", "security_admin", "role:operation_admin",
                "role:backup_admin", "ROLE:OPERATOR")) {
            Outcome outcome = service.create(new CreateScriptCommand(
                    "ra-" + Math.abs(bad.hashCode()), "ra.sh", null, "echo\n".getBytes(StandardCharsets.UTF_8), null,
                    false, 60, List.of(), List.of(), "none", bad), "actor-secadmin");
            assertTrue(outcome instanceof Outcome.Refused, bad);
            assertEquals("INVALID_RUN_AS", ((Outcome.Refused) outcome).code(), bad);
        }
        for (String good : List.of("role:operator", "role:viewer", "role:replay_viewer")) {
            Outcome outcome = service.create(new CreateScriptCommand(
                    "ok-" + good.substring(5), "ok.sh", null, "echo\n".getBytes(StandardCharsets.UTF_8), null,
                    false, 60, List.of(), List.of(), "none", good), "actor-secadmin");
            assertTrue(outcome instanceof Outcome.Ok, good);
        }
    }

    @Test
    void validatesCronTimeoutCredentialRefsEgressAndRunAs() {
        byte[] okBody = "#!/usr/bin/env sh\necho 1\n".getBytes(StandardCharsets.UTF_8);

        // 6-field cron rejected (must be 5-field cron)
        Outcome sixFieldCron = service.create(new CreateScriptCommand(
                "s1", "s1.sh", null, okBody, "0 0 2 * * *", false, 300, List.of(), List.of(), "none", null),
                "actor-secadmin");
        assertEquals("INVALID_SCHEDULE", ((Outcome.Refused) sixFieldCron).code());

        // timeout > 3600 rejected
        Outcome badTimeout = service.create(new CreateScriptCommand(
                "s2", "s2.sh", null, okBody, null, false, 3601, List.of(), List.of(), "none", null),
                "actor-secadmin");
        assertEquals("INVALID_TIMEOUT", ((Outcome.Refused) badTimeout).code());

        // unknown credential_ref rejected
        Outcome badCred = service.create(new CreateScriptCommand(
                "s3", "s3.sh", null, okBody, null, false, 300, List.of(), List.of("cred-does-not-exist"), "none", null),
                "actor-secadmin");
        assertEquals("UNKNOWN_CREDENTIAL_REFERENCE", ((Outcome.Refused) badCred).code());

        // malformed egress rejected
        Outcome badEgress = service.create(new CreateScriptCommand(
                "s4", "s4.sh", null, okBody, null, false, 300, List.of("192.0.2.10:99999"), List.of(), "none", null),
                "actor-secadmin");
        assertEquals("INVALID_EGRESS", ((Outcome.Refused) badEgress).code());

        // run_as role:security_admin rejected
        Outcome badRunAs = service.create(new CreateScriptCommand(
                "s5", "s5.sh", null, okBody, null, false, 300, List.of(), List.of(), "none", "role:security_admin"),
                "actor-secadmin");
        assertEquals("INVALID_RUN_AS", ((Outcome.Refused) badRunAs).code());
    }

    @Test
    void aiviewReadsMaskedMetadataAndIsRefusedOnEveryWriteEndpoint() {
        controller.create(new CreateScriptRequest(
                "aiview-check", "aiview.sh", "sh", "#!/usr/bin/env sh\necho ok\n", null,
                "0 4 * * *", false, 300, List.of("192.0.2.10:443"), List.of("cred-ref-alpha"),
                "none", "role:viewer"),
                authedRequest("admin-bob", false));
        // A user name is never a run_as (review finding 8): refused with the allowlist, nothing stored.
        assertEquals(400, controller.create(new CreateScriptRequest(
                "aiview-user", "aiview2.sh", "sh", "echo 1\n", null, null, false, 60, List.of(), List.of(),
                "none", "operator-alice"), authedRequest("admin-bob", false)).getStatusCode().value());

        var viewerReq = authedRequest("viewer-aiview", true);
        var listResp = controller.list(viewerReq);
        assertEquals(200, listResp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scripts = (List<Map<String, Object>>) listResp.getBody().get("scripts");
        assertEquals(1, scripts.size());
        Map<String, Object> item = scripts.get(0);
        assertFalse("admin-bob".equals(item.get("created_by_actor_fingerprint")),
                "aiview must receive a masked actor token, not the raw actor");

        String scriptId = (String) item.get("script_id");
        assertEquals(403, controller.create(new CreateScriptRequest(
                "x", "x.sh", "sh", "echo 1\n", null, null, false, 60, List.of(), List.of(), "none", null), viewerReq)
                .getStatusCode().value());
        assertEquals(403, controller.addVersion(scriptId, new NewVersionRequest("x.sh", "sh", "echo 2\n", null, null), viewerReq)
                .getStatusCode().value());
        assertEquals(403, controller.updateSettings(scriptId, new PatchSettingsRequest(null, null, true, null, null, null, null, null), viewerReq)
                .getStatusCode().value());
        assertEquals(403, controller.enable(scriptId, viewerReq).getStatusCode().value());
        assertEquals(403, controller.disable(scriptId, viewerReq).getStatusCode().value());
    }

    @Test
    void v251MigrationDefinesTablesAppendOnlyTriggerAuditTriggersRedactionAndModuleGrants() throws Exception {
        String sql;
        try (var in = getClass().getClassLoader().getResourceAsStream("db/migration/V251__script_execution_slice1.sql")) {
            assertNotNull(in, "V251__script_execution_slice1.sql must exist on the classpath");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS script ("));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS script_version ("));
        assertTrue(sql.contains("kind IN ('sh', 'bash', 'py', 'jar', 'js', 'mjs', 'pl', 'rb', 'ps1', 'bin')"));
        assertTrue(sql.contains("timeout_s BETWEEN 1 AND 3600"));
        assertTrue(sql.contains("trg_guard_script_version_append_only"));
        assertTrue(sql.contains("trg_audit_script"));
        assertTrue(sql.contains("trg_audit_script_version"));
        assertTrue(sql.contains("('script', 'egress', 2,"));
        assertTrue(sql.contains("('script_version', 'wrapped_data_key', 1,"));
        assertTrue(sql.contains("GRANT SELECT, INSERT, UPDATE ON script TO ui2_app;"));
        assertTrue(sql.contains("GRANT SELECT, INSERT ON script_version TO ui2_app;"));
        assertFalse(sql.toLowerCase( java.util.Locale.ROOT).contains("body text"), "No plaintext body column may exist in V251");
    }

    private static MockHttpServletRequest authedRequest(String actor, boolean replayViewer) {
        var req = new MockHttpServletRequest();
        req.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, actor);
        req.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, replayViewer);
        return req;
    }

    private static final class InMemoryScriptRepository implements ScriptRepository {
        private final Map<String, ScriptRecord> scripts = new LinkedHashMap<>();
        private final Map<String, List<ScriptVersionRecord>> versions = new LinkedHashMap<>();

        @Override
        public List<ScriptRecord> findAll() {
            return List.copyOf(scripts.values());
        }

        @Override
        public Optional<ScriptRecord> findById(String scriptId) {
            return Optional.ofNullable(scripts.get(scriptId));
        }

        @Override
        public Optional<ScriptRecord> findByName(String name) {
            return scripts.values().stream().filter(s -> s.name().equals(name)).findFirst();
        }

        @Override
        public List<ScriptVersionRecord> findVersions(String scriptId) {
            List<ScriptVersionRecord> list = new ArrayList<>(versions.getOrDefault(scriptId, List.of()));
            list.sort((a, b) -> Integer.compare(b.version(), a.version()));
            return List.copyOf(list);
        }

        @Override
        public Optional<ScriptVersionRecord> findVersion(String scriptId, int version) {
            return versions.getOrDefault(scriptId, List.of()).stream().filter(v -> v.version() == version).findFirst();
        }

        @Override
        public ScriptRecord create(ScriptRecord script, ScriptVersionRecord initialVersion, String actorFingerprint, String actionId) {
            scripts.put(script.scriptId(), script);
            versions.put(script.scriptId(), new ArrayList<>(List.of(initialVersion)));
            return script;
        }

        @Override
        public ScriptRecord appendVersion(String scriptId, ScriptVersionRecord version, String actorFingerprint, String actionId) {
            ScriptRecord existing = scripts.get(scriptId);
            versions.computeIfAbsent(scriptId, k -> new ArrayList<>()).add(version);
            ScriptRecord updated = new ScriptRecord(
                    existing.scriptId(), existing.name(), existing.kind(), version.version(), existing.enabled(),
                    existing.schedule(), existing.timeoutSeconds(), existing.egress(), version.egressDeviceTargets(),
                    version.credentialRefs(), existing.notifyMode(), existing.runAs(),
                    existing.createdByActorFingerprint(), existing.createdAt(), version.createdAt());
            scripts.put(scriptId, updated);
            return updated;
        }

        @Override
        public Optional<ScriptRecord> updateSettings(String scriptId, boolean enabled, Optional<String> schedule,
                int timeoutSeconds, List<String> egress, List<String> egressDeviceTargets, List<String> credentialRefs,
                String notifyMode, String runAs, String actorFingerprint, String actionId) {
            ScriptRecord existing = scripts.get(scriptId);
            if (existing == null) return Optional.empty();
            ScriptRecord updated = new ScriptRecord(
                    existing.scriptId(), existing.name(), existing.kind(), existing.currentVersion(), enabled,
                    schedule, timeoutSeconds, egress, egressDeviceTargets, credentialRefs, notifyMode, runAs,
                    existing.createdByActorFingerprint(), existing.createdAt(), FIXED_NOW);
            scripts.put(scriptId, updated);
            return Optional.of(updated);
        }

        @Override
        public Optional<ScriptRecord> setEnabled(String scriptId, boolean enabled, String actorFingerprint, String actionId) {
            ScriptRecord existing = scripts.get(scriptId);
            if (existing == null) return Optional.empty();
            ScriptRecord updated = new ScriptRecord(
                    existing.scriptId(), existing.name(), existing.kind(), existing.currentVersion(), enabled,
                    existing.schedule(), existing.timeoutSeconds(), existing.egress(), existing.egressDeviceTargets(),
                    existing.credentialRefs(), existing.notifyMode(), existing.runAs(),
                    existing.createdByActorFingerprint(), existing.createdAt(), FIXED_NOW);
            scripts.put(scriptId, updated);
            return Optional.of(updated);
        }
    }

    private static final class StubDeviceRepository implements DeviceRepository {
        private final Map<String, String> byAddress;

        StubDeviceRepository(Map<String, String> byAddress) {
            this.byAddress = byAddress;
        }

        @Override
        public Optional<String> findDeviceIdByEndpointAddress(String addressRef) {
            return Optional.ofNullable(byAddress.get(addressRef));
        }

        @Override
        public List<DeviceSummaryRecord> listAll() {
            List<DeviceSummaryRecord> out = new ArrayList<>();
            for (Map.Entry<String, String> e : byAddress.entrySet()) {
                out.add(new DeviceSummaryRecord(
                        e.getValue(), "gateway", "check_point", DeviceEnrollmentState.ENROLLED,
                        Optional.of("fw-" + e.getValue()), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(e.getKey()), Optional.empty(), false, false));
            }
            return out;
        }

        @Override
        public Optional<DeviceRecord> find(String deviceId) {
            return Optional.empty();
        }

        @Override
        public Optional<EndpointRecord> findEndpoint(String endpointId) {
            return Optional.empty();
        }

        @Override
        public Optional<EndpointRecord> findEndpointByDeviceId(String deviceId) {
            return Optional.empty();
        }

        @Override
        public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState, DeviceEnrollmentState toState,
                String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean recordConfirmSuccess(String deviceId, DeviceConfirmFacts facts, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId) {
            return Optional.empty();
        }
    }
}
