package com.securityexpert.nexus.ui2.persistence.device.configuration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One {@code device_configuration_run} row plus its child index/override
 * rows and the optional structural deviation summary, reassembled as one
 * unit (migration V16, 14G CG-1..CG-10; deviation summary migration V22,
 * 14I DV-2).
 *
 * <p>{@code canonicalHash} is CG-2's own distinction from {@code rawHash}:
 * for Check Point it is the SHA-256 over the sanitized view's {@code set}
 * lines only (the raw text's header timestamp changes every read and would
 * otherwise mark every run "changed"); for Palo Alto's structured XML reads
 * (no such header) it equals {@code rawHash}. {@code rawHash}/{@code
 * rawBytes} describe the untouched bytes the {@code artefactRef} points at
 * in the artefact store -- never the bytes themselves (AGENTS.md
 * raw-evidence law).</p>
 *
 * <p>{@code deviationSummary} is present only when {@code changeState} is
 * {@link ChangeState#CHANGED}; it is empty for {@code first_run} and
 * {@code unchanged} runs (DV-2 AC-4).</p>
 */
public record ConfigurationRun(
        String runId,
        String deviceId,
        String jobId,
        Instant collectedAt,
        String vendor,
        String readKind,
        boolean primary,
        String canonicalHash,
        String rawHash,
        long rawBytes,
        String artefactRef,
        int withheldLineCount,
        Optional<String> sanitizedText,
        String changeState,
        List<ConfigurationIndexEntry> index,
        List<ConfigurationOverride> overrides,
        Optional<ConfigurationDeviationSummary> deviationSummary) {

    public ConfigurationRun {
        sanitizedText = sanitizedText == null ? Optional.empty() : sanitizedText;
        index = index == null ? List.of() : List.copyOf(index);
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
        deviationSummary = deviationSummary == null ? Optional.empty() : deviationSummary;
    }
}
