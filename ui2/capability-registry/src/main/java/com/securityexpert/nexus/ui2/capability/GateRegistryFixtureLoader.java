package com.securityexpert.nexus.ui2.capability;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Parses the version-controlled gate-registry fixture (adjudication F2:
 * "seeded from a version-controlled fixture committed alongside the
 * capability specs") into {@link GateRow}s. The fixture is committed at
 * {@code capability-registry/src/main/resources/capabilities/
 * gate_registry_fixture.yaml} -- a plain list under a {@code gates:} key,
 * one entry per row, field names matching {@link GateRow}'s own.
 */
public final class GateRegistryFixtureLoader {

    private GateRegistryFixtureLoader() {
    }

    @SuppressWarnings("unchecked")
    public static List<GateRow> loadFromYaml(String yamlText) {
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(yamlText);
        List<GateRow> result = new ArrayList<>();
        if (!(loaded instanceof Map<?, ?> root)) {
            return result;
        }
        Object gates = root.get("gates");
        if (!(gates instanceof List<?> gateList)) {
            return result;
        }
        for (Object item : gateList) {
            Map<String, Object> row = (Map<String, Object>) item;
            result.add(new GateRow(
                    str(row, "gate_id"),
                    str(row, "vendor"),
                    str(row, "platform_role_scope"),
                    str(row, "shell_context"),
                    str(row, "transport_kind"),
                    str(row, "canonical_command_key"),
                    ActionClass.fromId(str(row, "action_class")),
                    SignOffState.fromColumnValue(str(row, "sign_off_state")),
                    ((Number) row.get("timeout_s")).intValue(),
                    strOrNull(row, "retry_rule"),
                    strOrNull(row, "max_frequency"),
                    strOrNull(row, "session_reuse_rule"),
                    strOrNull(row, "unsupported_behavior_ref"),
                    strOrNull(row, "secret_output_risk"),
                    (List<String>) row.getOrDefault("safe_telemetry_fields", List.of()),
                    str(row, "source_document_pointer")));
        }
        return result;
    }

    public static List<GateRow> loadFromStream(InputStream stream) {
        try (stream) {
            return loadFromYaml(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String str(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (value == null) {
            throw new IllegalArgumentException("gate registry fixture row missing required field: " + field);
        }
        return String.valueOf(value);
    }

    private static String strOrNull(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? null : String.valueOf(value);
    }
}
