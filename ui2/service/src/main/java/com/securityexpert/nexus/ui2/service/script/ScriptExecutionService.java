package com.securityexpert.nexus.ui2.service.script;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptSecretFinding;
import com.securityexpert.nexus.ui2.persistence.script.ScriptVersionRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * Application service for Script Execution slice 1 ({@code SCRIPT_EXECUTION_CONTRACT.md} sections 3.1, 3.6, 3.7, 7
 * and Amendment 2026-11-18 in {@code PO_DECISION_RECORD_2026_11_18_SCRIPT_EXECUTION_NO_REFUSALS.md}).
 *
 * <ul>
 *   <li>Supported kinds: {@code sh}, {@code bash}, {@code py}, {@code jar}, {@code js}, {@code mjs}, {@code pl},
 *       {@code rb}, {@code ps1}, {@code bin}. {@code .bat} and {@code .cmd} are refused with
 *       {@link #BAT_CMD_REFUSAL_REASON}.</li>
 *   <li>Every uploaded body is size-checked (at most {@link #MAX_SCRIPT_BYTES}), UTF-8 validated for text kinds,
 *       scanned with {@link ScriptSecretScanner} (producing a warning and storing {@code secret_findings}, never a
 *       refusal), and written encrypted at rest to {@link ArtefactStore} under class {@link ArtefactClass#SCRIPT_BODY}.
 *       Plaintext script bodies are never persisted in the database, logged, or returned by any read method.</li>
 *   <li>Declared {@code egress} entries ({@code host:port}) are correlated against stored enrolled device management
 *       addresses; matches are recorded in {@code egress_device_targets} and surfaced as a warning, never refused.</li>
 *   <li>Declared {@code credential_refs} are validated against {@link CredentialReferenceRepository#exists(String)}.</li>
 * </ul>
 */
public final class ScriptExecutionService {

    /** Maximum script upload size: 10 MiB (10,485,760 bytes). */
    public static final int MAX_SCRIPT_BYTES = 10 * 1024 * 1024;
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;
    public static final int MAX_TIMEOUT_SECONDS = 3600;
    public static final String DEFAULT_NOTIFY = "none";
    public static final String DEFAULT_RUN_AS = RoleToken.OPERATOR;
    public static final String BAT_CMD_REFUSAL_REASON = "cannot run on the Linux runner; use PowerShell (.ps1)";

    public static final Set<String> SUPPORTED_KINDS = Set.of(
            "sh", "bash", "py", "jar", "js", "mjs", "pl", "rb", "ps1", "bin");
    public static final Set<String> TEXT_KINDS = Set.of(
            "sh", "bash", "py", "js", "mjs", "pl", "rb", "ps1");
    public static final Set<String> BINARY_KINDS = Set.of("jar", "bin");
    public static final Set<String> NOTIFY_MODES = Set.of("syslog", "smtp", "both", "none");

    public record ScriptDetails(
            ScriptRecord script,
            List<ScriptVersionRecord> versions,
            List<Instant> nextFiveFires,
            List<String> warnings) {
    }

    public record CreateScriptCommand(
            String name,
            String fileName,
            String kind,
            byte[] body,
            String schedule,
            Boolean enabled,
            Integer timeoutSeconds,
            List<String> egress,
            List<String> credentialRefs,
            String notifyMode,
            String runAs) {
    }

    public record NewVersionCommand(
            String fileName,
            String kind,
            byte[] body,
            List<String> credentialRefs) {
    }

    public record UpdateSettingsCommand(
            Optional<String> schedule,
            boolean clearSchedule,
            Optional<Boolean> enabled,
            Optional<Integer> timeoutSeconds,
            Optional<List<String>> egress,
            Optional<List<String>> credentialRefs,
            Optional<String> notifyMode,
            Optional<String> runAs) {
    }

    public sealed interface Outcome {
        record Ok(ScriptDetails details) implements Outcome {
        }

        record Refused(int status, String code, String message) implements Outcome {
        }
    }

    private final ScriptRepository scriptRepository;
    private final CredentialReferenceRepository credentialReferences;
    private final DeviceRepository deviceRepository;
    private final ArtefactStore artefactStore;
    private final Clock clock;
    private final int maxScriptBytes;

    @Autowired
    public ScriptExecutionService(
            ScriptRepository scriptRepository,
            CredentialReferenceRepository credentialReferences,
            DeviceRepository deviceRepository,
            ArtefactStore artefactStore) {
        this(scriptRepository, credentialReferences, deviceRepository, artefactStore, Clock.systemUTC(), MAX_SCRIPT_BYTES);
    }

    public ScriptExecutionService(
            ScriptRepository scriptRepository,
            CredentialReferenceRepository credentialReferences,
            DeviceRepository deviceRepository,
            ArtefactStore artefactStore,
            Clock clock,
            int maxScriptBytes) {
        this.scriptRepository = Objects.requireNonNull(scriptRepository, "scriptRepository");
        this.credentialReferences = Objects.requireNonNull(credentialReferences, "credentialReferences");
        this.deviceRepository = deviceRepository;
        this.artefactStore = artefactStore;
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxScriptBytes <= 0 || maxScriptBytes > MAX_SCRIPT_BYTES) {
            throw new IllegalArgumentException("maxScriptBytes must be between 1 and " + MAX_SCRIPT_BYTES);
        }
        this.maxScriptBytes = maxScriptBytes;
    }

    public List<ScriptDetails> list() {
        Instant now = clock.instant();
        List<ScriptRecord> scripts = scriptRepository.findAll();
        List<ScriptDetails> out = new ArrayList<>(scripts.size());
        for (ScriptRecord script : scripts) {
            List<ScriptVersionRecord> versions = scriptRepository.findVersions(script.scriptId());
            out.add(toDetails(script, versions, now));
        }
        return List.copyOf(out);
    }

    public Optional<ScriptDetails> findById(String scriptId) {
        if (scriptId == null || scriptId.isBlank()) {
            return Optional.empty();
        }
        return scriptRepository.findById(scriptId.strip()).map(script -> {
            List<ScriptVersionRecord> versions = scriptRepository.findVersions(script.scriptId());
            return toDetails(script, versions, clock.instant());
        });
    }

    public Outcome create(CreateScriptCommand command, String actorFingerprint) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return new Outcome.Refused(403, "AUTHENTICATION_REQUIRED", "An authenticated security_admin is required");
        }
        if (command == null) {
            return new Outcome.Refused(400, "BODY_REQUIRED", "Request body is required");
        }
        Outcome.Refused kindOrBodyProblem = validateKindAndBody(command.fileName(), command.name(), command.kind(), command.body());
        if (kindOrBodyProblem != null) {
            return kindOrBodyProblem;
        }
        String resolvedKind = resolveKindInternal(command.fileName(), command.name(), command.kind()).orElseThrow();
        String resolvedName = resolveScriptName(command.name(), command.fileName());
        if (resolvedName == null || resolvedName.isBlank() || resolvedName.length() > 128
                || !resolvedName.matches("[A-Za-z0-9._-]+")) {
            return new Outcome.Refused(400, "INVALID_SCRIPT_NAME",
                    "Script name must be 1..128 characters of letters, digits, '.', '_' or '-'");
        }
        if (scriptRepository.findByName(resolvedName).isPresent()) {
            return new Outcome.Refused(409, "SCRIPT_NAME_EXISTS", "A script named '" + resolvedName + "' already exists");
        }

        Optional<String> schedule = Optional.empty();
        if (command.schedule() != null && !command.schedule().isBlank()) {
            schedule = ScriptSchedule.normalizeFiveFieldCron(command.schedule());
            if (schedule.isEmpty()) {
                return new Outcome.Refused(400, "INVALID_SCHEDULE",
                        "schedule must be a valid five-field cron expression (minute hour day-of-month month day-of-week)");
            }
        }

        int timeoutSeconds = command.timeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS : command.timeoutSeconds();
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            return new Outcome.Refused(400, "INVALID_TIMEOUT",
                    "timeout_s must be between 1 and " + MAX_TIMEOUT_SECONDS + " seconds");
        }

        String notifyMode = command.notifyMode() == null || command.notifyMode().isBlank()
                ? DEFAULT_NOTIFY
                : command.notifyMode().strip().toLowerCase(Locale.ROOT);
        if (!NOTIFY_MODES.contains(notifyMode)) {
            return new Outcome.Refused(400, "INVALID_NOTIFY", "notify must be one of: syslog, smtp, both, none");
        }

        String runAs = command.runAs() == null || command.runAs().isBlank()
                ? DEFAULT_RUN_AS
                : command.runAs().strip();
        if ( isForbiddenRunAs(runAs)) {
            return new Outcome.Refused(400, "INVALID_RUN_AS", "script run_as cannot be role:security_admin");
        }

        EgressValidation egressValidation = validateAndCorrelateEgress(command.egress());
        if (egressValidation.refusal != null) {
            return egressValidation.refusal;
        }

        CredentialRefsValidation credValidation = validateCredentialRefs(command.credentialRefs());
        if (credValidation.refusal != null) {
            return credValidation.refusal;
        }

        if (artefactStore == null) {
            return new Outcome.Refused(503, "STORE_UNAVAILABLE",
                    "the service has no artefact store mounted (UI2_ARTEFACT_STORE_KEY_FILE / UI2_ARTEFACT_STORE_ROOT)");
        }

        boolean textKind = TEXT_KINDS.contains(resolvedKind);
        List<ScriptSecretFinding> findings = ScriptSecretScanner.scan(command.body(), textKind);

        String scriptId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        ArtefactStore.ArtefactMetadata storedMeta;
        try (ArtefactStore.ArtefactHandle handle = artefactStore.open(
                scriptId, scriptId + "@v1", ArtefactClass.SCRIPT_BODY, false)) {
            handle.sink().write(command.body());
            storedMeta = handle.finish();
        } catch (ArtefactStore.QuotaExceededException e) {
            return new Outcome.Refused(507, "ARTEFACT_STORE_QUOTA_EXCEEDED", e.getMessage());
        } catch (IOException e) {
            return new Outcome.Refused(500, "ARTEFACT_STORE_WRITE_FAILED", "Could not store encrypted script body");
        }

        boolean enabled = Boolean.TRUE.equals(command.enabled());
        ScriptRecord script = new ScriptRecord(
                scriptId,
                resolvedName,
                resolvedKind,
                1,
                enabled,
                schedule,
                timeoutSeconds,
                egressValidation.normalizedEgress,
                egressValidation.deviceTargets,
                credValidation.normalizedRefs,
                notifyMode,
                runAs,
                actorFingerprint,
                now,
                now);
        ScriptVersionRecord initialVersion = new ScriptVersionRecord(
                scriptId,
                1,
                storedMeta.plaintextSha256(),
                storedMeta.plaintextBytes(),
                storedMeta.ref().value(),
                storedMeta.keyId(),
                storedMeta.wrappedDataKey(),
                findings,
                credValidation.normalizedRefs,
                egressValidation.deviceTargets,
                actorFingerprint,
                now);
        ScriptRecord saved = scriptRepository.create(script, initialVersion, actorFingerprint, ActionRegistry.SCRIPT_WRITE);
        List<ScriptVersionRecord> versions = scriptRepository.findVersions(scriptId);
        return new Outcome.Ok(toDetails(saved, versions, now));
    }

    public Outcome addVersion(String scriptId, NewVersionCommand command, String actorFingerprint) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return new Outcome.Refused(403, "AUTHENTICATION_REQUIRED", "An authenticated security_admin is required");
        }
        if (scriptId == null || scriptId.isBlank()) {
            return new Outcome.Refused(400, "INVALID_SCRIPT_ID", "script_id is required");
        }
        Optional<ScriptRecord> maybeExisting = scriptRepository.findById(scriptId.strip());
        if (maybeExisting.isEmpty()) {
            return new Outcome.Refused(404, "SCRIPT_NOT_FOUND", "No script with id '" + scriptId + "'");
        }
        ScriptRecord existing = maybeExisting.get();
        if (command == null) {
            return new Outcome.Refused(400, "BODY_REQUIRED", "Request body is required");
        }
        String effectiveKind = (command.kind() == null || command.kind().isBlank()) ? existing.kind() : command.kind();
        Outcome.Refused kindOrBodyProblem = validateKindAndBody(command.fileName(), null, effectiveKind, command.body());
        if (kindOrBodyProblem != null) {
            return kindOrBodyProblem;
        }
        String resolvedKind = resolveKindInternal(command.fileName(), null, effectiveKind).orElseThrow();
        if (!existing.kind().equals(resolvedKind)) {
            return new Outcome.Refused(400, "KIND_MISMATCH",
                    "Uploaded version kind '" + resolvedKind + "' does not match script kind '" + existing.kind() + "'");
        }

        List<String> credentialRefs = existing.credentialRefs();
        if (command.credentialRefs() != null) {
            CredentialRefsValidation credValidation = validateCredentialRefs(command.credentialRefs());
            if (credValidation.refusal != null) {
                return credValidation.refusal;
            }
            credentialRefs = credValidation.normalizedRefs;
        }

        if (artefactStore == null) {
            return new Outcome.Refused(503, "STORE_UNAVAILABLE",
                    "the service has no artefact store mounted (UI2_ARTEFACT_STORE_KEY_FILE / UI2_ARTEFACT_STORE_ROOT)");
        }

        boolean textKind = TEXT_KINDS.contains(existing.kind());
        List<ScriptSecretFinding> findings = ScriptSecretScanner.scan(command.body(), textKind);

        int nextVersion = existing.currentVersion() + 1;
        Instant now = clock.instant();
        ArtefactStore.ArtefactMetadata storedMeta;
        try (ArtefactStore.ArtefactHandle handle = artefactStore.open(
                existing.scriptId(), existing.scriptId() + "@v" + nextVersion, ArtefactClass.SCRIPT_BODY, false)) {
            handle.sink().write(command.body());
            storedMeta = handle.finish();
        } catch (ArtefactStore.QuotaExceededException e) {
            return new Outcome.Refused(507, "ARTEFACT_STORE_QUOTA_EXCEEDED", e.getMessage());
        } catch (IOException e) {
            return new Outcome.Refused(500, "ARTEFACT_STORE_WRITE_FAILED", "Could not store encrypted script body");
        }

        ScriptVersionRecord versionRecord = new ScriptVersionRecord(
                existing.scriptId(),
                nextVersion,
                storedMeta.plaintextSha256(),
                storedMeta.plaintextBytes(),
                storedMeta.ref().value(),
                storedMeta.keyId(),
                storedMeta.wrappedDataKey(),
                findings,
                credentialRefs,
                existing.egressDeviceTargets(),
                actorFingerprint,
                now);
        ScriptRecord updated = scriptRepository.appendVersion(
                existing.scriptId(), versionRecord, actorFingerprint, ActionRegistry.SCRIPT_WRITE);
        List<ScriptVersionRecord> versions = scriptRepository.findVersions(existing.scriptId());
        return new Outcome.Ok(toDetails(updated, versions, now));
    }

    public Outcome updateSettings(String scriptId, UpdateSettingsCommand command, String actorFingerprint) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return new Outcome.Refused(403, "AUTHENTICATION_REQUIRED", "An authenticated security_admin is required");
        }
        if (scriptId == null || scriptId.isBlank()) {
            return new Outcome.Refused(400, "INVALID_SCRIPT_ID", "script_id is required");
        }
        Optional<ScriptRecord> maybeExisting = scriptRepository.findById(scriptId.strip());
        if (maybeExisting.isEmpty()) {
            return new Outcome.Refused(404, "SCRIPT_NOT_FOUND", "No script with id '" + scriptId + "'");
        }
        ScriptRecord existing = maybeExisting.get();
        if (command == null) {
            return new Outcome.Refused(400, "BODY_REQUIRED", "Request body is required");
        }

        Optional<String> schedule = existing.schedule();
        if (command.clearSchedule()) {
            schedule = Optional.empty();
        } else if (command.schedule().isPresent()) {
            String rawSchedule = command.schedule().get();
            if (rawSchedule == null || rawSchedule.isBlank()) {
                schedule = Optional.empty();
            } else {
                schedule = ScriptSchedule.normalizeFiveFieldCron(rawSchedule);
                if (schedule.isEmpty()) {
                    return new Outcome.Refused(400, "INVALID_SCHEDULE",
                            "schedule must be a valid five-field cron expression (minute hour day-of-month month day-of-week)");
                }
            }
        }

        boolean enabled = command.enabled().orElse(existing.enabled());

        int timeoutSeconds = command.timeoutSeconds().orElse(existing.timeoutSeconds());
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            return new Outcome.Refused(400, "INVALID_TIMEOUT",
                    "timeout_s must be between 1 and " + MAX_TIMEOUT_SECONDS + " seconds");
        }

        List<String> egress = existing.egress();
        List<String> egressDeviceTargets = existing.egressDeviceTargets();
        if (command.egress().isPresent()) {
            EgressValidation egressValidation = validateAndCorrelateEgress(command.egress().get());
            if (egressValidation.refusal != null) {
                return egressValidation.refusal;
            }
            egress = egressValidation.normalizedEgress;
            egressDeviceTargets = egressValidation.deviceTargets;
        }

        List<String> credentialRefs = existing.credentialRefs();
        if (command.credentialRefs().isPresent()) {
            CredentialRefsValidation credValidation = validateCredentialRefs(command.credentialRefs().get());
            if (credValidation.refusal != null) {
                return credValidation.refusal;
            }
            credentialRefs = credValidation.normalizedRefs;
        }

        String notifyMode = existing.notifyMode();
        if (command.notifyMode().isPresent()) {
            notifyMode = command.notifyMode().get().strip().toLowerCase(Locale.ROOT);
            if (!NOTIFY_MODES.contains(notifyMode)) {
                return new Outcome.Refused(400, "INVALID_NOTIFY", "notify must be one of: syslog, smtp, both, none");
            }
        }

        String runAs = existing.runAs();
        if (command.runAs().isPresent()) {
            runAs = command.runAs().get().strip();
            if (runAs.isEmpty() || isForbiddenRunAs(runAs)) {
                return new Outcome.Refused(400, "INVALID_RUN_AS", "script run_as cannot be empty or role:security_admin");
            }
        }

        Optional<ScriptRecord> updated = scriptRepository.updateSettings(
                existing.scriptId(),
                enabled,
                schedule,
                timeoutSeconds,
                egress,
                egressDeviceTargets,
                credentialRefs,
                notifyMode,
                runAs,
                actorFingerprint,
                ActionRegistry.SCRIPT_WRITE);
        if (updated.isEmpty()) {
            return new Outcome.Refused(404, "SCRIPT_NOT_FOUND", "No script with id '" + scriptId + "'");
        }
        List<ScriptVersionRecord> versions = scriptRepository.findVersions(existing.scriptId());
        return new Outcome.Ok(toDetails(updated.get(), versions, clock.instant()));
    }

    public Outcome setEnabled(String scriptId, boolean enabled, String actorFingerprint) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return new Outcome.Refused(403, "AUTHENTICATION_REQUIRED", "An authenticated security_admin is required");
        }
        if (scriptId == null || scriptId.isBlank()) {
            return new Outcome.Refused(400, "INVALID_SCRIPT_ID", "script_id is required");
        }
        Optional<ScriptRecord> updated = scriptRepository.setEnabled(
                scriptId.strip(), enabled, actorFingerprint, ActionRegistry.SCRIPT_WRITE);
        if (updated.isEmpty()) {
            return new Outcome.Refused(404, "SCRIPT_NOT_FOUND", "No script with id '" + scriptId + "'");
        }
        List<ScriptVersionRecord> versions = scriptRepository.findVersions(scriptId.strip());
        return new Outcome.Ok(toDetails(updated.get(), versions, clock.instant()));
    }

    private Outcome.Refused validateKindAndBody(String fileName, String scriptName, String explicitKind, byte[] body) {
        if (isBatOrCmd(fileName) || isBatOrCmd(scriptName) || isBatOrCmdKind(explicitKind)) {
            return new Outcome.Refused(400, "UNSUPPORTED_SCRIPT_KIND", BAT_CMD_REFUSAL_REASON);
        }
        Optional<String> fromFile = extensionKind(fileName).or(() -> extensionKind(scriptName));
        Optional<String> fromExplicit = normalizeKind(explicitKind);
        if (explicitKind != null && !explicitKind.isBlank() && fromExplicit.isEmpty()) {
            return new Outcome.Refused(400, "UNSUPPORTED_SCRIPT_KIND",
                    "Unsupported script kind '" + explicitKind + "'; supported kinds: " + SUPPORTED_KINDS);
        }
        if (fileName != null && !fileName.isBlank() && hasExtension(fileName) && fromFile.isEmpty()) {
            return new Outcome.Refused(400, "UNSUPPORTED_SCRIPT_KIND",
                    "Unsupported script file extension in '" + fileName + "'");
        }
        if (fromFile.isPresent() && fromExplicit.isPresent() && !fromFile.get().equals(fromExplicit.get())) {
            return new Outcome.Refused(400, "KIND_EXTENSION_MISMATCH",
                    "File extension kind '" + fromFile.get() + "' does not match explicit kind '" + fromExplicit.get() + "'");
        }
        Optional<String> resolved = fromFile.or(() -> fromExplicit);
        if (resolved.isEmpty()) {
            return new Outcome.Refused(400, "UNSUPPORTED_SCRIPT_KIND",
                    "Could not determine script kind; supply a supported file extension or explicit kind");
        }
        if (body == null || body.length == 0) {
            return new Outcome.Refused(400, "EMPTY_SCRIPT_BODY", "Script body must not be empty");
        }
        if (body.length > maxScriptBytes) {
            return new Outcome.Refused(400, "SCRIPT_TOO_LARGE",
                    "Script body size (" + body.length + " bytes) exceeds limit of " + maxScriptBytes + " bytes");
        }
        if (TEXT_KINDS.contains(resolved.get()) && !isValidUtf8Text(body)) {
            return new Outcome.Refused(400, "INVALID_UTF8",
                    "Script body for text kind '" + resolved.get() + "' must be valid UTF-8 without NUL bytes");
        }
        return null;
    }

    private static Optional<String> resolveKindInternal(String fileName, String scriptName, String explicitKind) {
        return extensionKind(fileName).or(() -> extensionKind(scriptName)).or(() -> normalizeKind(explicitKind));
    }

    private static boolean isBatOrCmd(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.strip().toLowerCase(Locale.ROOT);
        return lower.endsWith(".bat") || lower.endsWith(".cmd");
    }

    private static boolean isBatOrCmdKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return false;
        }
        String lower = kind.strip().toLowerCase(Locale.ROOT);
        if (lower.startsWith(".")) {
            lower = lower.substring(1);
        }
        return "bat".equals(lower) || "cmd".equals(lower);
    }

    private static boolean hasExtension(String fileName) {
        String base = simpleFileName(fileName);
        int dot = base.lastIndexOf('.');
        return dot > 0 && dot < base.length() - 1;
    }

    private static Optional<String> extensionKind(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        String base = simpleFileName(fileName);
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot == base.length() - 1) {
            return Optional.empty();
        }
        String ext = base.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_KINDS.contains(ext) ? Optional.of(ext) : Optional.empty();
    }

    private static Optional<String> normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return Optional.empty();
        }
        String lower = kind.strip().toLowerCase(Locale.ROOT);
        if (lower.startsWith(".")) {
            lower = lower.substring(1);
        }
        return SUPPORTED_KINDS.contains(lower) ? Optional.of(lower) : Optional.empty();
    }

    private static String resolveScriptName(String explicitName, String fileName) {
        if (explicitName != null && !explicitName.isBlank()) {
            return explicitName.strip();
        }
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String base = simpleFileName(fileName);
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private static String simpleFileName(String path) {
        String normalized = path.strip().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static boolean isValidUtf8Text(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean isForbiddenRunAs(String runAs) {
        String lower = runAs.strip().toLowerCase(Locale.ROOT);
        return "role:security_admin".equals(lower) || "security_admin".equals(lower);
    }

    private record EgressValidation(
            List<String> normalizedEgress,
            List<String> deviceTargets,
            Outcome.Refused refusal) {
    }

    private EgressValidation validateAndCorrelateEgress(List<String> rawEgress) {
        if (rawEgress == null || rawEgress.isEmpty()) {
            return new EgressValidation(List.of(), List.of(), null);
        }
        Set<String> normalized = new LinkedHashSet<>();
        List<String> hosts = new ArrayList<>();
        for (String item : rawEgress) {
            if (item == null || item.isBlank()) {
                return new EgressValidation(List.of(), List.of(),
                        new Outcome.Refused(400, "INVALID_EGRESS", "egress entries must be non-empty host:port values"));
            }
            String trimmed = item.strip();
            ParsedHostPort parsed = parseHostPort(trimmed);
            if (parsed == null) {
                return new EgressValidation(List.of(), List.of(),
                        new Outcome.Refused(400, "INVALID_EGRESS",
                                "Invalid egress entry '" + trimmed + "'; expected host:port with port 1..65535"));
            }
            normalized.add(parsed.canonical());
            hosts.add(parsed.host());
        }
        Set<String> targets = new LinkedHashSet<>(scriptRepository.findEnrolledDeviceIdsByEndpointHosts(hosts));
        if (deviceRepository != null) {
            for (String host : hosts) {
                deviceRepository.findDeviceIdByEndpointAddress(host).ifPresent(targets::add);
            }
            for (DeviceSummaryRecord summary : deviceRepository.listAll()) {
                if (summary.enrollmentState() == DeviceEnrollmentState.DRAFT) {
                    continue;
                }
                summary.managementIp().ifPresent(ip -> {
                    String endpointHost = stripPortFromEndpoint(ip);
                    for (String host : hosts) {
                        if (endpointHost.equalsIgnoreCase(host) || ip.strip().equalsIgnoreCase(host)) {
                            targets.add(summary.deviceId());
                        }
                    }
                });
                deviceRepository.findEndpointByDeviceId(summary.deviceId()).map(EndpointRecord::addressRef).ifPresent(addr -> {
                    String endpointHost = stripPortFromEndpoint(addr);
                    for (String host : hosts) {
                        if (endpointHost.equalsIgnoreCase(host) || addr.strip().equalsIgnoreCase(host)) {
                            targets.add(summary.deviceId());
                        }
                    }
                });
            }
        }
        return new EgressValidation(List.copyOf(normalized), List.copyOf(targets), null);
    }

    private static String stripPortFromEndpoint(String addressRef) {
        String trimmed = addressRef.strip();
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            return close > 1 ? trimmed.substring(1, close) : trimmed;
        }
        int colon = trimmed.indexOf(':');
        int lastColon = trimmed.lastIndexOf(':');
        if (colon > 0 && colon == lastColon) {
            return trimmed.substring(0, colon).strip();
        }
        return trimmed;
    }

    private record ParsedHostPort(String host, int port, String canonical) {
    }

    private static ParsedHostPort parseHostPort(String entry) {
        if (entry == null || entry.isBlank() || entry.contains("/") || entry.contains(" ") || entry.contains("\t")) {
            return null;
        }
        String host;
        String portPart;
        if (entry.startsWith("[")) {
            int close = entry.indexOf(']');
            if (close <= 1 || close + 1 >= entry.length() || entry.charAt(close + 1) != ':') {
                return null;
            }
            host = entry.substring(1, close);
            portPart = entry.substring(close + 2);
            if (!host.matches("[0-9a-fA-F:]+")) {
                return null;
            }
        } else {
            int colon = entry.lastIndexOf(':');
            if (colon <= 0 || colon == entry.length() - 1 || entry.indexOf(':') != colon) {
                return null;
            }
            host = entry.substring(0, colon);
            portPart = entry.substring(colon + 1);
            if (!host.matches("[A-Za-z0-9.-]+") || host.startsWith(".") || host.endsWith(".") || host.contains("..")) {
                return null;
            }
        }
        int port;
        try {
            port = Integer.parseInt(portPart);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        String canonical = (entry.startsWith("[") ? "[" + normalizedHost + "]" : normalizedHost) + ":" + port;
        return new ParsedHostPort(normalizedHost, port, canonical);
    }

    private record CredentialRefsValidation(List<String> normalizedRefs, Outcome.Refused refusal) {
    }

    private CredentialRefsValidation validateCredentialRefs(List<String> rawRefs) {
        if (rawRefs == null || rawRefs.isEmpty()) {
            return new CredentialRefsValidation(List.of(), null);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String ref : rawRefs) {
            if (ref == null || ref.isBlank()) {
                return new CredentialRefsValidation(List.of(),
                        new Outcome.Refused(400, "INVALID_CREDENTIAL_REFERENCE",
                                "credential_refs entries must not be blank"));
            }
            String trimmed = ref.strip();
            if (!credentialReferences.exists(trimmed)) {
                return new CredentialRefsValidation(List.of(),
                        new Outcome.Refused(400, "UNKNOWN_CREDENTIAL_REFERENCE",
                                "Credential reference '" + trimmed + "' does not exist in the credential store"));
            }
            normalized.add(trimmed);
        }
        return new CredentialRefsValidation(List.copyOf(normalized), null);
    }

    private static ScriptDetails toDetails(ScriptRecord script, List<ScriptVersionRecord> versions, Instant now) {
        List<Instant> nextFires = script.schedule()
                .map(cron -> ScriptSchedule.nextFiveFires(cron, now))
                .orElse(List.of());
        List<String> warnings = new ArrayList<>();
        ScriptVersionRecord latest = versions.stream()
                .filter(v -> v.version() == script.currentVersion())
                .findFirst()
                .orElse(versions.isEmpty() ? null : versions.get(0));
        if (latest != null && !latest.secretFindings().isEmpty()) {
            warnings.add(ScriptSecretScanner.formatWarning(latest.secretFindings()));
        }
        if (!script.egressDeviceTargets().isEmpty()) {
            warnings.add("Declared egress targets enrolled device management address(es) "
                    + script.egressDeviceTargets()
                    + "; this is a direct path outside neXus command gates and will be recorded on each run.");
        }
        return new ScriptDetails(script, versions, nextFires, List.copyOf(warnings));
    }
}
