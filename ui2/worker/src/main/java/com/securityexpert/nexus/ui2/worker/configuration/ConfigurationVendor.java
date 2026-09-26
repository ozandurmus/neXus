package com.securityexpert.nexus.ui2.worker.configuration;

/** Vendors supported by {@code configuration_collect}. */
public enum ConfigurationVendor {
    CHECK_POINT,
    PALO_ALTO,
    /** FortiGate (FORTINET_CONTRACT.md, PO 2026-09-26): the top-level "show" parsed into config blocks. */
    FORTINET,
    CISCO_ASA
}
