package com.securityexpert.nexus.ui2.discovery.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Contract §11 check 9 (AC-3) / LV-1 to LV-3 vocabulary. Reflects over
 * {@link CandidateRow}'s record components — recursively unwrapping
 * {@code Optional}, following sealed permitted subtypes and reading enum
 * constant names, exactly as cp's analogous check does — so a field or
 * enum value added later is caught automatically. Also proves the second
 * half of LV-1/LV-3: no field is a function of the connection-state
 * element alone.
 */
class Check9VocabularyTest {

    /** The vocabulary §8/§9 name, plus a bounded, explicit synonym set (matches cp's check 13). */
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
                .filter(Check9VocabularyTest::containsForbiddenWord)
                .toList();

        assertFalse(identifiers.isEmpty());
        assertTrue(violations.isEmpty(), "liveness-vocabulary identifiers found: " + violations);
    }

    /**
     * LV-1/LV-3: changing only the connection-state element between two
     * otherwise-identical inputs changes only {@link CandidateRow#connectionState()}
     * on the resulting row — nothing else is derived from it.
     */
    @Test
    void noFieldIsDerivedFromConnectionStateAlone() {
        List<RawDeviceInput> variants = Fixtures.check9ConnectionStateVariants();
        CandidateRow established = CandidateRowAssembler.assemble(List.of(variants.get(0))).get(0);
        CandidateRow notEstablished = CandidateRowAssembler.assemble(List.of(variants.get(1))).get(0);

        assertFalse(established.connectionState().equals(notEstablished.connectionState()));

        assertEquals(established.stableIdentifier(), notEstablished.stableIdentifier());
        assertEquals(established.displayName(), notEstablished.displayName());
        assertEquals(established.deviceTypeMarker(), notEstablished.deviceTypeMarker());
        assertEquals(established.ownIpv4Address(), notEstablished.ownIpv4Address());
        assertEquals(established.ownIpv6Address(), notEstablished.ownIpv6Address());
        assertEquals(established.peerSerialReference(), notEstablished.peerSerialReference());
        assertEquals(established.connectionTimestamp(), notEstablished.connectionTimestamp());
        assertEquals(established.certificateStatus(), notEstablished.certificateStatus());
        assertEquals(established.certificateExpiration(), notEstablished.certificateExpiration());
        assertEquals(established.hostLink(), notEstablished.hostLink());
        assertEquals(established.sharedPolicyElementOne(), notEstablished.sharedPolicyElementOne());
        assertEquals(established.sharedPolicyElementTwo(), notEstablished.sharedPolicyElementTwo());
        assertEquals(established.sharedPolicyElementThree(), notEstablished.sharedPolicyElementThree());
        assertEquals(established.pairingOutcome(), notEstablished.pairingOutcome());
        assertEquals(established.unreciprocatedInboundClaimantSerials(), notEstablished.unreciprocatedInboundClaimantSerials());
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
