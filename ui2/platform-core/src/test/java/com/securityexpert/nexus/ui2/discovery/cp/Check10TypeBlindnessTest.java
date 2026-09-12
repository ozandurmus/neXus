package com.securityexpert.nexus.ui2.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Contract §9 check 10 / HR-4 (AC-5): host resolution computed with no
 * access to object type produces the same outcome as the same computation
 * with object type available.
 */
class Check10TypeBlindnessTest {

    /**
     * HR-4 as a structural fact: {@link HostResolution#resolve} takes only
     * the two address fields. A test on its signature, not merely its
     * behaviour, is what stops a future change quietly adding an object-
     * type parameter back in.
     */
    @Test
    void resolveMethodHasNoObjectTypeOrKindParameter() throws NoSuchMethodException {
        Method resolve = HostResolution.class.getMethod("resolve", Address.class, Address.class);
        assertArrayEquals(new Class<?>[] {Address.class, Address.class}, resolve.getParameterTypes());
    }

    /**
     * Behavioural corroboration: candidates of different object types that
     * share the same two address fields resolve identically. host-1
     * (GATEWAY) and chassis-member-1 (MEMBER) are both a physical device's
     * own management/own address pair; vs-1 (GATEWAY) and vs-member-1
     * (MEMBER) are both a hosted virtual system's pair; interop-1 (GATEWAY)
     * and vcluster-1 (CLUSTER) both have an absent management address.
     */
    @Test
    void candidatesOfDifferentObjectTypesWithTheSameAddressPairResolveIdentically() {
        Map<String, CandidateRow> rows = CandidateRowAssembler.assemble(Fixtures.allInputs()).stream()
                .collect(Collectors.toMap(r -> r.key().stableIdentifier().value(), Function.identity()));

        assertEquals(ObjectType.GATEWAY, rows.get("host-1").objectType());
        assertEquals(ObjectType.MEMBER, rows.get("chassis-member-1").objectType());
        assertEquals(rows.get("host-1").hostResolution(), rows.get("chassis-member-1").hostResolution());
        assertEquals(HostResolution.PHYSICAL_DEVICE, rows.get("host-1").hostResolution());

        assertEquals(ObjectType.GATEWAY, rows.get("vs-1").objectType());
        assertEquals(ObjectType.MEMBER, rows.get("vs-member-1").objectType());
        assertEquals(rows.get("vs-1").hostResolution(), rows.get("vs-member-1").hostResolution());
        assertEquals(HostResolution.VIRTUAL_SYSTEM_HOSTED, rows.get("vs-1").hostResolution());

        assertEquals(ObjectType.GATEWAY, rows.get("interop-1").objectType());
        assertEquals(ObjectType.CLUSTER, rows.get("vcluster-1").objectType());
        assertEquals(rows.get("interop-1").hostResolution(), rows.get("vcluster-1").hostResolution());
        assertEquals(HostResolution.NOT_APPLICABLE, rows.get("interop-1").hostResolution());
    }

    /** A run with the object type deliberately swapped on every input still resolves identically. */
    @Test
    void resolutionComputedWithoutObjectTypeMatchesResolutionComputedWithIt() {
        List<RawCandidateInput> original = Fixtures.allInputs();
        List<RawCandidateInput> typeSwapped = original.stream()
                .map(in -> new RawCandidateInput(in.key(), swap(in.objectType()), in.flags(), in.displayName(),
                        in.ownAddress(), in.managementAddress(), in.clusterReference(), in.model(),
                        in.softwareVersion(), in.managementPlaneConnectionState()))
                .toList();

        Map<String, HostResolution> before = original.stream()
                .collect(Collectors.toMap(in -> in.key().stableIdentifier().value(),
                        in -> HostResolution.resolve(in.managementAddress(), in.ownAddress())));
        Map<String, HostResolution> after = typeSwapped.stream()
                .collect(Collectors.toMap(in -> in.key().stableIdentifier().value(),
                        in -> HostResolution.resolve(in.managementAddress(), in.ownAddress())));

        assertEquals(before, after);
    }

    private static ObjectType swap(ObjectType type) {
        return switch (type) {
            case GATEWAY -> ObjectType.MEMBER;
            case CLUSTER -> ObjectType.GATEWAY;
            case MEMBER -> ObjectType.CLUSTER;
        };
    }
}
