package com.securityexpert.nexus.ui2.worker.compliance.catalog;

import java.util.List;

import com.securityexpert.nexus.ui2.worker.compliance.model.AssertionRule;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceControl;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceFramework;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvidenceRequirement;
import com.securityexpert.nexus.ui2.worker.compliance.model.FrameworkMapping;
import com.securityexpert.nexus.ui2.worker.compliance.model.Severity;
import com.securityexpert.nexus.ui2.worker.compliance.model.VendorBinding;

/**
 * Compliance Catalog for Palo Alto firewalls running PAN-OS.
 * Grounded on CIS Palo Alto Firewall 10.x/11.x Benchmark v1.1.0,
 * PCI-DSS v4.0.1, NIST SP 800-53 Rev 5, and Financial Baseline (BDDK).
 */
public final class PaloAltoComplianceCatalog {

    /**
     * Bump on ANY change to a control, its assertion or the evaluator's logic: the service caches evaluations by
     * configuration hash and this version (V59, ComplianceService rule-set fingerprint); an unbumped logic change stays
     * invisible until the 6-hour backstop re-evaluates.
     */
    public static final String CATALOG_VERSION = "2026.09.1";

    private PaloAltoComplianceCatalog() {
    }

    public static List<ComplianceControl> getControls() {
        return List.of(
            // 1. Legal Warning Notice on Login Banner
            new ComplianceControl(
                "pan_cis_1_2_1_login_banner",
                "Configure Legal Warning Notice on Login Banner",
                "An explicit legal warning banner warns unauthorized users that access is monitored and subject to prosecution.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.2.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "1.2.1", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AC-8", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Warning Banners §1.3", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("system", "login-banner", "system.login_banner", "PAN-CMD-0010"),
                        AssertionRule.present()
                    )
                )
            ),

            // 2. Administrative Inactivity Timeout (<= 15 min)
            new ComplianceControl(
                "pan_cis_1_2_2_idle_timeout",
                "Enforce Administrative Session Inactivity Timeout (<= 15 min)",
                "Terminates unattended administrative sessions to mitigate unauthorized physical and shoulder-surfing access.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.2.2", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "8.2.8", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AC-11", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Session Governance §5.2", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("management", "idle-timeout", "setting.management.idle_timeout", "PAN-CMD-0012"),
                        AssertionRule.lte(15)
                    )
                )
            ),

            // 3. Account Lockout Failed Attempts (<= 5)
            new ComplianceControl(
                "pan_cis_1_2_3_account_lockout_attempts",
                "Enforce Account Lockout After Failed Attempts (<= 5)",
                "Locks out administrative accounts after consecutive failed attempts to prevent automated online brute-forcing.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.2.3", "1.1.0"),
                    FrameworkMapping.stricterThan(ComplianceFramework.PCI_DSS, "8.3.4", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AC-7", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Brute Force Protection §2.4", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("management", "failed-attempts", "setting.management.failed_attempts", "PAN-CMD-0013"),
                        AssertionRule.lte(5)
                    )
                )
            ),

            // 4. Account Lockout Duration (>= 30 min)
            new ComplianceControl(
                "pan_cis_1_2_4_account_lockout_duration",
                "Enforce Account Lockout Duration (>= 30 minutes)",
                "Ensures locked accounts remain inaccessible long enough for security operations to investigate authentication anomalies.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.2.4", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "8.3.4", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AC-7", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("management", "lockout-time", "setting.management.lockout_time", "PAN-CMD-0014"),
                        AssertionRule.gte(30)
                    )
                )
            ),

            // 5. Minimum Password Length (>= 12 characters)
            new ComplianceControl(
                "pan_cis_1_2_5_password_min_length",
                "Enforce Minimum Password Length (>= 12 characters)",
                "Adequate password length is the foundational defense against credential spraying and dictionary attacks.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.2.5", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "8.3.6", "4.0.1"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "IA-5(1)", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Access Control §3.1", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("password_complexity", "minimum-length", "setting.management.password_complexity.minimum_length", "PAN-CMD-0015"),
                        AssertionRule.gte(12)
                    )
                )
            ),

            // 6. Password Complexity Requirements
            new ComplianceControl(
                "pan_cis_1_2_6_password_complexity",
                "Enforce Password Complexity Requirements",
                "Requiring uppercase, lowercase, numeric, and special character combinations resists automated cracking.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.2.6", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "8.3.6", "4.0.1"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "IA-5(1)", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("password_complexity", "enabled", "setting.management.password_complexity.enabled", "PAN-CMD-0016"),
                        AssertionRule.equalsStr("yes")
                    )
                )
            ),

            // 7. Password History Restriction (>= 5 generations)
            new ComplianceControl(
                "pan_cis_1_2_7_password_history",
                "Enforce Password History Restriction (>= 5 generations)",
                "Prevents immediate cycling back to previously compromised or known passwords.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.2.7", "1.1.0"),
                    FrameworkMapping.stricterThan(ComplianceFramework.PCI_DSS, "8.3.7", "4.0.1"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "IA-5(1)", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("password_complexity", "password-history", "setting.management.password_complexity.password_history", "PAN-CMD-0017"),
                        AssertionRule.gte(5)
                    )
                )
            ),

            // 8. Password Expiration Period (<= 90 days)
            new ComplianceControl(
                "pan_cis_1_2_8_password_expiration",
                "Enforce Password Expiration Period (<= 90 days)",
                "Limits the operational exposure window of aging administrative credentials.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.2.8", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "8.3.9", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Password Lifecycle §3.3", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("password_complexity", "expiration-period", "setting.management.password_complexity.expiration_period", "PAN-CMD-0018"),
                        AssertionRule.lte(90)
                    )
                )
            ),

            // 9. Insecure Telnet Service Disabled
            new ComplianceControl(
                "pan_cis_1_3_1_telnet_disabled",
                "Ensure Insecure Telnet Service Is Disabled",
                "Telnet transmits credentials and sessions in plaintext, creating severe eavesdropping and credential compromise risk.",
                Severity.CRITICAL,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.3.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.4", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "CM-7", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("service", "disable-telnet", "system.service.disable_telnet", "PAN-CMD-0020"),
                        AssertionRule.equalsStr("yes")
                    )
                )
            ),

            // 10. Insecure Cleartext HTTP Disabled
            new ComplianceControl(
                "pan_cis_1_3_2_http_disabled",
                "Ensure Insecure Cleartext HTTP Service Is Disabled",
                "Cleartext HTTP administrative sessions expose session cookies and credentials across unencrypted channels.",
                Severity.CRITICAL,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.3.2", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.3", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "SC-8", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("service", "disable-http", "system.service.disable_http", "PAN-CMD-0021"),
                        AssertionRule.equalsStr("yes")
                    )
                )
            ),

            // 11. SSH Protocol Version 2 Enforced
            new ComplianceControl(
                "pan_cis_1_3_3_ssh_protocol_v2",
                "Enforce SSH Protocol Version 2 Only",
                "SSH protocol version 1 has proven cryptographic vulnerabilities and must never be permitted.",
                Severity.CRITICAL,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.3.3", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.5", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "SC-8", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Transport Security §1.1", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("system", "ssh-version", "system.ssh.version", "PAN-CMD-0022"),
                        AssertionRule.present()
                    )
                )
            ),

            // 12. Web Management Enforces TLS 1.2+
            new ComplianceControl(
                "pan_cis_1_3_4_tls_version",
                "Ensure Web Management Enforces TLS 1.2 or Greater",
                "Legacy TLS 1.0/1.1 protocols suffer from cryptographic weaknesses and CBC-mode vulnerabilities.",
                Severity.CRITICAL,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.3.4", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.5", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "SC-13", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Cryptographic Baseline §2.1", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("ssl_tls_service_profile", "tls-version-min", "ssl.tls_service_profile.min_version", "PAN-CMD-0023"),
                        AssertionRule.matches("(?i)(tls1[-_.]?[23]|1\\.[23]|max)")
                    )
                )
            ),

            // 13. Management Access Restricted to Permitted IP Addresses
            new ComplianceControl(
                "pan_cis_1_3_5_permitted_ip_addresses",
                "Restrict Administrative Access to Dedicated Permitted IPs",
                "Administrative portals must reject connections from general user segments and accept only authorized bastions.",
                Severity.CRITICAL,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "1.3.5", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "1.3.1", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AC-17", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Segment Isolation §1.2", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("permitted_ip", "permitted-ip", "system.permitted_ip", "PAN-CMD-0025"),
                        AssertionRule.present()
                    )
                )
            ),

            // 14. Dual Redundant NTP Servers Configured
            new ComplianceControl(
                "pan_cis_2_1_1_ntp_redundancy",
                "Configure Dual Redundant NTP Synchronization",
                "Accurate, redundant time sync is critical for audit trail correlation, certificate validation, and forensic readiness.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.1.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "10.6.1", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AU-8", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Time Synchronization §4.2", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("ntp_servers", "ntp-servers", "system.ntp_servers", "PAN-CMD-0030"),
                        AssertionRule.present()
                    )
                )
            ),

            // 15. System Timezone Configured
            new ComplianceControl(
                "pan_cis_2_1_2_timezone_configured",
                "Ensure System Timezone Is Explicitly Configured",
                "Consistent timezone configuration eliminates ambiguity in cross-system event correlation and incident logs.",
                Severity.LOW,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.1.2", "1.1.0"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "AU-8", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("system", "timezone", "system.timezone", "PAN-CMD-0032"),
                        AssertionRule.present()
                    )
                )
            ),

            // 16. Redundant DNS Resolution Servers Configured
            new ComplianceControl(
                "pan_cis_2_1_3_dns_servers",
                "Configure Redundant DNS Resolution Servers",
                "Redundant DNS servers ensure reliable hostname resolution for SIEM destinations, CRL checking, and NTP lookups.",
                Severity.LOW,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.1.3", "1.1.0"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "SC-20", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("dns_setting", "servers", "system.dns_setting.servers", "PAN-CMD-0034"),
                        AssertionRule.present()
                    )
                )
            ),

            // 17. Remote Syslog / SIEM Forwarding Configured
            new ComplianceControl(
                "pan_cis_2_2_1_syslog_forwarding",
                "Configure Central Remote Syslog / SIEM Forwarding",
                "Forwarding audit records to external SIEM platforms prevents local log tampering and enables correlation.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.2.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "10.2.1", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AU-4", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Audit Logging §6.1", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("log_settings", "syslog", "shared.log_settings.syslog", "PAN-CMD-0040"),
                        AssertionRule.present()
                    )
                )
            ),

            // 18. System Event Log Forwarding Profile Configured
            new ComplianceControl(
                "pan_cis_2_2_2_system_log_forwarding",
                "Ensure System Event Logs Forwarded to Central Syslog",
                "Guarantees that hardware events, daemon crashes, and system state transitions are recorded externally.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.2.2", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "10.2.1", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AU-6", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("log_settings", "system", "shared.log_settings.system", "PAN-CMD-0042"),
                        AssertionRule.present()
                    )
                )
            ),

            // 19. Configuration Audit Log Forwarding Profile Configured
            new ComplianceControl(
                "pan_cis_2_2_3_config_log_forwarding",
                "Ensure Configuration Audit Logs Forwarded to Central Syslog",
                "Admin commits, security policy edits, and administrative overrides must be preserved on tamper-proof SIEM loggers.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.2.3", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "10.2.2", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AU-3", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Audit Logging §6.1", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("log_settings", "config", "shared.log_settings.config", "PAN-CMD-0044"),
                        AssertionRule.present()
                    )
                )
            ),

            // 20. High Availability HA1 Control Link Encryption
            new ComplianceControl(
                "pan_cis_2_3_1_ha_encryption",
                "Ensure High Availability Control Link (HA1) Encryption Is Enabled",
                "Encrypting the HA1 heartbeat and configuration synchronization link prevents packet eavesdropping between peers.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.3.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.5", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "SC-8", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("high_availability", "encryption", "deviceconfig.high_availability.encryption", "PAN-CMD-0050"),
                        AssertionRule.present()
                    )
                )
            ),

            // 21. DATA_UNAVAILABLE: SNMPv3 Cryptographic Auth and Privacy Enforced
            new ComplianceControl(
                "pan_cis_4_1_1_snmpv3_only",
                "Enforce SNMPv3 with Cryptographic Authentication and Privacy",
                "Legacy SNMPv1 and SNMPv2c transmit community strings in cleartext across the local network.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "4.1.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.2", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "CM-7", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("snmp_setting", "snmpv3", "system.snmp.v3_users", "PAN-GATE-SNMP3-01"),
                        AssertionRule.present()
                    )
                )
            ),

            // 22. DATA_UNAVAILABLE: Unused Network Interfaces Administratively Down
            new ComplianceControl(
                "pan_cis_4_1_2_unused_interfaces_down",
                "Ensure Unused Network Interfaces Are Administratively Down",
                "Unconfigured active physical ports provide unmonitored physical ingress points into the network segment.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "4.1.2", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "1.2.2", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "CM-7", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("network_interface", "link-state", "network.interface.down", "PAN-GATE-IFACE-01"),
                        AssertionRule.present()
                    )
                )
            ),

            // 23. DATA_UNAVAILABLE: Dynamic DNS (DDNS) Client Disabled
            new ComplianceControl(
                "pan_cis_4_1_3_ddns_disabled",
                "Ensure Dynamic DNS (DDNS) Client Is Disabled",
                "Unapproved dynamic DNS updates can expose internal management addresses or misdirect traffic.",
                Severity.LOW,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "4.1.3", "1.1.0"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "CM-7", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("ddns", "ddns-client", "system.ddns.state", "PAN-GATE-DDNS-01"),
                        AssertionRule.absent()
                    )
                )
            ),

            // 24. DATA_UNAVAILABLE: Weak SSH/TLS Ciphers Disabled
            new ComplianceControl(
                "pan_cis_4_1_4_ssh_strong_ciphers",
                "Disable Weak SSH and TLS Ciphers (CBC, 3DES, RC4)",
                "Legacy CBC mode and RC4 ciphers are vulnerable to cryptographic plaintext recovery attacks.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "4.1.4", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.5", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "SC-13", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Cryptographic Baseline §2.1", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "palo_alto",
                        "pan_os",
                        EvidenceRequirement.of("ssh_ciphers", "ciphers", "system.ssh.ciphers", "PAN-GATE-CIPHERS-01"),
                        AssertionRule.noneMatch("(?i)(cbc|3des|arcfour|rc4)")
                    )
                )
            )
        );
    }
}
