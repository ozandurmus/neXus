/**
 * Redaction of Check Point text log chunks on write, before encryption (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md
 * section 12).
 *
 * <p>Pure functions over bytes: no I/O, no clock, no Spring, no database. The collector hands a chunk to
 * {@link com.securityexpert.nexus.ui2.worker.systemlog.redaction.LogRedactor} and stores the returned bytes, the
 * per-rule counts (their sum is the chunk's {@code redaction_count}) and the rule-set id. Only the secret value is
 * replaced, never the whole line.</p>
 */
package com.securityexpert.nexus.ui2.worker.systemlog.redaction;
