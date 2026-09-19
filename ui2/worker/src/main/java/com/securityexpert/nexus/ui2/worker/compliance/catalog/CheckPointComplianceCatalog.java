package com.securityexpert.nexus.ui2.worker.compliance.catalog;

import java.util.List;

import com.securityexpert.nexus.ui2.worker.compliance.model.AssertionRule;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceControl;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceFramework;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvidenceRequirement;
import com.securityexpert.nexus.ui2.worker.compliance.model.FrameworkMapping;
import com.securityexpert.nexus.ui2.worker.compliance.model.Severity;
import com.securityexpert.nexus.ui2.worker.compliance.model.VendorBinding;

public final class CheckPointComplianceCatalog {

    public static final String CATALOG_VERSION = "2026.09.1";

    private CheckPointComplianceCatalog() {
    }

    public static List<ComplianceControl> getControls() {
        return List.of(
            // 1. Password Min Length
            new ComplianceControl(
                "gaia_cis_2_1_1_password_min_length",
                "Enforce Minimum Password Length (>= 12 characters)",
                "Adequate password length is the first line of defense against brute force and dictionary credential attacks.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.1.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "8.3.6", "4.0.1"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "IA-5(1)", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Access Control §3.1", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("password_policy", "password-controls min-password-length", "gaia.password_policy.min_length", "CP-CMD-0012"),
                        AssertionRule.gte(12)
                    )
                )
            ),

            // 2. Password Complexity
            new ComplianceControl(
                "gaia_cis_2_1_2_password_complexity",
                "Enforce Password Complexity Requirements",
                "Requiring uppercase, lowercase, digits, and special characters hinders automated cracking tools.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.1.2", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "8.3.6", "4.0.1"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "IA-5(1)", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("password_policy", "password-controls complexity", "gaia.password_policy.complexity", "CP-CMD-0012"),
                        AssertionRule.equalsStr("on")
                    )
                )
            ),

            // 3. Password History
            new ComplianceControl(
                "gaia_cis_2_1_3_password_history",
                "Enforce Password History Restriction (>= 5 generations)",
                "Prevents immediate cycling back to previously compromised or weak passwords.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.1.3", "1.1.0"),
                    FrameworkMapping.stricterThan(ComplianceFramework.PCI_DSS, "8.3.7", "4.0.1"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "IA-5(1)", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("password_policy", "password-controls history", "gaia.password_policy.history", "CP-CMD-0012"),
                        AssertionRule.gte(5)
                    )
                )
            ),

            // 4. Account Lockout Threshold
            new ComplianceControl(
                "gaia_cis_2_1_4_account_lockout_attempts",
                "Enforce Account Lockout After Failed Attempts (<= 5)",
                "Locks out accounts after consecutive failed login attempts to prevent automated online brute forcing.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.1.4", "1.1.0"),
                    FrameworkMapping.stricterThan(ComplianceFramework.PCI_DSS, "8.3.4", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AC-7", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Brute Force Protection §2.4", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("password_policy", "password-controls lockout-threshold", "gaia.password_policy.lockout_threshold", "CP-CMD-0012"),
                        AssertionRule.lte(5)
                    )
                )
            ),

            // 5. Account Lockout Duration
            new ComplianceControl(
                "gaia_cis_2_1_5_account_lockout_duration",
                "Enforce Account Lockout Duration (>= 30 minutes)",
                "Ensures locked accounts remain inaccessible long enough for security operations to notice.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.1.5", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "8.3.4", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AC-7", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("password_policy", "password-controls lockout-duration", "gaia.password_policy.lockout_duration", "CP-CMD-0012"),
                        AssertionRule.gte(30)
                    )
                )
            ),

            // 6. Inactivity Session Timeout
            new ComplianceControl(
                "gaia_cis_2_5_2_session_timeout",
                "Enforce Administrative Session Inactivity Timeout (<= 10 min)",
                "Terminates unattended administrative sessions to mitigate physical and shoulder-surfing compromise.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.5.2", "1.1.0"),
                    FrameworkMapping.stricterThan(ComplianceFramework.PCI_DSS, "8.2.8", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AC-11", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Session Governance §5.2", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("clienv", "clienv inactivity-timeout", "gaia.clienv.timeout", "CP-CMD-0015"),
                        AssertionRule.lte(10)
                    )
                )
            ),

            // 7. SSH Protocol v2 Only
            new ComplianceControl(
                "gaia_cis_2_5_3_ssh_protocol_v2",
                "Enforce SSH Protocol Version 2 Only",
                "SSH protocol version 1 suffers from fundamental cryptographic flaws and must be disabled.",
                Severity.CRITICAL,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.5.3", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.5", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "SC-8", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Transport Security §1.1", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("ssh", "sshd protocol", "gaia.sshd.protocol", "CP-CMD-0018"),
                        AssertionRule.equalsStr("2")
                    )
                )
            ),

            // 8. Telnet Service Disabled
            new ComplianceControl(
                "gaia_cis_2_5_6_telnet_disabled",
                "Ensure Insecure Telnet Service Is Disabled",
                "Telnet transmits credentials in cleartext and presents an extreme eavesdropping risk.",
                Severity.CRITICAL,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.5.6", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.4", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "CM-7", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("telnet", "telnet server", "gaia.telnet.state", "CP-CMD-0019"),
                        AssertionRule.absent()
                    )
                )
            ),

            // 9. HTTP Disabled / HTTPS Only
            new ComplianceControl(
                "gaia_cis_2_5_5_web_https_only",
                "Ensure Web Management Enforces HTTPS Only",
                "Cleartext HTTP administrative access exposes session cookies and administrative passwords.",
                Severity.CRITICAL,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.5.5", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.3", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "SC-8", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("web", "web ssl-port", "gaia.web.ssl_port", "CP-CMD-0021"),
                        AssertionRule.present()
                    )
                )
            ),

            // 10. Non-default Admin Username
            new ComplianceControl(
                "gaia_cis_2_1_8_non_default_admin",
                "Configure Non-Default Administrative Username",
                "Standard administrative accounts (e.g. admin) are constant targets for automated credential stuffing.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.1.8", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.1", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AC-2", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("expert_password_hash", "expert-password-hash", "gaia.expert.hash", "CP-CMD-0025"),
                        AssertionRule.present()
                    )
                )
            ),

            // 11. Dual Redundant NTP Servers
            new ComplianceControl(
                "gaia_cis_2_3_1_ntp_redundancy",
                "Configure Dual Redundant NTP Servers",
                "Accurate, redundant time sync is critical for audit trail correlation, certificate validation and forensic integrity.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.3.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "10.6.1", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AU-8", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Time Synchronization §4.2", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("ntp", "ntp server", "gaia.ntp.servers", "CP-CMD-0028"),
                        AssertionRule.count_gte(2)
                    )
                )
            ),

            // 12. Remote Syslog Configured
            new ComplianceControl(
                "gaia_cis_2_4_1_syslog_server",
                "Configure Central Remote Syslog / SIEM Forwarding",
                "Forwarding audit records to an external SIEM prevents local log tampering and enables correlation.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.4.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "10.2.1", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AU-4", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Audit Logging §6.1", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("syslog", "syslog server", "gaia.syslog.servers", "CP-CMD-0030"),
                        AssertionRule.present()
                    )
                )
            ),

            // 13. Audit Log Rotation and Quota
            new ComplianceControl(
                "gaia_cis_2_4_2_log_quota",
                "Configure Audit Log Rotation and Quota Limits",
                "Prevents system disk exhaustion caused by unchecked log volume growth.",
                Severity.LOW,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.4.2", "1.1.0"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "AU-4", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("format", "format date-format", "gaia.format.log", "CP-CMD-0033"),
                        AssertionRule.present()
                    )
                )
            ),

            // 14. Core Dump Generation Restricted
            new ComplianceControl(
                "gaia_cis_2_4_3_core_dump_restriction",
                "Restrict Process Core Dump Storage and Total Quota",
                "Unbounded core dumps can consume storage and potentially leak process memory secrets.",
                Severity.LOW,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.4.3", "1.1.0"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "CM-6", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("core_dump", "core-dump total", "gaia.coredump.total", "CP-CMD-0035"),
                        AssertionRule.present()
                    )
                )
            ),

            // 15. Legal Notice Login Banner
            new ComplianceControl(
                "gaia_cis_2_2_1_login_banner",
                "Configure Legal Warning Notice on Login Banner",
                "An explicit legal banner warns unauthorized users that access is monitored and subject to prosecution.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.2.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "1.2.1", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AC-8", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Warning Banners §1.3", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("message", "message banner", "gaia.message.banner", "CP-CMD-0040"),
                        AssertionRule.equalsStr("on")
                    )
                )
            ),

            // 16. ARP Proxy & Cache Protection
            new ComplianceControl(
                "gaia_cis_2_7_2_arp_cache_protection",
                "Configure Bounded ARP Cache and Proxy Protection",
                "Prevents ARP table saturation and cache poisoning attacks in large layer-2 segments.",
                Severity.LOW,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.7.2", "1.1.0"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "SC-5", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("arp", "arp table cache-size", "gaia.arp.cache_size", "CP-CMD-0045"),
                        AssertionRule.gte(1024)
                    )
                )
            ),

            // 17. IP Conflict Monitoring Enabled
            new ComplianceControl(
                "gaia_cis_2_7_3_ip_conflict_monitor",
                "Enable IP Address Conflict Monitoring",
                "Detects duplicate IP addresses on network interfaces before routing blackholes occur.",
                Severity.LOW,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.7.3", "1.1.0"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "CM-7", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("ip_conflicts_monitor", "ip-conflicts-monitor state", "gaia.ip_conflict.state", "CP-CMD-0048"),
                        AssertionRule.equalsStr("off")
                    )
                )
            ),

            // 18. Clienv Debug Mode Disabled
            new ComplianceControl(
                "gaia_cis_2_7_4_clienv_debug_disabled",
                "Ensure CLI Debug Mode Is Disabled in Production",
                "Debug logging in interactive shells can expose sensitive commands and consume CPU resources.",
                Severity.LOW,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.7.4", "1.1.0"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "CM-7", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("clienv", "clienv debug", "gaia.clienv.debug", "CP-CMD-0050"),
                        AssertionRule.equalsStr("0")
                    )
                )
            ),

            // 19. Grub2 Boot Password Configured
            new ComplianceControl(
                "gaia_cis_2_1_9_grub2_password",
                "Configure GRUB2 Bootloader Password",
                "Prevents unauthorized physical boot manipulation and single-user root recovery bypass.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.1.9", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "PE-3", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("grub2_password_hash", "grub2-password-hash", "gaia.grub2.hash", "CP-CMD-0055"),
                        AssertionRule.present()
                    )
                )
            ),

            // 20. Installer Self-Update Policy
            new ComplianceControl(
                "gaia_cis_2_8_1_installer_policy",
                "Configure Check Point Deployment Agent Auto-Update Check",
                "Regular verification ensures firmware packages and security hotfixes are tracked.",
                Severity.LOW,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.8.1", "1.1.0"),
                    FrameworkMapping.contributesTo(ComplianceFramework.NIST_800_53, "SI-2", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("installer_policy", "installer policy check-for-updates-period", "gaia.installer.period", "CP-CMD-0060"),
                        AssertionRule.present()
                    )
                )
            ),

            // 21. DATA_UNAVAILABLE: Weak SSH Ciphers Disabled
            new ComplianceControl(
                "gaia_cis_2_5_4_ssh_strong_ciphers",
                "Disable Weak SSH Ciphers (CBC, 3DES, RC4)",
                "Legacy CBC mode and RC4 ciphers are vulnerable to plaintext recovery and POODLE-style exploits.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.5.4", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.5", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "SC-13", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Cryptographic Baseline §2.1", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("ssh_ciphers", "ssh ciphers", "gaia.ssh.ciphers", "CP-GATE-CIPHERS-01"),
                        AssertionRule.noneMatch("(?i)(cbc|3des|arcfour|rc4)")
                    )
                )
            ),

            // 22. DATA_UNAVAILABLE: SNMP v3 Auth/Priv Enforced
            new ComplianceControl(
                "gaia_cis_2_6_1_snmp_v3_only",
                "Enforce SNMP v3 with Cryptographic Auth and Privacy (SHA/AES)",
                "SNMP v1 and v2c send community strings in cleartext over the network.",
                Severity.HIGH,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.6.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "2.2.2", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "CM-7", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("snmp_v3", "snmp v3 users", "gaia.snmp.v3_users", "CP-GATE-SNMP3-01"),
                        AssertionRule.present()
                    )
                )
            ),

            // 23. DATA_UNAVAILABLE: Unused Network Interfaces Disabled
            new ComplianceControl(
                "gaia_cis_2_7_1_unused_interfaces_disabled",
                "Ensure All Unused Network Interfaces Are Administratively Down",
                "Unconfigured active physical ports provide unmonitored physical ingress points into the network.",
                Severity.MEDIUM,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.7.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "1.2.2", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "CM-7", "Rev 5")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("interface_status", "interface admin state", "gaia.interface.down", "CP-GATE-IFACE-01"),
                        AssertionRule.present()
                    )
                )
            ),

            // 24. DATA_UNAVAILABLE: Management Subnet Access Restrictions
            new ComplianceControl(
                "gaia_cis_2_5_1_mgmt_trusted_subnets",
                "Restrict Administrative Access to Dedicated Bastion / Management Subnets",
                "Administrative portals should only accept connections from trusted internal jump servers.",
                Severity.CRITICAL,
                "device_os",
                "device",
                List.of(
                    FrameworkMapping.satisfies(ComplianceFramework.CIS, "2.5.1", "1.1.0"),
                    FrameworkMapping.satisfies(ComplianceFramework.PCI_DSS, "1.3.1", "4.0.1"),
                    FrameworkMapping.satisfies(ComplianceFramework.NIST_800_53, "AC-17", "Rev 5"),
                    FrameworkMapping.satisfies(ComplianceFramework.FINANCIAL_BASELINE, "Segment Isolation §1.2", "2026")
                ),
                List.of(
                    new VendorBinding(
                        "check_point",
                        "gaia",
                        EvidenceRequirement.of("allowed_clients", "allowed-client subnet", "gaia.mgmt.allowed_clients", "CP-GATE-CLIENTS-01"),
                        AssertionRule.present()
                    )
                )
            )
        );
    }
}
