package com.securityexpert.nexus.ui2.capability;

/**
 * A closed gate-resolution outcome for one capability (C4 §3). Registry
 * consumers switch exhaustively over this type; there is no open "custom"
 * variant.
 */
public enum GateRequirement {
    NONE,
    OPERATOR_APPROVAL,
    DUAL_CONTROL
}
