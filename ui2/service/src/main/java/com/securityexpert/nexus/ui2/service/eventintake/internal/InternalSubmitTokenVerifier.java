package com.securityexpert.nexus.ui2.service.eventintake.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.securityexpert.nexus.ui2.service.eventintake.internal.InternalSubmitAuthResult.Status;

/**
 * Pure-Java constant-time SHA-256 bearer-token verifier and closed-schema payload parser for
 * internal {@code POST /internal/event-triggers/submit} calls
 * ({@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md} {@code Q8};
 * {@code POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md} {@code §4.2} Option A).
 *
 * <p>Enforces fail-closed verification in strict order:</p>
 * <ol>
 *   <li>Configuration check: {@code config == null} &rarr; {@link Status#VERIFIER_NOT_CONFIGURED} (503).</li>
 *   <li>Pre-parse body cap ({@value #MAX_BODY_BYTES} bytes):
 *       {@code rawBodyBytes != null && rawBodyBytes.length > 1024} &rarr;
 *       {@link Status#OVERSIZE} (413) before header/token verification and JSON parsing.</li>
 *   <li>Strict {@code Authorization} header parse: exact prefix {@code "Bearer "} followed by a
 *       printable non-whitespace ASCII token ({@value #MIN_TOKEN_LENGTH}..{@value #MAX_TOKEN_LENGTH}
 *       chars); otherwise {@link Status#MISSING_OR_MALFORMED_HEADER} (401).</li>
 *   <li>Constant-time SHA-256 digest verification via {@link MessageDigest#isEqual(byte[], byte[])}
 *       against {@code current} and optional {@code next} (valid only while
 *       {@code nextValidUntil != null && !now.isAfter(nextValidUntil)}).</li>
 *   <li>Payload presence check ({@code rawBodyBytes == null} &rarr;
 *       {@link Status#INVALID_PAYLOAD}, 400), strict Jackson {@link JsonMapper} deserialization
 *       ({@code FAIL_ON_UNKNOWN_PROPERTIES}, {@code FAIL_ON_TRAILING_TOKENS},
 *       {@code STRICT_DUPLICATE_DETECTION}, no scalar coercion), and strict canonical UUID
 *       validation.</li>
 * </ol>
 */
public final class InternalSubmitTokenVerifier {

    public static final int MAX_BODY_BYTES = 1024;
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final int MIN_TOKEN_LENGTH = 16;
    public static final int MAX_TOKEN_LENGTH = 512;

    private static final String SHA256_ALGORITHM = "SHA-256";
    private static final ObjectMapper OBJECT_MAPPER = buildStrictObjectMapper();

    private static ObjectMapper buildStrictObjectMapper() {
        ObjectMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    private final InternalSubmitTokenConfig config;
    private final Clock clock;

    /**
     * Creates a verifier with the given token configuration (nullable; {@code null} fails closed
     * with HTTP 503) and {@link Clock#systemUTC()}.
     */
    public InternalSubmitTokenVerifier(InternalSubmitTokenConfig config) {
        this(config, Clock.systemUTC());
    }

    /**
     * Creates a verifier with the given token configuration (nullable; {@code null} fails closed
     * with HTTP 503) and explicit {@link Clock}.
     */
    public InternalSubmitTokenVerifier(InternalSubmitTokenConfig config, Clock clock) {
        this.config = config;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Verifies an internal submit request using this verifier's configured token digests and clock.
     */
    public InternalSubmitAuthResult verify(String authorizationHeader, byte[] rawBodyBytes) {
        return verify(this.config, authorizationHeader, rawBodyBytes, clock.instant());
    }

    /**
     * Verifies an internal submit request using this verifier's configured token digests at {@code now}.
     */
    public InternalSubmitAuthResult verify(
            String authorizationHeader,
            byte[] rawBodyBytes,
            Instant now) {
        return verify(this.config, authorizationHeader, rawBodyBytes, now);
    }

    /**
     * Verifies an internal submit request against the provided {@code tokenConfig} at {@code now}.
     */
    public InternalSubmitAuthResult verify(
            InternalSubmitTokenConfig tokenConfig,
            String authorizationHeader,
            byte[] rawBodyBytes,
            Instant now) {
        Objects.requireNonNull(now, "now");

        // 1. Fail-closed configuration check.
        if (tokenConfig == null) {
            return InternalSubmitAuthResult.rejected(Status.VERIFIER_NOT_CONFIGURED);
        }

        // 2. Pre-parse body byte cap (before header/token verification and before JSON parsing).
        if (rawBodyBytes != null && rawBodyBytes.length > MAX_BODY_BYTES) {
            return InternalSubmitAuthResult.rejected(Status.OVERSIZE);
        }

        // 3. Strict Authorization Bearer header validation.
        final String token = extractBearerToken(authorizationHeader);
        if (token == null) {
            return InternalSubmitAuthResult.rejected(Status.MISSING_OR_MALFORMED_HEADER);
        }

        // 4. Constant-time SHA-256 verification against current and optional next digests.
        final Status authStatus = verifyTokenDigest(tokenConfig, token, now);
        if (!authStatus.isAccepted()) {
            return InternalSubmitAuthResult.rejected(authStatus);
        }

        // 5. Strict closed-schema JSON deserialization and canonical UUID validation.
        if (rawBodyBytes == null) {
            return InternalSubmitAuthResult.rejected(Status.INVALID_PAYLOAD);
        }
        final InternalSubmitRequest request;
        try {
            request = OBJECT_MAPPER.readValue(rawBodyBytes, InternalSubmitRequest.class);
        } catch (IOException | IllegalArgumentException ex) {
            return InternalSubmitAuthResult.rejected(Status.INVALID_PAYLOAD);
        }
        if (request == null
                || !InternalSubmitRequest.isCanonicalUuid(request.scopeId())
                || !InternalSubmitRequest.isCanonicalUuid(request.eventId())) {
            return InternalSubmitAuthResult.rejected(Status.INVALID_PAYLOAD);
        }

        return authStatus == Status.ACCEPTED_CURRENT
                ? InternalSubmitAuthResult.acceptedCurrent(request)
                : InternalSubmitAuthResult.acceptedNext(request);
    }

    private static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        final String token = authorizationHeader.substring(BEARER_PREFIX.length());
        final int length = token.length();
        if (length < MIN_TOKEN_LENGTH || length > MAX_TOKEN_LENGTH) {
            return null;
        }
        for (int i = 0; i < length; i++) {
            final char c = token.charAt(i);
            // Printable ASCII excluding whitespace (0x20) and control chars (<0x20, >=0x7F).
            if (c < 0x21 || c > 0x7E) {
                return null;
            }
        }
        return token;
    }

    private static Status verifyTokenDigest(
            InternalSubmitTokenConfig tokenConfig,
            String token,
            Instant now) {
        final byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        final byte[] presentedDigest;
        try {
            presentedDigest = sha256(tokenBytes);
        } finally {
            Arrays.fill(tokenBytes, (byte) 0);
        }

        final byte[] currentDigest = tokenConfig.currentSha256Bytes();
        final byte[] nextDigest = tokenConfig.nextSha256Bytes().orElse(null);
        try {
            final boolean matchesCurrent = MessageDigest.isEqual(presentedDigest, currentDigest);
            final boolean matchesNext = nextDigest != null
                    && MessageDigest.isEqual(presentedDigest, nextDigest);

            if (matchesCurrent) {
                return Status.ACCEPTED_CURRENT;
            }
            if (matchesNext) {
                final Instant nextValidUntil = tokenConfig.nextValidUntil();
                if (nextValidUntil != null && !now.isAfter(nextValidUntil)) {
                    return Status.ACCEPTED_NEXT;
                }
                if (nextValidUntil != null) {
                    return Status.NEXT_TOKEN_EXPIRED;
                }
                return Status.TOKEN_MISMATCH;
            }
            return Status.TOKEN_MISMATCH;
        } finally {
            Arrays.fill(presentedDigest, (byte) 0);
            Arrays.fill(currentDigest, (byte) 0);
            if (nextDigest != null) {
                Arrays.fill(nextDigest, (byte) 0);
            }
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance(SHA256_ALGORITHM).digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 MessageDigest provider unavailable", ex);
        }
    }
}
