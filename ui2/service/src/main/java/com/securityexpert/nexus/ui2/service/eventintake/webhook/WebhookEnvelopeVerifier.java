package com.securityexpert.nexus.ui2.service.eventintake.webhook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.securityexpert.nexus.ui2.service.eventintake.webhook.WebhookPayload.EventClass;
import com.securityexpert.nexus.ui2.service.eventintake.webhook.WebhookPayload.MessageType;
import com.securityexpert.nexus.ui2.service.eventintake.webhook.WebhookVerificationResult.Status;

/**
 * Pure-Java verifier for {@code RELAY_HTTPS} structured webhook envelopes
 * ({@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md} {@code Q1} Option {@code 1B},
 * {@code §1.1}; {@code POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md} {@code §4.5}).
 *
 * <p>Enforces the fail-closed verification sequence in strict order:</p>
 * <ol>
 *   <li>Pre-parse 8 KiB body cap ({@code > 8192} bytes &rarr; {@link Status#OVERSIZE}, HTTP 413).</li>
 *   <li>Required header presence and strict syntax/skew validation ({@code ±300 s}).</li>
 *   <li>Dual-{@code kid} key lookup and constant-time HMAC-SHA256 verification over
 *       {@code ASCII(timestamp + "." + nonce + ".") || rawBodyBytes}.</li>
 *   <li>Fail-closed replay protection via {@link WebhookNonceStore} (TTL 600 s; replay &rarr; 409;
 *       unavailable/exception &rarr; 503).</li>
 *   <li>Closed-schema JSON deserialization ({@code FAIL_ON_UNKNOWN_PROPERTIES},
 *       {@code FAIL_ON_TRAILING_TOKENS}, {@code STRICT_DUPLICATE_DETECTION}, no scalar coercion)
 *       and fail-closed opaque-identity check ({@link Status#NOT_EVALUABLE} when a {@code CHANGE_EVENT}
 *       lacks both {@code opaque_emitter_key} and {@code opaque_target_id}).</li>
 * </ol>
 */
public final class WebhookEnvelopeVerifier {

    public static final int MAX_BODY_BYTES = 8192;
    public static final long MAX_TIMESTAMP_SKEW_SECONDS = 300L;
    public static final long NONCE_TTL_SECONDS = 600L;

    public static final String HEADER_KEY_ID = "X-Signal-Key-Id";
    public static final String HEADER_TIMESTAMP = "X-Signal-Timestamp";
    public static final String HEADER_NONCE = "X-Signal-Nonce";
    public static final String HEADER_SIGNATURE = "X-Signal-Signature";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAX_SIGNAL_ID_LENGTH = 128;
    private static final int MAX_OPAQUE_TARGET_ID_LENGTH = 128;
    private static final int MAX_DEVICE_EVENT_TIME_LENGTH = 64;

    private static final Pattern KEY_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^(0|-?[1-9][0-9]{0,18})$");
    private static final Pattern NONCE_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{16,128}$");
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("^sha256=([0-9a-f]{64})$");
    private static final Pattern OPAQUE_EMITTER_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");

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

    private final WebhookNonceStore nonceStore;
    private final Clock clock;

    public WebhookEnvelopeVerifier(WebhookNonceStore nonceStore) {
        this(nonceStore, Clock.systemUTC());
    }

    public WebhookEnvelopeVerifier(WebhookNonceStore nonceStore, Clock clock) {
        this.nonceStore = Objects.requireNonNull(nonceStore, "nonceStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Verifies an inbound webhook request using the verifier's configured {@link Clock}.
     */
    public WebhookVerificationResult verify(
            WebhookSourceKey sourceKey,
            Map<String, String> headers,
            byte[] rawBodyBytes) {
        return verify(sourceKey, headers, rawBodyBytes, clock.instant());
    }

    /**
     * Verifies an inbound webhook request at the explicit instant {@code now}.
     */
    public WebhookVerificationResult verify(
            WebhookSourceKey sourceKey,
            Map<String, String> headers,
            byte[] rawBodyBytes,
            Instant now) {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(now, "now");

        final String sourceId = sourceKey.sourceId();

        // 1. Pre-parse 8 KiB body cap (before header/signature/JSON processing).
        if (rawBodyBytes != null && rawBodyBytes.length > MAX_BODY_BYTES) {
            return WebhookVerificationResult.rejected(
                    Status.OVERSIZE,
                    sourceId,
                    null,
                    "Webhook payload exceeds maximum allowed size of 8192 bytes.");
        }
        final byte[] body = rawBodyBytes != null ? rawBodyBytes : new byte[0];

        // 2. Normalize headers case-insensitively and reject conflicting duplicate keys.
        final Map<String, String> normalizedHeaders = normalizeHeaders(headers);
        if (normalizedHeaders == null) {
            return WebhookVerificationResult.rejected(
                    Status.INVALID_HEADERS,
                    sourceId,
                    null,
                    "Duplicate conflicting webhook headers are not permitted.");
        }

        final String kid = normalizedHeaders.get(HEADER_KEY_ID.toLowerCase(Locale.ROOT));
        if (kid == null || !KEY_ID_PATTERN.matcher(kid).matches()) {
            return WebhookVerificationResult.rejected(
                    Status.INVALID_HEADERS,
                    sourceId,
                    null,
                    "Missing or malformed X-Signal-Key-Id header.");
        }

        final String nonce = normalizedHeaders.get(HEADER_NONCE.toLowerCase(Locale.ROOT));
        if (nonce == null || !NONCE_PATTERN.matcher(nonce).matches()) {
            return WebhookVerificationResult.rejected(
                    Status.INVALID_HEADERS,
                    sourceId,
                    null,
                    "Missing or malformed X-Signal-Nonce header.");
        }

        final String timestampHeader = normalizedHeaders.get(HEADER_TIMESTAMP.toLowerCase(Locale.ROOT));
        final Instant requestTimestamp = parseEpochSeconds(timestampHeader);
        if (requestTimestamp == null) {
            return WebhookVerificationResult.rejected(
                    Status.TIMESTAMP_OUT_OF_WINDOW,
                    sourceId,
                    null,
                    "Missing or non-numeric X-Signal-Timestamp header.");
        }
        final Instant minAllowed = now.minusSeconds(MAX_TIMESTAMP_SKEW_SECONDS);
        final Instant maxAllowed = now.plusSeconds(MAX_TIMESTAMP_SKEW_SECONDS);
        if (requestTimestamp.isBefore(minAllowed) || requestTimestamp.isAfter(maxAllowed)) {
            return WebhookVerificationResult.rejected(
                    Status.TIMESTAMP_OUT_OF_WINDOW,
                    sourceId,
                    null,
                    "X-Signal-Timestamp is outside the allowed +-300s skew window.");
        }

        final String signatureHeader = normalizedHeaders.get(HEADER_SIGNATURE.toLowerCase(Locale.ROOT));
        final byte[] providedSignature = parseSignatureBytes(signatureHeader);
        if (providedSignature == null) {
            return WebhookVerificationResult.rejected(
                    Status.INVALID_SIGNATURE,
                    sourceId,
                    null,
                    "Missing or malformed X-Signal-Signature header.");
        }

        // 3. Dual-kid key resolution and constant-time HMAC-SHA256 verification.
        final Optional<byte[]> maybeSecret = sourceKey.resolveSecret(kid, now);
        if (maybeSecret.isEmpty()) {
            return WebhookVerificationResult.rejected(
                    Status.UNKNOWN_KEY,
                    sourceId,
                    null,
                    "Unknown or expired webhook signing key identifier.");
        }

        final byte[] secret = maybeSecret.get();
        final byte[] expectedSignature;
        try {
            expectedSignature = computeHmacSha256(secret, timestampHeader, nonce, body);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }

        if (!MessageDigest.isEqual(expectedSignature, providedSignature)) {
            return WebhookVerificationResult.rejected(
                    Status.INVALID_SIGNATURE,
                    sourceId,
                    kid,
                    "Webhook HMAC-SHA256 signature verification failed.");
        }

        // 4. Fail-closed replay protection (only after signature and timestamp pass).
        // Guarantee nonce retention extends strictly past the latest instant requestTimestamp can
        // still satisfy the +-300s skew check, even for stores pruning expires_at <= now().
        final Instant minNonceExpiry = requestTimestamp.plusSeconds(MAX_TIMESTAMP_SKEW_SECONDS).plusSeconds(1);
        final Instant defaultNonceExpiry = now.plusSeconds(NONCE_TTL_SECONDS);
        final Instant nonceExpiresAt = defaultNonceExpiry.isAfter(minNonceExpiry)
                ? defaultNonceExpiry
                : minNonceExpiry;
        final WebhookNonceStore.RecordOutcome nonceOutcome;
        try {
            nonceOutcome = nonceStore.recordIfAbsent(sourceId, nonce, nonceExpiresAt);
        } catch (RuntimeException ex) {
            return WebhookVerificationResult.rejected(
                    Status.REPLAY_STORE_UNAVAILABLE,
                    sourceId,
                    kid,
                    "Replay protection store is unavailable.");
        }
        if (nonceOutcome == null || nonceOutcome == WebhookNonceStore.RecordOutcome.UNAVAILABLE) {
            return WebhookVerificationResult.rejected(
                    Status.REPLAY_STORE_UNAVAILABLE,
                    sourceId,
                    kid,
                    "Replay protection store is unavailable.");
        }
        if (nonceOutcome == WebhookNonceStore.RecordOutcome.REPLAYED) {
            return WebhookVerificationResult.rejected(
                    Status.REPLAYED_NONCE,
                    sourceId,
                    kid,
                    "Webhook request nonce has already been recorded.");
        }

        // 5. Closed JSON schema deserialization and semantic validation.
        final WebhookPayload payload;
        try {
            payload = OBJECT_MAPPER.readValue(body, WebhookPayload.class);
        } catch (IOException ex) {
            return WebhookVerificationResult.rejected(
                    Status.INVALID_PAYLOAD,
                    sourceId,
                    kid,
                    "Webhook JSON payload failed closed-schema validation.");
        }
        if (payload == null) {
            return WebhookVerificationResult.rejected(
                    Status.INVALID_PAYLOAD,
                    sourceId,
                    kid,
                    "Webhook JSON payload must be a JSON object.");
        }

        return validatePayload(sourceId, kid, payload);
    }

    private static WebhookVerificationResult validatePayload(
            String sourceId,
            String matchedKid,
            WebhookPayload payload) {
        final String signalId = payload.signalId();
        if (signalId == null
                || signalId.isBlank()
                || signalId.length() > MAX_SIGNAL_ID_LENGTH
                || containsAsciiControl(signalId)) {
            return WebhookVerificationResult.rejected(
                    Status.INVALID_PAYLOAD,
                    sourceId,
                    matchedKid,
                    "Webhook payload field signal_id is missing or out of bounds.");
        }

        final MessageType messageType = payload.messageType();
        if (messageType == null) {
            return WebhookVerificationResult.rejected(
                    Status.INVALID_PAYLOAD,
                    sourceId,
                    matchedKid,
                    "Webhook payload field message_type is required.");
        }

        final String deviceEventTime = payload.deviceEventTime();
        if (deviceEventTime != null
                && (deviceEventTime.length() > MAX_DEVICE_EVENT_TIME_LENGTH
                || containsAsciiControl(deviceEventTime))) {
            return WebhookVerificationResult.rejected(
                    Status.INVALID_PAYLOAD,
                    sourceId,
                    matchedKid,
                    "Webhook payload field device_event_time exceeds allowed bounds.");
        }

        final String opaqueEmitterKey = payload.opaqueEmitterKey();
        final String opaqueTargetId = payload.opaqueTargetId();

        if (messageType == MessageType.CHANGE_EVENT) {
            if (payload.vendor() == null
                    || payload.eventClass() == null
                    || payload.eventClass() == EventClass.HEARTBEAT
                    || payload.eventOutcome() == null) {
                return WebhookVerificationResult.rejected(
                        Status.INVALID_PAYLOAD,
                        sourceId,
                        matchedKid,
                        "Change event payload requires valid vendor, event_class, and event_outcome.");
            }

            final boolean hasEmitter = opaqueEmitterKey != null && !opaqueEmitterKey.isBlank();
            final boolean hasTarget = opaqueTargetId != null && !opaqueTargetId.isBlank();
            if (!hasEmitter && !hasTarget) {
                return WebhookVerificationResult.rejected(
                        Status.NOT_EVALUABLE,
                        sourceId,
                        matchedKid,
                        "Change event lacks both opaque_emitter_key and opaque_target_id.");
            }
        } else if (messageType == MessageType.HEARTBEAT) {
            if ((payload.eventClass() != null && payload.eventClass() != EventClass.HEARTBEAT)
                    || payload.eventOutcome() != null) {
                return WebhookVerificationResult.rejected(
                        Status.INVALID_PAYLOAD,
                        sourceId,
                        matchedKid,
                        "Heartbeat payload must not carry contradictory change-event event_class or event_outcome.");
            }
        }

        if (opaqueEmitterKey != null && !OPAQUE_EMITTER_KEY_PATTERN.matcher(opaqueEmitterKey).matches()) {
            return WebhookVerificationResult.rejected(
                    Status.INVALID_PAYLOAD,
                    sourceId,
                    matchedKid,
                    "Webhook payload field opaque_emitter_key violates format or length bounds.");
        }

        if (opaqueTargetId != null
                && (opaqueTargetId.isBlank()
                || opaqueTargetId.length() > MAX_OPAQUE_TARGET_ID_LENGTH
                || containsAsciiControl(opaqueTargetId))) {
            return WebhookVerificationResult.rejected(
                    Status.INVALID_PAYLOAD,
                    sourceId,
                    matchedKid,
                    "Webhook payload field opaque_target_id violates length or character bounds.");
        }

        return WebhookVerificationResult.accepted(sourceId, matchedKid, payload);
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new HashMap<>(headers.size());
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lower = key.toLowerCase(Locale.ROOT);
            String value = entry.getValue();
            if (normalized.containsKey(lower)) {
                String existing = normalized.get(lower);
                if (!Objects.equals(existing, value)) {
                    return null;
                }
            } else {
                normalized.put(lower, value);
            }
        }
        return normalized;
    }

    private static Instant parseEpochSeconds(String value) {
        if (value == null || !TIMESTAMP_PATTERN.matcher(value).matches()) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException | DateTimeException ex) {
            return null;
        }
    }

    private static byte[] parseSignatureBytes(String signatureHeader) {
        if (signatureHeader == null) {
            return null;
        }
        Matcher matcher = SIGNATURE_PATTERN.matcher(signatureHeader);
        if (!matcher.matches()) {
            return null;
        }
        return HexFormat.of().parseHex(matcher.group(1));
    }

    private static byte[] computeHmacSha256(
            byte[] secret,
            String timestampHeader,
            String nonce,
            byte[] rawBodyBytes) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] prefix = (timestampHeader + "." + nonce + ".").getBytes(StandardCharsets.US_ASCII);
            mac.update(prefix);
            mac.update(rawBodyBytes);
            return mac.doFinal();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 provider unavailable", ex);
        }
    }

    private static boolean containsAsciiControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }
}
