package com.securityexpert.nexus.ui2.service.estate;

import java.util.Map;
import java.util.Objects;

/**
 * Projected response of {@code GET /api/v2/estate/as-of}. Names and cluster references are already projected by
 * {@link EstateAsOfService} for the caller's persona so {@code PrivacyMaskingResponseBodyAdvice} sets
 * {@code X-Nexus-Masked: true}, enforces {@link #masked()} for {@code role:replay_viewer}, and passes the body through
 * without double-masking pseudonyms.
 */
public record EstateAsOfResponse(boolean masked, Map<String, Object> body) {

    public EstateAsOfResponse {
        Objects.requireNonNull(body, "body");
    }
}
