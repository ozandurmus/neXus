package com.securityexpert.nexus.ui2.service.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** C9's explicit allowlist: new keys and unclassified string carriers never pass through. */
public final class ReplayProjector {
    private final byte[] key;

    public ReplayProjector(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("replay key must be 256 bits");
        }
        this.key = key.clone();
    }

    public String pseudonym(String domain, String identity) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return domain + "-" + HexFormat.of().formatHex(mac.doFinal(
                    (domain + "\0" + identity).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("replay projection unavailable");
        }
    }

    public Object project(Object body) {
        if (!(body instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("unclassified replay response");
        }
        if ("NOT_FOUND".equals(map.get("error"))) {
            return Map.of("error", "NOT_FOUND");
        }
        if (map.get("devices") instanceof List<?> devices) {
            return Map.of("devices", devices.stream().map(this::device).toList());
        }
        return device(map);
    }

    private Map<String, Object> device(Object value) {
        if (!(value instanceof Map<?, ?> source) || !(source.get("device_id") instanceof String id)) {
            throw new IllegalArgumentException("unclassified replay device");
        }
        String label = pseudonym("device", id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device_id", label);
        copy(source, result, "role", "vendor_hint", "enrollment_state", "disabled",
                "model", "software_version", "ha_role");
        if (source.containsKey("hostname")) {
            result.put("hostname", source.get("hostname") == null ? null : label);
        }
        if (source.containsKey("cluster_member_ref")) {
            Object ref = source.get("cluster_member_ref");
            result.put("cluster_member_ref", ref == null ? null : pseudonym("device", requireString(ref)));
        }
        if (source.containsKey("facts")) {
            Object rawFacts = source.get("facts");
            if (rawFacts == null) {
                result.put("facts", null);
            } else if (rawFacts instanceof Map<?, ?> facts) {
                Map<String, Object> safeFacts = new LinkedHashMap<>();
                copy(facts, safeFacts, "model", "software_version", "ha_role");
                if (facts.containsKey("hostname")) {
                    safeFacts.put("hostname", facts.get("hostname") == null ? null : label);
                }
                result.put("facts", safeFacts);
            } else {
                throw new IllegalArgumentException("unclassified replay facts");
            }
        }
        if (source.containsKey("job")) {
            Object rawJob = source.get("job");
            if (rawJob == null) {
                result.put("job", null);
            } else if (rawJob instanceof Map<?, ?> job && job.get("job_id") instanceof String jobId) {
                Map<String, Object> safeJob = new LinkedHashMap<>();
                safeJob.put("job_id", pseudonym("job", jobId));
                copy(job, safeJob, "state", "outcome");
                result.put("job", safeJob);
            } else {
                throw new IllegalArgumentException("unclassified replay job");
            }
        }
        return result;
    }

    private static String requireString(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("unclassified replay identity");
        }
        return text;
    }

    private static void copy(Map<?, ?> source, Map<String, Object> target, String... fields) {
        for (String field : fields) {
            if (source.containsKey(field)) {
                Object value = source.get(field);
                if (value != null && !(value instanceof String) && !(value instanceof Boolean)) {
                    throw new IllegalArgumentException("unclassified replay field type");
                }
                target.put(field, value);
            }
        }
    }
}
