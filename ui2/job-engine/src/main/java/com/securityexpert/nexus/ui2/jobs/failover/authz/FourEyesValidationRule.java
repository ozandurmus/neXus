package com.securityexpert.nexus.ui2.jobs.failover.authz;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Enforces the 4-eyes dual control authorization invariants and cryptographic token management.
 * Invariants:
 * 1. Requester and approver must be distinct authenticated principals (requesterId != approverId).
 * 2. Mandatory operator reason >= 8 characters.
 * 3. Mandatory active maintenance window reference.
 * 4. Mandatory pre-flight assessment digest binding.
 * 5. Ephemeral lease lifetime (bounded to 15 minutes).
 * 6. Cryptographic token signing and single-use verification.
 */
public class FourEyesValidationRule {

    public static final Duration MAX_LEASE_DURATION = Duration.ofMinutes(15);
    public static final int MIN_REASON_LENGTH = 8;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secretSigningKey;
    private final Clock clock;

    public FourEyesValidationRule(byte[] secretSigningKey) {
        this(secretSigningKey, Clock.systemUTC());
    }

    public FourEyesValidationRule(byte[] secretSigningKey, Clock clock) {
        this.secretSigningKey = Objects.requireNonNull(secretSigningKey, "secretSigningKey must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (secretSigningKey.length < 16) {
            throw new IllegalArgumentException("secretSigningKey must be at least 16 bytes for HMAC-SHA256");
        }
    }

    public FourEyesAuthorizationResult authorize(FailoverAuthorizationRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // 1. Validate requester and approver presence
        String reqId = request.requesterId() != null ? request.requesterId().trim() : "";
        String appId = request.approverId() != null ? request.approverId().trim() : "";

        if (reqId.isEmpty() || appId.isEmpty()) {
            return new FourEyesAuthorizationResult.Refused(
                "AUTHENTICATION_REQUIRED",
                "Both requester and approver identities are required for 4-eyes failover authorization"
            );
        }

        // 2. Dual-control separation: requester != approver
        if (reqId.equalsIgnoreCase(appId)) {
            return new FourEyesAuthorizationResult.Refused(
                "FOUR_EYES_IDENTITY_COLLISION",
                "Requester and approver must be distinct authenticated individuals (4-eyes dual control invariant)"
            );
        }

        // 3. Mandatory justification reason length
        String reason = request.reason() != null ? request.reason().trim() : "";
        if (reason.length() < MIN_REASON_LENGTH) {
            return new FourEyesAuthorizationResult.Refused(
                "REASON_TOO_SHORT",
                "A failover authorization requires an explicit operator reason of at least " + MIN_REASON_LENGTH + " characters"
            );
        }

        // 4. Maintenance window check
        String mwRef = request.maintenanceWindowRef() != null ? request.maintenanceWindowRef().trim() : "";
        if (mwRef.isEmpty()) {
            return new FourEyesAuthorizationResult.Refused(
                "MAINTENANCE_WINDOW_REQUIRED",
                "An authorized maintenance window reference or change ticket ID is required"
            );
        }

        // 5. Assessment digest binding
        String digest = request.assessmentDigest() != null ? request.assessmentDigest().trim() : "";
        if (digest.isEmpty()) {
            return new FourEyesAuthorizationResult.Refused(
                "PREFLIGHT_ASSESSMENT_REQUIRED",
                "An immutable pre-flight assessment digest must be bound to the authorization lease"
            );
        }

        // 6. Nonce validation
        String nonce = request.clientNonce() != null ? request.clientNonce().trim() : "";
        if (nonce.isEmpty()) {
            return new FourEyesAuthorizationResult.Refused(
                "NONCE_REQUIRED",
                "A client nonce is required for replay protection"
            );
        }

        // Issue lease token
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(MAX_LEASE_DURATION);
        String tokenId = UUID.randomUUID().toString();
        String signature = computeSignature(tokenId, request.clusterRef(), reqId, appId, digest, expiresAt, nonce);

        FailoverLeaseToken token = new FailoverLeaseToken(
            tokenId,
            request.clusterRef(),
            reqId,
            appId,
            digest,
            now,
            expiresAt,
            signature
        );

        return new FourEyesAuthorizationResult.Authorized(token);
    }

    public boolean verifyTokenSignature(FailoverLeaseToken token, String clientNonce) {
        if (token == null || clientNonce == null) {
            return false;
        }
        if (token.isExpired(Instant.now(clock))) {
            return false;
        }
        String expectedSig = computeSignature(
            token.tokenId(),
            token.clusterRef(),
            token.requesterId(),
            token.approverId(),
            token.assessmentDigest(),
            token.expiresAt(),
            clientNonce
        );
        return MessageDigest.isEqual(
            token.tokenSignature().getBytes(StandardCharsets.UTF_8),
            expectedSig.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String computeSignature(String tokenId, String clusterRef, String requesterId, String approverId,
                                    String digest, Instant expiresAt, String nonce) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretSigningKey, HMAC_ALGORITHM));
            String payload = String.join(":", tokenId, clusterRef, requesterId, approverId, digest, String.valueOf(expiresAt.toEpochMilli()), nonce);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(rawHmac.length * 2);
            for (byte b : rawHmac) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", ex);
        }
    }
}
