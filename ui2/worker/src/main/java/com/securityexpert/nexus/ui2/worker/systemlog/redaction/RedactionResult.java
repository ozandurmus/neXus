package com.securityexpert.nexus.ui2.worker.systemlog.redaction;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.securityexpert.nexus.ui2.worker.systemlog.SourceFamily;

/**
 * What {@link LogRedactor#redact} returns.
 *
 * @param family the source family the chunk belongs to
 * @param bytes the redacted chunk; line terminators ({@code LF}, {@code CRLF}) and every byte outside a secret value
 *        are exactly those of the input
 * @param redactionCount the number of values replaced by {@value LogRedactor#REDACTED} (stored as the chunk's
 *        {@code redaction_count}); the sum of {@link #countsByRule()}
 * @param countsByRule replacements per rule id, only rules that fired; never contains text from the log
 * @param ruleSetId the version of the rule set that produced this result ({@link LogRedactor#RULE_SET_ID}); stored
 *        with the chunk so a later rule change stays traceable
 */
public record RedactionResult(SourceFamily family, byte[] bytes, int redactionCount, Map<String, Integer> countsByRule,
        String ruleSetId) {

    public RedactionResult {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(ruleSetId, "ruleSetId");
        countsByRule = Map.copyOf(new TreeMap<>(countsByRule));
    }

    /** True when at least one value was replaced. */
    public boolean changed() {
        return redactionCount > 0;
    }
}
