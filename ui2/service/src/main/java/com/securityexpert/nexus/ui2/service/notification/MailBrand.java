package com.securityexpert.nexus.ui2.service.notification;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

import java.net.URI;
import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * The product name shown in notification mail (From display name, subject prefix, test digests). Read from
 * {@code UI2_MAIL_PRODUCT_NAME}; defaults to "Overwatch". Control characters are stripped so the value can never
 * inject a header line.
 */
public final class MailBrand {
    private static final List<String> PUBLIC_URL_ENVS = List.of(
            "UI2_PUBLIC_URL", "UI2_PUBLIC_BASE_URL", "UI2_BASE_URL", "UI2_REPORTS_PUBLIC_BASE_URL");
    private static final List<String> ENVIRONMENT_ENVS = List.of(
            "UI2_MAIL_ENVIRONMENT", "UI2_ENVIRONMENT_NAME", "UI2_ENVIRONMENT");

    private MailBrand() {}

    public static String name() {
        return cleanLabel(OverwatchEnv.get("UI2_MAIL_PRODUCT_NAME"), "Overwatch");
    }

    /** The deployment environment label shown in the notification mail footer; defaults to "production". */
    public static String environment() {
        for (String env : ENVIRONMENT_ENVS) {
            String value = OverwatchEnv.get(env);
            if (value != null && !value.isBlank()) {
                return cleanLabel(value, "production");
            }
        }
        return "production";
    }

    /** The public base URL (without trailing slash) for links in notification mail, or {@code null} when unset. */
    public static String publicBaseUrl() {
        for (String env : PUBLIC_URL_ENVS) {
            String normalized = normalizeBaseUrl(OverwatchEnv.get(env));
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    /**
     * Resolves the public base URL from environment variables first, falling back to {@code saml_settings.sp_base_url}
     * when configured in the database; returns {@code null} when unset.
     */
    public static String publicBaseUrl(DSLContext dsl) {
        String envUrl = publicBaseUrl();
        if (envUrl != null || dsl == null) {
            return envUrl;
        }
        try {
            Record row = dsl.fetchOne("select sp_base_url from saml_settings where id = 1");
            if (row != null && row.field("sp_base_url") != null) {
                return normalizeBaseUrl(row.get("sp_base_url", String.class));
            }
        } catch (RuntimeException ignored) {
            // Unset or unavailable in lightweight mock fixtures; omit the link.
        }
        return null;
    }

    static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw.strip();
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.isEmpty() || candidate.length() > 256 || !candidate.matches("[^\\p{Cntrl}\\s<>\"']+")) {
            return null;
        }
        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme();
            if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            return candidate;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String cleanLabel(String value, String fallback) {
        if (value == null) return fallback;
        String clean = value.replaceAll("[\\p{Cntrl}<>\"]", "").strip();
        return clean.isEmpty() || clean.length() > 64 ? fallback : clean;
    }
}
