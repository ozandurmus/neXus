package com.securityexpert.nexus.ui2.platform;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared constants and target-reference codec for the {@code script_run} job type
 * ({@code SCRIPT_EXECUTION_CONTRACT.md} sections 3.2-3.4) and per-run {@code nexus-cli} session actors.
 */
public final class ScriptExecutionJob {

    /** Job type and capability ID for a script execution run. */
    public static final String CAPABILITY = "script_run";

    /** Target kind stored in {@code jobs.target_kind}. */
    public static final String TARGET_KIND = "script";

    /** Module name in {@code module_runtime_control} and worker ID prefix ({@code script_runner-<uuid>}). */
    public static final String MODULE = "script_runner";

    /** Prefix for per-run {@code nexus-cli} session actor fingerprints. */
    public static final String SESSION_ACTOR_PREFIX = "script_session:";

    /** Maximum captured bytes per stream (stdout and stderr each capped at 256 KiB). */
    public static final int MAX_STREAM_BYTES = 256 * 1024;

    /** Maximum inline tail stored on {@code script_run.output_tail} (4 KiB). */
    public static final int MAX_TAIL_BYTES = 4 * 1024;

    /**
     * The roles a script may run as ({@code SCRIPT_EXECUTION_CONTRACT.md} Amendment 2026-11-18 (b) item 9): a role token
     * from this list only, never a user name and never {@code role:security_admin}. V258 enforces the same list with a
     * CHECK on {@code script.run_as} and {@code script_run.run_as}; the first entry is the default.
     */
    public static final List<String> RUN_AS_ALLOWLIST =
            List.of(RoleToken.OPERATOR, RoleToken.VIEWER, RoleToken.REPLAY_VIEWER);

    /** Default {@code run_as}. */
    public static final String DEFAULT_RUN_AS = RoleToken.OPERATOR;

    private ScriptExecutionJob() {
    }

    public record TargetRef(String scriptId, int version) {
        public TargetRef {
            Objects.requireNonNull(scriptId, "scriptId");
            if (scriptId.isBlank()) {
                throw new IllegalArgumentException("scriptId must not be blank");
            }
            if (version < 1) {
                throw new IllegalArgumentException("version must be >= 1");
            }
        }
    }

    /** Formats {@code <script_id>@<version>} for {@code jobs.target_ref}. */
    public static String formatTargetRef(String scriptId, int version) {
        TargetRef ref = new TargetRef(scriptId.strip(), version);
        return ref.scriptId() + "@" + ref.version();
    }

    /** Returns {@code true} when {@code runAs} is exactly one of {@link #RUN_AS_ALLOWLIST}. */
    public static boolean isAllowedRunAs(String runAs) {
        return runAs != null && RUN_AS_ALLOWLIST.contains(runAs.strip()) && runAs.strip().equals(runAs);
    }

    /**
     * Returns {@code true} when {@code runAs} carries mandatory AIView masking and machine-session write-block
     * semantics ({@link RoleToken#REPLAY_VIEWER}).
     */
    public static boolean isMachineRunAs(String runAs) {
        return runAs != null && RoleToken.REPLAY_VIEWER.equals(runAs.strip());
    }

    /**
     * Returns {@code true} unless {@code runAs} is on {@link #RUN_AS_ALLOWLIST}: a user name, an unknown role, a blank
     * value and {@code role:security_admin} are all forbidden.
     */
    public static boolean isForbiddenRunAs(String runAs) {
        if (runAs == null || runAs.isBlank()) {
            return true;
        }
        String normalized = runAs.strip().toLowerCase(Locale.ROOT);
        if (RoleToken.SECURITY_ADMIN.equals(normalized) || "security_admin".equals(normalized)) {
            return true;
        }
        return !isAllowedRunAs(runAs.strip());
    }

    /** Formats the actor fingerprint for a per-run {@code nexus-cli} session. */
    public static String formatSessionActor(String scriptId, String runId, String runAs) {
        if (isForbiddenRunAs(runAs)) {
            throw new IllegalArgumentException("script session run_as must be one of " + RUN_AS_ALLOWLIST);
        }
        return SESSION_ACTOR_PREFIX + Objects.requireNonNull(scriptId, "scriptId").strip()
                + ":" + Objects.requireNonNull(runId, "runId").strip()
                + ":" + runAs.strip();
    }

    /**
     * Extracts the effective {@code run_as} role from a per-run script session actor fingerprint, returning
     * {@link Optional#empty()} if {@code actorFingerprint} is not a script session or its role is not on
     * {@link #RUN_AS_ALLOWLIST}.
     */
    public static Optional<String> extractSessionRunAs(String actorFingerprint) {
        if (actorFingerprint == null || !actorFingerprint.startsWith(SESSION_ACTOR_PREFIX)) {
            return Optional.empty();
        }
        String rest = actorFingerprint.substring(SESSION_ACTOR_PREFIX.length());
        int firstColon = rest.indexOf(':');
        if (firstColon <= 0) {
            return Optional.empty();
        }
        int secondColon = rest.indexOf(':', firstColon + 1);
        if (secondColon <= firstColon + 1 || secondColon == rest.length() - 1) {
            return Optional.empty();
        }
        String runAs = rest.substring(secondColon + 1).strip();
        if (isForbiddenRunAs(runAs)) {
            return Optional.empty();
        }
        return Optional.of(runAs);
    }
}
