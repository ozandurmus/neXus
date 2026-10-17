package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.worker.systemlog.SourceFamily;
import com.securityexpert.nexus.ui2.worker.systemlog.redaction.LogRedactor;
import com.securityexpert.nexus.ui2.worker.systemlog.redaction.RedactionResult;

/**
 * The redaction call site of the Check Point collector (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 12: "secrets are
 * redacted on write, before encryption"). Every chunk passes through it after the line trim and before the artefact
 * sink; nothing else of a chunk is ever written.
 *
 * <p><b>Fail closed.</b> {@link #NOT_WIRED} is the default of the executor. While it is in place no chunk is stored:
 * a source that would read is recorded {@code UNKNOWN} with reason {@code REDACTOR_MISSING}, its cursor does not move,
 * and no read is sent for it (reading bytes that cannot be stored would only load the device).</p>
 */
@FunctionalInterface
public interface ChunkRedactor {

    /** The redacted bytes, the number of replaced values, the count per rule id, and the id of the rule set. */
    record Redaction(byte[] bytes, int redactionCount, Map<String, Integer> countsByRule, String ruleSetId) {
        public Redaction {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(ruleSetId, "ruleSetId");
            countsByRule = Map.copyOf(countsByRule);
            if (redactionCount < 0) throw new IllegalArgumentException("redactionCount");
        }
    }

    Redaction redact(SourceFamily family, byte[] data, int offset, int length);

    /** No redactor: the executor stores nothing (fail closed). Calling it is a programming error. */
    ChunkRedactor NOT_WIRED = (family, data, offset, length) -> {
        throw new IllegalStateException("REDACTOR_MISSING");
    };

    /** The real rules of the system-log programme ({@link LogRedactor}, rule set {@value LogRedactor#RULE_SET_ID}). */
    static ChunkRedactor of(LogRedactor redactor) {
        Objects.requireNonNull(redactor, "redactor");
        return (family, data, offset, length) -> {
            RedactionResult result = redactor.redact(family, data, offset, length);
            return new Redaction(result.bytes(), result.redactionCount(), result.countsByRule(), result.ruleSetId());
        };
    }
}
