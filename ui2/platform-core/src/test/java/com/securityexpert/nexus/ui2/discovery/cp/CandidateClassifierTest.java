package com.securityexpert.nexus.ui2.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Contract §4.3: CL-1, CL-2, CL-3 (AC-2, AC-3). */
class CandidateClassifierTest {

    @Test
    void everyMeasuredRowClassifiesToItsKind() {
        assertEquals(CandidateKind.STANDALONE_PRODUCT_GATEWAY,
                CandidateClassifier.classify(ObjectType.GATEWAY, new ClassificationFlags(true, false, false)));
        assertEquals(CandidateKind.NON_PRODUCT_INTEROPERABLE_DEVICE,
                CandidateClassifier.classify(ObjectType.GATEWAY, new ClassificationFlags(false, false, false)));
        assertEquals(CandidateKind.STANDALONE_VIRTUALIZATION_HOST,
                CandidateClassifier.classify(ObjectType.GATEWAY, new ClassificationFlags(true, true, false)));
        assertEquals(CandidateKind.STANDALONE_VIRTUAL_SYSTEM,
                CandidateClassifier.classify(ObjectType.GATEWAY, new ClassificationFlags(true, false, true)));
        assertEquals(CandidateKind.VIRTUALIZATION_CLUSTER,
                CandidateClassifier.classify(ObjectType.CLUSTER, new ClassificationFlags(true, true, false)));
        assertEquals(CandidateKind.VIRTUAL_SYSTEM_CLUSTER,
                CandidateClassifier.classify(ObjectType.CLUSTER, new ClassificationFlags(true, false, true)));
        assertEquals(CandidateKind.PLAIN_HIGH_AVAILABILITY_CLUSTER,
                CandidateClassifier.classify(ObjectType.CLUSTER, new ClassificationFlags(true, false, false)));
        assertEquals(CandidateKind.PHYSICAL_VIRTUALIZATION_CHASSIS_MEMBER,
                CandidateClassifier.classify(ObjectType.MEMBER, new ClassificationFlags(true, true, false)));
        assertEquals(CandidateKind.VIRTUAL_SYSTEM_MEMBER,
                CandidateClassifier.classify(ObjectType.MEMBER, new ClassificationFlags(true, false, true)));
        assertEquals(CandidateKind.PLAIN_CLUSTER_MEMBER,
                CandidateClassifier.classify(ObjectType.MEMBER, new ClassificationFlags(true, false, false)));
    }

    /** AC-3: an unmeasured flag combination is UNCLASSIFIED, not dropped and not the nearest kind. */
    @Test
    void unmeasuredCombinationIsUnclassifiedNotNearestKind() {
        CandidateKind kind = CandidateClassifier.classify(ObjectType.GATEWAY, new ClassificationFlags(true, true, true));
        assertEquals(CandidateKind.UNCLASSIFIED, kind);
    }

    @Test
    void everyObjectTypeHasAnUnmeasuredCombination() {
        assertEquals(CandidateKind.UNCLASSIFIED,
                CandidateClassifier.classify(ObjectType.CLUSTER, new ClassificationFlags(false, false, false)));
        assertEquals(CandidateKind.UNCLASSIFIED,
                CandidateClassifier.classify(ObjectType.MEMBER, new ClassificationFlags(false, true, true)));
    }
}
