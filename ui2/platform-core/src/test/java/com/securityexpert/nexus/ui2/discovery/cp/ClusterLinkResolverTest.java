package com.securityexpert.nexus.ui2.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.OpaqueId;

/** Contract §5.3: MC-1, MC-2. */
class ClusterLinkResolverTest {

    @Test
    void identifierPresentLinks() {
        ClusterReference ref = new ClusterReference(Optional.of(OpaqueId.of("cluster-1")), Optional.of("fixture-name"));
        ClusterLink.Linked linked = assertInstanceOf(ClusterLink.Linked.class, ClusterLinkResolver.resolve(Optional.of(ref)));
        org.junit.jupiter.api.Assertions.assertEquals("cluster-1", linked.clusterStableIdentifier().value());
    }

    @Test
    void referenceAbsentIsNotEvaluable() {
        assertInstanceOf(ClusterLink.NotEvaluable.class, ClusterLinkResolver.resolve(Optional.empty()));
    }

    /** MC-2: a display name with no identifier is NOT_EVALUABLE, never a name-based fallback. */
    @Test
    void identifierMissingIsNotEvaluableEvenWithADisplayName() {
        ClusterReference ref = new ClusterReference(Optional.empty(), Optional.of("fixture-name"));
        assertInstanceOf(ClusterLink.NotEvaluable.class, ClusterLinkResolver.resolve(Optional.of(ref)));
    }
}
