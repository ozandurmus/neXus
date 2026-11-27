package com.securityexpert.nexus.ui2.service.eventintake.submit;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.securityexpert.nexus.ui2.service.eventintake.submit.InternalSubmitAuthResult.MatchedSlot;
import com.securityexpert.nexus.ui2.service.eventintake.submit.InternalSubmitAuthResult.Status;

/**
 * Pure-Java fail-closed authenticator and closed-schema payload parser for internal trigger
 * submissions ({@code POST /internal/event-triggers/submit} from {@code ui2-event-intake} to
 * {@code ui2-service}, per {@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md}
 * {@code Q8}, {@code §1.1}, and {@code POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md} {@code §4.2}).
 *
 * <p>Enforces the fail-closed verification sequence in strict order:</p>
 * <ol>
 *   <li>Pre-parse 1,024-byte body cap ({@code > 1024} bytes &rarr; {@link Status#PAYLOAD_TOO_LARGE}, HTTP 413).</li>
 *   <li>Fail-closed token configuration and strict {@code "Bearer <b64token>"} header syntax check (RFC 6750)
 *       ({@link Status#UNAUTHORIZED}, HTTP 401).</li>
 *   <li>Constant-time SHA-256 digest comparison via {@link MessageDigest#isEqual} against
 *       {@code current} and (when configured) {@code next}, always evaluating both digests when
 *       {@code next} is configured so timing does not leak which slot matched.</li>
 *   <li>Pre-parse non-empty body check ({@code null} or 0 bytes &rarr; {@link Status#INVALID_PAYLOAD}, HTTP 400).</li>
 *   <li>Strict closed-schema JSON deserialization ({@code FAIL_ON_UNKNOWN_PROPERTIES},
 *       {@code FAIL_ON_TRAILING_TOKENS}, {@code STRICT_DUPLICATE_DETECTION}, scalar coercion disabled)
 *       with exact canonical lowercase RFC 4122 UUID verification for {@code scope_id} and {@code event_id}.</li>
 * </ol>
 */
public final class InternalSubmitAuthenticator {

    public static final int MAX_BODY_BYTES = 1024;
    public static final int MAX_BEARER_TOKEN_LENGTH = 4096;

    public static final String REASON_CONFIG_UNAVAILABLE = "CONFIG_UNAVAILABLE";
    public static final String REASON_MISSING_AUTHORIZATION_HEADER = "MISSING_AUTHORIZATION_HEADER";
    public static final String REASON_MALFORMED_AUTHORIZATION_HEADER = "MALFORMED_AUTHORIZATION_HEADER";
    public static final String REASON_INVALID_OR_EXPIRED_TOKEN =
            "INVALID_OR_EXPIRED_TOKEN";
    public static final String REASON_BODY_EXCEEDS_MAX_BYTES = "BODY_EXCEEDS_MAX_BYTES";
    public static final String REASON_EMPTY_BODY = "EMPTY_BODY";
    public static final String REASON_MALFORMED_JSON = "MALFORMED_JSON";
    public static final String REASON_INVALID_UUID_FIELD = "INVALID_UUID_FIELD";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern CANONICAL_UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

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

    private final Clock clock;

    public InternalSubmitAuthenticator() {
        this(Clock.systemUTC());
    }

    public InternalSubmitAuthenticator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Authenticates and parses an internal submit request using the configured {@link Clock}.
     */
    public InternalSubmitAuthResult authenticateAndParse(
            String authorizationHeader,
            byte[] rawBody,
            InternalSubmitTokenConfig config) {
        return authenticateAndParse(authorizationHeader, rawBody, config, clock.instant());
    }

    /**
     * Authenticates and parses an internal submit request at the explicit instant {@code now}.
     */
    public InternalSubmitAuthResult authenticateAndParse(
            String authorizationHeader,
            byte[] rawBody,
            InternalSubmitTokenConfig config,
            Instant now) {
        // 1. Pre-parse oversize cap before auth hashing or Jackson touches a single byte.
        if (rawBody != null && rawBody.length > MAX_BODY_BYTES) {
            return InternalSubmitAuthResult.rejected(
                    Status.PAYLOAD_TOO_LARGE,
                    REASON_BODY_EXCEEDS_MAX_BYTES);
        }

        // 2. Fail closed when token configuration is absent or Authorization header is missing/malformed.
        if (config == null) {
            return InternalSubmitAuthResult.rejected(
                    Status.UNAUTHORIZED,
                    REASON_CONFIG_UNAVAILABLE);
        }
        if (authorizationHeader == null) {
            return InternalSubmitAuthResult.rejected(
                    Status.UNAUTHORIZED,
                    REASON_MISSING_AUTHORIZATION_HEADER);
        }
        if (!authorizationHeader.startsWith(BEARER_PREFIX)
                || authorizationHeader.length() - BEARER_PREFIX.length() > MAX_BEARER_TOKEN_LENGTH) {
            return InternalSubmitAuthResult.rejected(
                    Status.UNAUTHORIZED,
                    REASON_MALFORMED_AUTHORIZATION_HEADER);
        }
        final String token = authorizationHeader.substring(BEARER_PREFIX.length());
        if (!InternalSubmitTokenConfig.matchesBearerTokenGrammar(token)) {
            return InternalSubmitAuthResult.rejected(
                    Status.UNAUTHORIZED,
                    REASON_MALFORMED_AUTHORIZATION_HEADER);
        }

        // 3. Constant-time SHA-256 verification. Always evaluate both slots when `next` is configured
        //    so comparison timing does not leak which slot matched.
        final MatchedSlot matchedSlot = verifyTokenDigest(token, config, now);
        if (matchedSlot == null) {
            return InternalSubmitAuthResult.rejected(
                    Status.UNAUTHORIZED,
                    REASON_INVALID_OR_EXPIRED_TOKEN);
        }

        // 4. Pre-parse empty/null body check before Jackson parses a single byte.
        if (rawBody == null || rawBody.length == 0) {
            return InternalSubmitAuthResult.rejected(
                    Status.INVALID_PAYLOAD,
                    REASON_EMPTY_BODY);
        }

        // 5. Strict closed-schema JSON deserialization and exact canonical RFC 4122 UUID verification.
        final WireSubmitPayload wire;
        try {
            wire = OBJECT_MAPPER.readValue(rawBody, WireSubmitPayload.class);
        } catch (IOException ex) {
            return InternalSubmitAuthResult.rejected(
                    Status.INVALID_PAYLOAD,
                    REASON_MALFORMED_JSON);
        }
        if (wire == null) {
            return InternalSubmitAuthResult.rejected(
                    Status.INVALID_PAYLOAD,
                    REASON_MALFORMED_JSON);
        }

        final UUID scopeId = parseCanonicalUuid(wire.scopeId());
        final UUID eventId = parseCanonicalUuid(wire.eventId());
        if (scopeId == null || eventId == null) {
            return InternalSubmitAuthResult.rejected(
                    Status.INVALID_PAYLOAD,
                    REASON_INVALID_UUID_FIELD);
        }

        return InternalSubmitAuthResult.accepted(new InternalSubmitRequest(scopeId, eventId), matchedSlot);
    }

    private static MatchedSlot verifyTokenDigest(
            String token,
            InternalSubmitTokenConfig config,
            Instant now) {
        final byte[] presentedDigest = InternalSubmitTokenConfig.digestRawToken(token);
        final byte[] currentDigest = config.currentDigestBytes();
        final byte[] nextDigest = config.nextDigestBytes();
        try {
            final boolean matchesCurrent = MessageDigest.isEqual(presentedDigest, currentDigest);
            final boolean matchesNextDigest = nextDigest != null
                    && MessageDigest.isEqual(presentedDigest, nextDigest);
            final boolean nextWindowOpen = nextDigest != null
                    && now != null
                    && !now.isAfter(config.nextValidUntil());
            final boolean matchesNext = matchesNextDigest && nextWindowOpen;

            if (matchesCurrent) {
                return MatchedSlot.CURRENT;
            }
            if (matchesNext) {
                return MatchedSlot.NEXT;
            }
            return null;
        } finally {
            Arrays.fill(presentedDigest, (byte) 0);
            Arrays.fill(currentDigest, (byte) 0);
            if (nextDigest != null) {
                Arrays.fill(nextDigest, (byte) 0);
            }
        }
    }

    private static UUID parseCanonicalUuid(String raw) {
        if (raw == null || !CANONICAL_UUID_PATTERN.matcher(raw).matches()) {
            return null;
        }
        return UUID.fromString(raw);
    }

    private record WireSubmitPayload(
            @JsonProperty("scope_id") String scopeId,
            @JsonProperty("event_id") String eventId) {
    }
}
