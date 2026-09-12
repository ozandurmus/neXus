package com.securityexpert.nexus.ui2.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Contract §9 check 13 / LV-1, LV-3 (AC-8). Reflects over {@link
 * CandidateRow}'s record components, recursively unwrapping {@code
 * Optional}, following sealed permitted subtypes and reading enum constant
 * names, so a field or enum value added later is caught automatically
 * rather than by extending a hand-written list of today's field names.
 */
class Check13LivenessVocabularyTest {

    /** The vocabulary contract §9 check 13 names, plus a bounded, explicit synonym set. */
    private static final Set<String> FORBIDDEN = Set.of(
            "up", "down", "online", "offline", "reachable", "unreachable", "healthy", "unhealthy",
            "live", "alive", "dead", "active", "inactive", "connected", "disconnected",
            "available", "unavailable", "communicating");

    @Test
    void candidateRowHasSomeReflectableComponents() {
        assertTrue(CandidateRow.class.getRecordComponents().length > 0);
    }

    @Test
    void candidateRowAndItsEnumsCarryNoLivenessVocabulary() {
        List<String> identifiers = new ArrayList<>();
        collect(CandidateRow.class, identifiers, new HashSet<>());

        List<String> violations = identifiers.stream()
                .filter(Check13LivenessVocabularyTest::containsForbiddenWord)
                .toList();

        assertFalse(identifiers.isEmpty());
        assertTrue(violations.isEmpty(), "liveness-vocabulary identifiers found: " + violations);
    }

    private static void collect(Class<?> type, List<String> identifiers, Set<Class<?>> visited) {
        if (!visited.add(type)) {
            return;
        }
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                identifiers.add(((Enum<?>) constant).name());
            }
            return;
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                identifiers.add(component.getName());
                Class<?> componentType = unwrapOptional(component);
                if (isDomainType(componentType)) {
                    collect(componentType, identifiers, visited);
                }
            }
            return;
        }
        if (type.isSealed()) {
            for (Class<?> permitted : type.getPermittedSubclasses()) {
                collect(permitted, identifiers, visited);
            }
        }
    }

    private static Class<?> unwrapOptional(RecordComponent component) {
        if (!java.util.Optional.class.equals(component.getType())) {
            return component.getType();
        }
        Type generic = component.getGenericType();
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> argument) {
            return argument;
        }
        return Object.class;
    }

    private static boolean isDomainType(Class<?> type) {
        return type.getPackageName().startsWith("com.securityexpert.nexus.ui2");
    }

    private static boolean containsForbiddenWord(String identifier) {
        for (String word : identifier.split("(?<=[a-z0-9])(?=[A-Z])|_")) {
            if (FORBIDDEN.contains(word.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
