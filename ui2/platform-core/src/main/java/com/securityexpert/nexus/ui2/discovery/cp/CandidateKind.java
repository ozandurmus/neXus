package com.securityexpert.nexus.ui2.discovery.cp;

/**
 * The ten measured candidate kinds of contract §4.2, plus the two outcomes
 * CL-2 requires for an object that maps to zero or to more than one of
 * them. {@code UNCLASSIFIED} and {@code AMBIGUOUS} are outcomes, not
 * errors (CL-3): a run that produces them still completes and returns its
 * full candidate set.
 */
public enum CandidateKind {
    /** K-1: standalone product gateway. */
    STANDALONE_PRODUCT_GATEWAY,
    /** K-2: non-product interoperable device. */
    NON_PRODUCT_INTEROPERABLE_DEVICE,
    /** K-3: standalone virtualization host. */
    STANDALONE_VIRTUALIZATION_HOST,
    /** K-4: standalone virtual system. */
    STANDALONE_VIRTUAL_SYSTEM,
    /** K-5: virtualization cluster. */
    VIRTUALIZATION_CLUSTER,
    /** K-6: virtual-system cluster. */
    VIRTUAL_SYSTEM_CLUSTER,
    /** K-7: plain high-availability cluster. */
    PLAIN_HIGH_AVAILABILITY_CLUSTER,
    /** K-8: physical virtualization chassis member. */
    PHYSICAL_VIRTUALIZATION_CHASSIS_MEMBER,
    /** K-9: virtual-system member. */
    VIRTUAL_SYSTEM_MEMBER,
    /** K-10: plain cluster member. */
    PLAIN_CLUSTER_MEMBER,
    /**
     * CL-2: a flag combination this contract has not measured. Returned,
     * shown and marked; never dropped and never assigned to the nearest
     * kind.
     */
    UNCLASSIFIED,
    /**
     * CL-2: a flag combination that matched more than one kind row — a
     * defect in the classifier or a change in the vendor's object model.
     * Never resolved by preference order.
     */
    AMBIGUOUS
}
