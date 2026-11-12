package com.securityexpert.nexus.ui2.service.failover;

import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;

/**
 * Per-vendor facts the failover path branches on. Only Check Point and Palo Alto have readiness and execution; Cisco ASA
 * and FortiGate pairs are listed as "readiness not available yet" and nothing else (no store row, no job).
 */
public enum FailoverVendor {
    CHECK_POINT("check_point", "Check Point", true, null, "STANDBY", InventoryHaFact.SOURCE_CP_CPHAPROB_STAT, true, true),
    PALO_ALTO("palo_alto", "Palo Alto", false, "pan_xml_api", "PASSIVE", InventoryHaFact.SOURCE_PAN_HIGH_AVAILABILITY_STATE, true, false),
    CISCO_ASA("cisco_asa", "Cisco ASA", true, "ssh_exec", "STANDBY", "asa_show_failover", false, false),
    FORTINET("fortinet", "FortiGate", true, "ssh_exec", "STANDBY", "fgt_get_system_ha_status", false, false);

    private final String dbValue;
    private final String label;
    private final boolean sshTrust;
    private final String transportKind;
    private final String standbyRole;
    private final String haFactSource;
    private final boolean available;
    private final boolean resume;

    FailoverVendor(String dbValue, String label, boolean sshTrust, String transportKind, String standbyRole,
            String haFactSource, boolean available, boolean resume) {
        this.dbValue = dbValue; this.label = label; this.sshTrust = sshTrust; this.transportKind = transportKind;
        this.standbyRole = standbyRole; this.haFactSource = haFactSource; this.available = available; this.resume = resume;
    }

    public String dbValue() { return dbValue; }
    public String label() { return label; }
    public boolean requiresSshTrust() { return sshTrust; }
    /** The endpoint transport kind every member must have; empty when the vendor carries no such check today (Check Point). */
    public Optional<String> requiredTransportKind() { return Optional.ofNullable(transportKind); }
    public String standbyRoleWord() { return standbyRole; }
    public String haFactSource() { return haFactSource; }
    public boolean readinessAvailable() { return available; }
    public boolean executionAvailable() { return available; }
    public boolean resumeSupported() { return resume; }

    public static Optional<FailoverVendor> of(String dbValue) {
        if (dbValue == null) return Optional.empty();
        for (FailoverVendor v : values()) if (v.dbValue.equals(dbValue)) return Optional.of(v);
        return Optional.empty();
    }
}
