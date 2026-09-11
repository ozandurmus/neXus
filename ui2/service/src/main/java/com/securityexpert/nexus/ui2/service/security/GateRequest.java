package com.securityexpert.nexus.ui2.service.security;

import java.util.Optional;

/**
 * A servlet-independent view of one HTTP request, as the {@code E1}-{@code E6}
 * chain needs it (contract §7). Kept free of any servlet type so
 * {@link GateChain} is testable without a servlet container or MockMvc; a
 * thin adapter extracts this from the real {@code HttpServletRequest}.
 */
public record GateRequest(
        String method,
        Optional<String> sessionCookieRawValue,
        Optional<String> csrfHeader,
        Optional<String> origin,
        String actionId,
        Optional<String> targetRef) {

    public boolean isStateChanging() {
        return switch (method.toUpperCase()) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }
}
