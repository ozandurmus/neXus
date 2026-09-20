package com.securityexpert.nexus.ui2.jobs.failover.schedule;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * Canonical length-prefixed serialization envelope for scheduled failover authorizations.
 * Guarantees collision-free, deterministic payload framing over UTF-8 byte arrays.
 */
public record FailoverScheduleEnvelope(
    String scheduleId,
    String clusterRef,
    String vendor,
    String commandFamilyId,
    String actionKind,
    String signedMutationTarget,
    Instant windowStart,
    Instant windowEnd,
    int maxStartDelayMinutes,
    String requesterId,
    String approverId,
    String grantId,
    String baselineDigest,
    String clientNonce,
    String keyId,
    String algVersion
) {
    public static final String DOMAIN_SEPARATOR = "NEXUS_FAILOVER_SCHEDULE_V1";
    public static final String DEFAULT_ALG_VERSION = "HMAC_SHA256_V1";
    public static final String DEFAULT_KEY_ID = "k1";

    public FailoverScheduleEnvelope {
        Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(vendor, "vendor must not be null");
        Objects.requireNonNull(commandFamilyId, "commandFamilyId must not be null");
        Objects.requireNonNull(actionKind, "actionKind must not be null");
        Objects.requireNonNull(signedMutationTarget, "signedMutationTarget must not be null");
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        Objects.requireNonNull(windowEnd, "windowEnd must not be null");
        Objects.requireNonNull(requesterId, "requesterId must not be null");
        Objects.requireNonNull(approverId, "approverId must not be null");
        Objects.requireNonNull(grantId, "grantId must not be null");
        Objects.requireNonNull(baselineDigest, "baselineDigest must not be null");
        Objects.requireNonNull(clientNonce, "clientNonce must not be null");
        Objects.requireNonNull(keyId, "keyId must not be null");
        Objects.requireNonNull(algVersion, "algVersion must not be null");
    }

    /**
     * Serializes this envelope into a canonical length-prefixed UTF-8 byte stream.
     * Framing format: len:val|len:val|...
     * Null fields encoded as -1:NULL|
     */
    public byte[] toCanonicalBytes() {
        StringBuilder sb = new StringBuilder();
        appendField(sb, DOMAIN_SEPARATOR);
        appendField(sb, scheduleId);
        appendField(sb, clusterRef);
        appendField(sb, vendor);
        appendField(sb, commandFamilyId);
        appendField(sb, actionKind);
        appendField(sb, signedMutationTarget);
        appendField(sb, windowStart.toString());
        appendField(sb, windowEnd.toString());
        appendField(sb, String.valueOf(maxStartDelayMinutes));
        appendField(sb, requesterId);
        appendField(sb, approverId);
        appendField(sb, grantId);
        appendField(sb, baselineDigest);
        appendField(sb, clientNonce);
        appendField(sb, keyId);
        appendField(sb, algVersion);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendField(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("-1:NULL|");
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            sb.append(bytes.length).append(':').append(value).append('|');
        }
    }
}
