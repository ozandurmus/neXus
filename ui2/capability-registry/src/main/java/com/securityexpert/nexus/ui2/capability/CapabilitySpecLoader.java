package com.securityexpert.nexus.ui2.capability;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Parses one YAML capability spec (adjudication §2 answer 5) into a
 * {@link CapabilitySpec}, validating it against the closed C4 §2.2 field
 * set at load: an unknown top-level or step-level field is an error, never
 * a silently ignored extra key (adjudication §2 answer 5, second sentence).
 *
 * <p>This class performs structural parsing/field-set validation only;
 * {@link CapabilitySpecValidator} and {@link GateResolver} run afterward.
 * SnakeYAML's default {@link Yaml#load(String)} returns a plain {@code
 * Map<String, Object>} tree (no custom type resolution, no code
 * execution) -- the safest mode for untrusted-shaped-but-trusted-source
 * (version-controlled) input.</p>
 */
public final class CapabilitySpecLoader {

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "capability_id", "vendor", "platform_role_scope", "action_class", "maturity_state",
            "transport", "target_shape_ref", "steps", "finally_steps", "parser_ref",
            "produces_facts", "consumes_facts", "evidence_shape_ref", "known_quirks",
            "source_pointers", "validation_status", "revalidation_plan_ref");

    private static final Set<String> TRANSPORT_FIELDS = Set.of(
            "kind", "trust_rule_ref", "shell_state_dependency_evidence");

    private static final Set<String> STEP_FIELDS = Set.of(
            "kind", "shell_context", "send", "gate_reference", "action_class",
            "expect", "timeout_s", "on_failure", "remote", "checks");

    private CapabilitySpecLoader() {
    }

    public static CapabilitySpec loadFromYaml(String yamlText) {
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(yamlText);
        if (!(loaded instanceof Map<?, ?> rawRoot)) {
            throw new CapabilityValidationException(CapabilityValidationException.MISSING_REQUIRED_FIELD,
                    "capability spec YAML did not parse to a mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) rawRoot;

        rejectUnknownFields(root.keySet(), TOP_LEVEL_FIELDS, "capability spec top level");

        String capabilityId = requireString(root, "capability_id");
        String vendor = requireString(root, "vendor");
        String platformRoleScope = requireString(root, "platform_role_scope");
        String maturityStateRaw = requireString(root, "maturity_state");

        @SuppressWarnings("unchecked")
        Map<String, Object> transport = (Map<String, Object>) root.get("transport");
        if (transport == null) {
            throw new CapabilityValidationException(CapabilityValidationException.MISSING_REQUIRED_FIELD,
                    "capability spec missing required field: transport");
        }
        rejectUnknownFields(transport.keySet(), TRANSPORT_FIELDS, "transport");
        TransportKind transportKind = TransportKind.fromSpecValue(String.valueOf(transport.get("kind")));
        boolean shellStateDependencyEvidence = Boolean.TRUE.equals(transport.get("shell_state_dependency_evidence"));

        MaturityState maturityState = MaturityState.valueOf(maturityStateRaw);

        List<CapabilityStep> steps = parseSteps(root.get("steps"));
        List<CapabilityStep> finallySteps = parseSteps(root.get("finally_steps"));

        @SuppressWarnings("unchecked")
        Map<String, Object> parserRef = (Map<String, Object>) root.get("parser_ref");
        String parserVersion = parserRef == null ? "UNKNOWN" : String.valueOf(parserRef.getOrDefault("parser_version", "UNKNOWN"));

        List<String> sourcePointers = stringList(root.get("source_pointers"));

        return new CapabilitySpec(capabilityId, vendor, platformRoleScope, transportKind, maturityState,
                steps, finallySteps, parserVersion, sourcePointers, shellStateDependencyEvidence);
    }

    public static CapabilitySpec loadFromStream(InputStream stream) {
        try (stream) {
            return loadFromYaml(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<CapabilityStep> parseSteps(Object rawSteps) {
        List<CapabilityStep> result = new ArrayList<>();
        if (rawSteps == null) {
            return result;
        }
        for (Object rawStep : (List<Object>) rawSteps) {
            Map<String, Object> stepMap = (Map<String, Object>) rawStep;
            rejectUnknownFields(stepMap.keySet(), STEP_FIELDS, "step");

            StepKind kind = StepKind.fromSpecValue(String.valueOf(stepMap.get("kind")));
            String shellContext = stepMap.get("shell_context") == null ? "not_applicable"
                    : String.valueOf(stepMap.get("shell_context"));
            String commandTemplate = stepMap.get("send") == null ? null : String.valueOf(stepMap.get("send"));
            boolean gateNotApplicable = "NOT_APPLICABLE".equals(stepMap.get("gate_reference"));

            Optional<ActionClass> declaredActionClass = stepMap.get("action_class") == null
                    ? Optional.empty()
                    : Optional.of(ActionClass.fromId(String.valueOf(stepMap.get("action_class"))));

            Object expect = stepMap.get("expect");
            Optional<String> expectRegex = Optional.empty();
            if (expect instanceof Map<?, ?> expectMap && expectMap.get("regex") != null) {
                expectRegex = Optional.of(String.valueOf(expectMap.get("regex")));
            }

            Optional<Integer> timeoutS = stepMap.get("timeout_s") == null ? Optional.empty()
                    : Optional.of(((Number) stepMap.get("timeout_s")).intValue());

            result.add(new CapabilityStep(kind, shellContext, commandTemplate, gateNotApplicable,
                    declaredActionClass, expectRegex, timeoutS));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<Object>) raw) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    private static String requireString(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if (value == null) {
            throw new CapabilityValidationException(CapabilityValidationException.MISSING_REQUIRED_FIELD,
                    "capability spec missing required field: " + field);
        }
        return String.valueOf(value);
    }

    private static void rejectUnknownFields(Set<String> actual, Set<String> allowed, String where) {
        for (String field : actual) {
            if (!allowed.contains(field)) {
                throw new CapabilityValidationException(CapabilityValidationException.UNKNOWN_SPEC_FIELD,
                        "unknown field '" + field + "' in " + where + " -- not part of the C4 §2.2 closed field set");
            }
        }
    }
}
