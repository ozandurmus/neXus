package com.securityexpert.nexus.ui2.worker.compliance.catalog;

import java.util.List;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceGuidance;

/** Static operator guidance. Contains no device data or executable actions. */
final class ComplianceGuidanceCatalog {
    private ComplianceGuidanceCatalog() {}

    static ComplianceGuidance forControl(String id) {
        return switch (id) {
            case "gaia_cis_2_1_1_password_min_length" -> manual(
                    "Password length. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open User Management > Password Policy.",
                    "Set the minimum password length to at least 12 characters.", "Check Point R81.20 Gaia Administration Guide: Password Policy",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_1_2_password_complexity" -> manual(
                    "Password complexity. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open User Management > Password Policy.",
                    "Enable complexity requirements for mixed character classes.", "Check Point R81.20 Gaia Administration Guide: Password Policy",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_1_3_password_history" -> manual(
                    "Password reuse. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open User Management > Password Policy.",
                    "Enable password history and retain at least five previous passwords.", "Check Point R81.20 Gaia Administration Guide: Password Policy",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_1_4_account_lockout_attempts" -> manual(
                    "Failed login protection. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open User Management > Password Policy.",
                    "Enable denial after failed logins and choose a nonzero threshold of at most five attempts.", "Check Point R81.20 Gaia Administration Guide: Password Policy",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_1_5_account_lockout_duration" -> manual(
                    "Account lockout. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open User Management > Password Policy.",
                    "Set the failed-login denial period to at least 30 minutes; retain an approved recovery route.", "Check Point R81.20 Gaia Administration Guide: Password Policy",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_5_2_session_timeout" -> manual(
                    "Session timeout. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open System Management > Session.",
                    "Set the Command Line Shell inactivity timeout to a nonzero value of at most 10 minutes. Review the Portal timeout separately.", "Check Point R81.20 Gaia Administration Guide: Session",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_5_3_ssh_protocol_v2" -> manual(
                    "SSH protocol. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open the release-specific Gaia SSH administration documentation.",
                    "Confirm SSH version 2 support and remove version 1 compatibility using the documented procedure for the installed release.", "Check Point R81.20 Gaia Administration Guide: SSH",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Review actual behavior separately; field presence alone does not prove the control title.");
            case "gaia_cis_2_5_6_telnet_disabled" -> manual(
                    "Telnet management. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open the release-specific Gaia management access documentation.",
                    "Confirm a working SSH access route, then remove Telnet administrative access using the supported procedure.", "Check Point R81.20 Gaia Administration Guide: Management Access",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_5_5_web_https_only" -> manual(
                    "HTTPS management. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open the Gaia Portal settings for the installed release.",
                    "Configure HTTPS management and review any cleartext administrative access separately.", "Check Point R81.20 Gaia Administration Guide: Gaia Portal",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Review actual behavior separately; field presence alone does not prove the control title.");
            case "gaia_cis_2_1_8_non_default_admin" -> manual(
                    "Individual administrators. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open User Management > Users.",
                    "Create individually assigned administrators with approved roles; verify access before reviewing shared or default accounts.", "Check Point R81.20 Gaia Administration Guide: Users",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. The current evidence tests Expert password presence, not individual administrator identity. Resolve this mismatch with the control owner.");
            case "gaia_cis_2_3_1_ntp_redundancy" -> manual(
                    "Time synchronization. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open System Management > Time.",
                    "Configure two approved NTP sources and verify synchronization through the normal operating procedure.", "Check Point R81.20 Gaia Administration Guide: Time",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Review actual behavior separately; field presence alone does not prove the control title.");
            case "gaia_cis_2_4_1_syslog_server" -> manual(
                    "Remote logging. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open System Management > System Logging.",
                    "Add the approved remote syslog destination and severity selection, then verify receipt at the log service.", "Check Point R81.20 Gaia Administration Guide: System Logging",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_4_2_log_quota" -> manual(
                    "Log retention review. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open System Management > System Logging.",
                    "Review the approved log retention and disk capacity policy separately from date-format configuration.", "Check Point R81.20 Gaia Administration Guide: System Logging",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. The current evidence tests date-format presence, not log rotation or quota. Resolve this mismatch before remediation.");
            case "gaia_cis_2_4_3_core_dump_restriction" -> manual(
                    "Core dump limits. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open System Management > Crash Data.",
                    "Review Total space limit and Dumps per process against the platform requirements and approved diagnostic retention policy.", "Check Point R81.20 Gaia Administration Guide: Crash Data",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_2_1_login_banner" -> manual(
                    "Login notice. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open the Gaia Messages settings for the installed release.",
                    "Enable the login banner and enter the approved legal notice.", "Check Point R81.20 Gaia Administration Guide: Messages",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_7_2_arp_cache_protection" -> manual(
                    "ARP capacity. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open Network Management > ARP > Configuration.",
                    "Set Maximum Entries to at least 1024 and size it for the actual static and dynamic entry requirements.", "Check Point R81.20 Gaia Administration Guide: ARP",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Cache capacity alone does not prove protection against ARP poisoning.");
            case "gaia_cis_2_7_3_ip_conflict_monitor" -> manual(
                    "IP conflict monitoring review. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open the Gaia IP conflict monitoring documentation for the installed release.",
                    "Resolve the mismatch between this control title and its expected value with the control owner before changing monitoring.", "Check Point R81.20 Gaia Administration Guide: IP Conflict Monitoring",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. The current assertion expects off although the title says enabled. Do not disable monitoring merely to obtain PASS.");
            case "gaia_cis_2_7_4_clienv_debug_disabled" -> manual(
                    "CLI debug settings. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open the Gaia command-line environment documentation for the installed release.",
                    "Review whether debugging is still needed and return the shell to its documented non-debug state through the approved change process.", "Check Point R81.20 Gaia Administration Guide: Command Line Interface",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_1_9_grub2_password" -> manual(
                    "Boot password. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open System Management > System Passwords.",
                    "Set an approved GRUB password and retain it in the authorized recovery process.", "Check Point R81.20 Gaia Administration Guide: System Passwords",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_8_1_installer_policy" -> manual(
                    "Deployment Agent updates. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open the Gaia software update settings for the installed release.",
                    "Review and configure the Deployment Agent update-check policy against the approved maintenance schedule.", "Check Point R81.20 Gaia Administration Guide: Software Updates",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_5_4_ssh_strong_ciphers" -> manual(
                    "SSH cipher review. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open the Gaia SSH security documentation for the installed release.",
                    "Identify supported strong algorithms and remove CBC, 3DES and RC4-family algorithms only through the documented hardening procedure.", "Check Point R81.20 Gaia Administration Guide: SSH",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_6_1_snmp_v3_only" -> manual(
                    "SNMP protection. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open the Gaia SNMP settings for the installed release.",
                    "Configure SNMPv3 authentication and privacy with the monitoring owner, then retire legacy community-based access.", "Check Point R81.20 Gaia Administration Guide: SNMP",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_7_1_unused_interfaces_disabled" -> manual(
                    "Unused interfaces. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open Network Management > Network Interfaces.",
                    "Confirm which ports are unused and disable only those ports after checking management, routing and HA dependencies.", "Check Point R81.20 Gaia Administration Guide: Network Interfaces",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "gaia_cis_2_5_1_mgmt_trusted_subnets" -> manual(
                    "Trusted management sources. Review the observed evidence before planning this Check Point Gaia change.",
                    "Open the Gaia Host Access settings for the installed release.",
                    "Add approved management sources, verify an allowed access route, then remove overly broad access entries.", "Check Point R81.20 Gaia Administration Guide: Host Access",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "pan_cis_1_2_1_login_banner" -> manual(
                    "Login notice. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Management.",
                    "Set the approved login banner in General Settings.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Management",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_1_2_2_idle_timeout" -> manual(
                    "Session timeout. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Management > Authentication Settings.",
                    "Set Idle Timeout to a nonzero value of at most 15 minutes.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Management",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_1_2_3_account_lockout_attempts" -> manual(
                    "Failed login protection. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Management > Authentication Settings.",
                    "Set Failed Attempts to a nonzero threshold of at most five.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Management",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_1_2_4_account_lockout_duration" -> manual(
                    "Account lockout. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Management > Authentication Settings.",
                    "Set Lockout Time to at least 30 minutes and retain an approved recovery route.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Management",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_1_2_5_password_min_length" -> manual(
                    "Password length. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Management > Minimum Password Complexity.",
                    "Enable the policy and set Minimum Length to at least 12; review account-specific password profile overrides.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Management",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_1_2_6_password_complexity" -> manual(
                    "Password complexity. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Management > Minimum Password Complexity.",
                    "Enable the policy and require uppercase, lowercase, numeric and special characters; review password profile overrides.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Management",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_1_2_7_password_history" -> manual(
                    "Password reuse. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Management > Minimum Password Complexity.",
                    "Set Prevent Password Reuse Limit to at least five; review password profile overrides.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Management",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_1_2_8_password_expiration" -> manual(
                    "Password expiration. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Management > Minimum Password Complexity.",
                    "Set Required Password Change Period to a nonzero value of at most 90 days; review password profile overrides.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Management",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_1_3_1_telnet_disabled" -> manual(
                    "Telnet management. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Interfaces.",
                    "Edit the management interface, confirm SSH access, then deselect Telnet.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Interfaces",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_1_3_2_http_disabled" -> manual(
                    "HTTP management. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Interfaces.",
                    "Edit the management interface, confirm HTTPS access, then deselect HTTP.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Interfaces",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_1_3_3_ssh_protocol_v2" -> manual(
                    "SSH protocol review. Review the observed evidence before planning this PAN-OS change.",
                    "Open the PAN-OS SSH management documentation for the installed release.",
                    "Verify that the installed release supports only approved SSH versions; use its documented hardening or upgrade procedure if required.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Management",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group. Review actual behavior separately; field presence alone does not prove the control title.");
            case "pan_cis_1_3_4_tls_version" -> manual(
                    "Management TLS. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Management.",
                    "Assign an SSL/TLS Service Profile with a minimum TLS version of 1.2 or greater; review certificate and client compatibility.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Management; Device > Certificate Management > SSL/TLS Service Profile",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_1_3_5_permitted_ip_addresses" -> manual(
                    "Trusted management sources. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Interfaces.",
                    "Edit the management interface and restrict Permitted IP Addresses to approved administration sources.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Interfaces",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_2_1_1_ntp_redundancy" -> manual(
                    "Time synchronization. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Services.",
                    "Configure approved primary and secondary NTP servers and review their service routes.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Services",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group. Review actual behavior separately; field presence alone does not prove the control title.");
            case "pan_cis_2_1_2_timezone_configured" -> manual(
                    "Timezone. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Management > General Settings.",
                    "Select the approved Time Zone for audit correlation.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Management",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_2_1_3_dns_servers" -> manual(
                    "DNS resilience. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Setup > Services.",
                    "Configure approved primary and secondary DNS servers and review their service routes.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Services",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_2_2_1_syslog_forwarding" -> manual(
                    "Remote logging. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Server Profiles > Syslog.",
                    "Create the approved syslog server profile, then select it for the required log types and verify receipt.", "PAN-OS Web Interface Help / Administrator Guide: Device > Server Profiles > Syslog",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_2_2_2_system_log_forwarding" -> manual(
                    "System log forwarding. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Log Settings.",
                    "Edit System log forwarding and select the approved syslog profile for the required severities.", "PAN-OS Web Interface Help / Administrator Guide: Device > Log Settings",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_2_2_3_config_log_forwarding" -> manual(
                    "Configuration log forwarding. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > Log Settings.",
                    "Edit Config log forwarding and select the approved syslog profile; verify receipt of configuration audit events.", "PAN-OS Web Interface Help / Administrator Guide: Device > Log Settings",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_2_3_1_ha_encryption" -> manual(
                    "HA1 encryption. Review the observed evidence before planning this PAN-OS change.",
                    "Open Device > High Availability > HA Communications.",
                    "Confirm model support, exchange HA keys through the documented secure procedure and enable encryption on the HA1 control link on both peers.", "PAN-OS Web Interface Help / Administrator Guide: Configure Active/Passive HA",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group. Coordinate both peers; HA changes may interrupt traffic. Review actual behavior separately; field presence alone does not prove the control title.");
            case "pan_cis_4_1_1_snmpv3_only" -> manual(
                    "SNMP protection. Review the observed evidence before planning this PAN-OS change.",
                    "Open the PAN-OS SNMP Setup for the installed release.",
                    "Configure SNMPv3 authentication and privacy with the monitoring owner, then retire legacy community-based polling.", "PAN-OS Web Interface Help / Administrator Guide: SNMP Setup",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_4_1_2_unused_interfaces_down" -> manual(
                    "Unused interfaces. Review the observed evidence before planning this PAN-OS change.",
                    "Open the PAN-OS Network Interfaces settings for the installed release.",
                    "Confirm which ports are unused and set only those ports administratively down after checking management and HA dependencies.", "PAN-OS Web Interface Help / Administrator Guide: Network Interfaces",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_4_1_3_ddns_disabled" -> manual(
                    "Dynamic DNS. Review the observed evidence before planning this PAN-OS change.",
                    "Open the interface Dynamic DNS settings in the PAN-OS WebUI.",
                    "Review consumers of dynamic DNS and disable unapproved DDNS registrations on the affected interfaces.", "PAN-OS Web Interface Help / Administrator Guide: Configure Dynamic DNS for Firewall Interfaces",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "pan_cis_4_1_4_ssh_strong_ciphers" -> manual(
                    "Strong management cryptography. Review the observed evidence before planning this PAN-OS change.",
                    "Open the PAN-OS SSH Management Profiles and SSL/TLS Service Profiles.",
                    "Review the algorithms supported by the release and configure approved strong algorithms; validate administrative client compatibility.", "PAN-OS Web Interface Help / Administrator Guide: Device > Setup > Management",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Requires an approved commit and, when centrally managed, review of the owning Panorama template or device group.");
            case "fg_admin_no_cleartext" -> manual(
                    "Administrative services. Review the observed evidence before planning this FortiOS change.",
                    "Open the FortiGate interface administrative access settings.",
                    "Remove HTTP and Telnet from managed interfaces after confirming HTTPS and SSH access.", "FortiOS Administration Guide: Administrative access",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "fg_remote_logging" -> manual(
                    "Remote logging. Review the observed evidence before planning this FortiOS change.",
                    "Open the FortiGate log settings.",
                    "Configure the approved remote logging destination and verify receipt.", "FortiOS Administration Guide: Logging",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "fg_ntp_configured" -> manual(
                    "Time synchronization. Review the observed evidence before planning this FortiOS change.",
                    "Open System > Settings.",
                    "Enable time synchronization with approved NTP sources.", "FortiOS Administration Guide: Settings",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "fg_ssh_v1_disabled" -> manual(
                    "SSH compatibility. Review the observed evidence before planning this FortiOS change.",
                    "Open the FortiOS administration settings for the installed release.",
                    "Disable SSH version 1 compatibility using the release-specific documented setting.", "FortiOS CLI Reference: config system global",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "fg_admin_tls_minimum" -> manual(
                    "Management TLS. Review the observed evidence before planning this FortiOS change.",
                    "Open the FortiOS administration settings for the installed release.",
                    "Restrict administrative TLS protocols to TLS 1.2 or greater and verify client compatibility.", "FortiOS CLI Reference: config system global",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "fg_global_telnet_disabled" -> manual(
                    "Global Telnet. Review the observed evidence before planning this FortiOS change.",
                    "Open the FortiOS administration settings for the installed release.",
                    "Disable the global Telnet service after confirming the approved secure access route.", "FortiOS CLI Reference: config system global",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "fg_ha_heartbeat_defined" -> manual(
                    "HA heartbeat. Review the observed evidence before planning this FortiOS change.",
                    "Open the FortiGate HA settings.",
                    "For an intended HA deployment, define the approved heartbeat interfaces consistently with the peer and physical connections.", "FortiOS Administration Guide: HA heartbeat interface",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Coordinate both peers; HA changes may interrupt traffic.");
            case "asa_http_sources_restricted" -> manual(
                    "ASDM access. Review the observed evidence before planning this Cisco ASA change.",
                    "Open ASDM management access settings.",
                    "Replace unrestricted HTTPS source entries with the approved administration networks and verify retained access.", "ASDM Book 1: Management Access",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "asa_telnet_absent" -> manual(
                    "Telnet management. Review the observed evidence before planning this Cisco ASA change.",
                    "Open ASDM management access settings.",
                    "Remove Telnet management entries after confirming SSH or ASDM access.", "ASDM Book 1: Management Access",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "asa_ssh_aaa" -> manual(
                    "SSH authentication. Review the observed evidence before planning this Cisco ASA change.",
                    "Open ASDM Users/AAA settings.",
                    "Configure SSH authentication with the approved AAA method and individually assigned accounts; test recovery access.", "ASDM Book 1: Management Access",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "asa_http_aaa" -> manual(
                    "ASDM authentication. Review the observed evidence before planning this Cisco ASA change.",
                    "Open ASDM Users/AAA settings.",
                    "Configure HTTP/ASDM authentication with the approved AAA method and individually assigned accounts; test recovery access.", "ASDM Book 1: Management Access",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "asa_logging_enabled" -> manual(
                    "System logging. Review the observed evidence before planning this Cisco ASA change.",
                    "Open ASDM logging settings.",
                    "Enable logging and confirm that the approved destinations receive system events.", "ASDM Book 1: Logging",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "asa_remote_syslog" -> manual(
                    "Remote logging. Review the observed evidence before planning this Cisco ASA change.",
                    "Open ASDM logging settings.",
                    "Add the approved syslog host using the intended interface and transport; verify receipt.", "ASDM Book 1: Logging",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "asa_log_timestamps" -> manual(
                    "Log timestamps. Review the observed evidence before planning this Cisco ASA change.",
                    "Open ASDM logging settings.",
                    "Enable timestamps and verify that the time source and timezone are correct.", "ASDM Book 1: Logging",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "asa_ntp_server" -> manual(
                    "Time synchronization. Review the observed evidence before planning this Cisco ASA change.",
                    "Open Configuration > Device Setup > System Time > NTP.",
                    "Configure an approved NTP server and verify synchronization in the intended context.", "ASDM Book 1: Basic Settings",
                    "Verify the installed release and your change process before applying; preserve a recovery access route.");
            case "asa_failover_link" -> manual(
                    "Failover communication. Review the observed evidence before planning this Cisco ASA change.",
                    "Open ASDM failover settings.",
                    "For an intended failover pair, define the communication link consistently with the peer and physical connections.", "ASDM Book 1: Failover",
                    "Verify the installed release and your change process before applying; preserve a recovery access route. Coordinate both peers; HA changes may interrupt traffic.");
            default -> null;
        };
    }

    private static ComplianceGuidance manual(String summary, String location, String change, String reference, String caution) {
        return new ComplianceGuidance(summary, List.of(location, change,
                "Review and apply through the approved change process, then refresh configuration evidence and re-check the finding."),
                null, List.of(reference), caution);
    }
}
