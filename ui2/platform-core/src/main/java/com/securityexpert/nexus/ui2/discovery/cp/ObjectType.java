package com.securityexpert.nexus.ui2.discovery.cp;

/**
 * The three object types the management plane returns (contract §4.1).
 * Supplied by the enumeration context that issued the per-type query
 * (§T-2), never inferred from a field on the object itself.
 */
public enum ObjectType {
    GATEWAY,
    CLUSTER,
    MEMBER
}
