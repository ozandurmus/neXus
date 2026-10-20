package com.securityexpert.nexus.ui2.service.changes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;

/**
 * The per-request {@link Projection}: one snapshot of the inventory names, the product's pseudonymizer for device
 * and policy names, its keyed identity tokens for principals, and one {@link LogMasking.Session} for free text. The
 * plain view returns every value as it is; the masked view never returns a raw hostname, principal or free text.
 * When the masking session could not be built (the estate dictionary is unavailable), {@link #text} fails closed by
 * throwing, and the source that needed it reports itself unavailable instead of showing raw text.
 */
public final class DefaultProjection implements Projection {
    private record Device(String hostname, String clusterRef, String vendor) {
    }

    private final boolean masked;
    private final Map<String, Device> devices = new HashMap<>();
    private final TopologyNamePseudonymizer names;
    private final LogMasking.Session session; // null in the plain view, or when masking is unavailable
    private final RuntimeException sessionFailure;

    public DefaultProjection(boolean masked, List<DeviceSummaryRecord> inventory, TopologyNamePseudonymizer names,
            LogMasking.Session session, RuntimeException sessionFailure) {
        this.masked = masked;
        this.names = Objects.requireNonNull(names, "names");
        this.session = session;
        this.sessionFailure = sessionFailure;
        for (DeviceSummaryRecord d : inventory) {
            devices.put(d.deviceId(), new Device(d.observedHostname().filter(s -> !s.isBlank()).orElse(null),
                    d.clusterMemberRef().filter(s -> !s.isBlank()).orElse(null), d.vendorHint()));
        }
    }

    @Override
    public boolean masked() {
        return masked;
    }

    @Override
    public String device(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return "UNKNOWN";
        Device d = devices.get(deviceId);
        if (d == null || d.hostname() == null) return masked ? "DEV-" + shortId(deviceId) : deviceId;
        return masked ? names.maskDeviceName(d.hostname(), d.clusterRef(), deviceId) : d.hostname();
    }

    @Override
    public String vendor(String deviceId) {
        Device d = deviceId == null ? null : devices.get(deviceId);
        return d == null ? null : d.vendor();
    }

    @Override
    public String principal(String raw) {
        if (raw == null || raw.isBlank() || !masked) return raw;
        return names.identityTokens().user(raw.strip());
    }

    @Override
    public String text(String raw) {
        if (raw == null || raw.isEmpty() || !masked) return raw;
        if (session == null) {
            throw sessionFailure != null ? sessionFailure : new IllegalStateException("masking session unavailable");
        }
        return session.maskLine(raw);
    }

    /** A policy or package name: itself, or its deterministic policy pseudonym. */
    public String policyName(String raw) {
        if (raw == null || raw.isBlank() || !masked) return raw;
        return names.maskPolicyName("policy", raw.strip());
    }

    /** True when the masked view can show free text; false means text sources must report UNAVAILABLE. */
    public boolean textAvailable() {
        return !masked || session != null;
    }

    /** The reason free text is unavailable in the masked view, for the coverage note. */
    public String textUnavailableReason() {
        return sessionFailure == null ? "masking unavailable" : sessionFailure.getClass().getSimpleName();
    }

    private String shortId(String deviceId) {
        // Opaque ids are not shown to the aiview persona; a short keyed digest keeps two devices apart.
        String token = names.identityTokens().user("device:" + deviceId);
        int dash = token.indexOf('-');
        String hex = dash >= 0 ? token.substring(dash + 1) : token;
        return hex.length() > 6 ? hex.substring(0, 6) : hex;
    }
}
