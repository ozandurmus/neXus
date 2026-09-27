package com.securityexpert.nexus.ui2.worker.compliance.catalog;

import java.util.ArrayList;
import java.util.List;

import com.securityexpert.nexus.ui2.worker.compliance.model.AssertionRule;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceControl;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvidenceRequirement;
import com.securityexpert.nexus.ui2.worker.compliance.model.Severity;
import com.securityexpert.nexus.ui2.worker.compliance.model.VendorBinding;

/** The frozen configuration-only FortiGate and ASA controls. */
public final class FortinetAsaComplianceCatalog {
    /** Bump on any catalog OR evaluator change: it is part of the stored-evaluation cache key (ComplianceService). */
    public static final String CATALOG_VERSION = "2026.09.fgt-asa.5";
    private FortinetAsaComplianceCatalog() {}

    private record Spec(String id, String title, Severity severity, String closest) {}

    private static final List<Spec> FORTINET = List.of(
            // Frameworks copied from closest existing control: gaia_cis_2_5_5_web_https_only.
            new Spec("fg_admin_no_cleartext", "No HTTP or Telnet on managed interfaces", Severity.HIGH, "gaia_cis_2_5_5_web_https_only"),
            // Frameworks copied from closest existing control: gaia_cis_2_4_1_syslog_server.
            new Spec("fg_remote_logging", "Configure remote log destination", Severity.HIGH, "gaia_cis_2_4_1_syslog_server"),
            // Frameworks copied from closest existing control: gaia_cis_2_3_1_ntp_redundancy.
            new Spec("fg_ntp_configured", "Configure time synchronization", Severity.MEDIUM, "gaia_cis_2_3_1_ntp_redundancy"),
            // Frameworks copied from closest existing control: gaia_cis_2_5_3_ssh_protocol_v2.
            new Spec("fg_ssh_v1_disabled", "Disable SSH v1 compatibility", Severity.HIGH, "gaia_cis_2_5_3_ssh_protocol_v2"),
            // Frameworks copied from closest existing control: pan_cis_1_3_4_tls_version.
            new Spec("fg_admin_tls_minimum", "Limit admin TLS to 1.2+", Severity.HIGH, "pan_cis_1_3_4_tls_version"),
            // Frameworks copied from closest existing control: gaia_cis_2_5_6_telnet_disabled.
            new Spec("fg_global_telnet_disabled", "Disable global Telnet service", Severity.HIGH, "gaia_cis_2_5_6_telnet_disabled"),
            // Frameworks copied from closest existing control: pan_cis_2_3_1_ha_encryption.
            new Spec("fg_ha_heartbeat_defined", "Define heartbeat interfaces for configured HA", Severity.HIGH, "pan_cis_2_3_1_ha_encryption"));

    private static final List<Spec> ASA = List.of(
            // Frameworks copied from closest existing control: pan_cis_1_3_5_permitted_ip_addresses.
            new Spec("asa_http_sources_restricted", "Restrict ASDM HTTPS sources", Severity.HIGH, "pan_cis_1_3_5_permitted_ip_addresses"),
            // Frameworks copied from closest existing control: pan_cis_1_3_1_telnet_disabled.
            new Spec("asa_telnet_absent", "Avoid Telnet management", Severity.HIGH, "pan_cis_1_3_1_telnet_disabled"),
            // Frameworks copied from closest existing control: gaia_cis_2_1_8_non_default_admin.
            new Spec("asa_ssh_aaa", "Authenticate SSH administrators individually", Severity.HIGH, "gaia_cis_2_1_8_non_default_admin"),
            // Frameworks copied from closest existing control: gaia_cis_2_1_8_non_default_admin.
            new Spec("asa_http_aaa", "Authenticate ASDM administrators individually", Severity.HIGH, "gaia_cis_2_1_8_non_default_admin"),
            // Frameworks copied from closest existing control: pan_cis_2_2_2_system_log_forwarding.
            new Spec("asa_logging_enabled", "Enable system logging", Severity.HIGH, "pan_cis_2_2_2_system_log_forwarding"),
            // Frameworks copied from closest existing control: gaia_cis_2_4_1_syslog_server.
            new Spec("asa_remote_syslog", "Configure remote log destination", Severity.HIGH, "gaia_cis_2_4_1_syslog_server"),
            // Frameworks copied from closest existing control: gaia_cis_2_4_2_log_quota.
            new Spec("asa_log_timestamps", "Timestamp logs", Severity.MEDIUM, "gaia_cis_2_4_2_log_quota"),
            // Frameworks copied from closest existing control: gaia_cis_2_3_1_ntp_redundancy.
            new Spec("asa_ntp_server", "Configure NTP source", Severity.MEDIUM, "gaia_cis_2_3_1_ntp_redundancy"),
            // Frameworks copied from closest existing control: pan_cis_2_3_1_ha_encryption.
            new Spec("asa_failover_link", "Define failover communication link", Severity.HIGH, "pan_cis_2_3_1_ha_encryption"));

    public static List<ComplianceControl> getControls(String vendor) {
        List<ComplianceControl> existing = new ArrayList<>(CheckPointComplianceCatalog.getControls());
        existing.addAll(PaloAltoComplianceCatalog.getControls());
        List<ComplianceControl> result = new ArrayList<>();
        String platform = "fortinet".equals(vendor) ? "fortios" : "asa";
        for (Spec spec : "fortinet".equals(vendor) ? FORTINET : ASA) {
            ComplianceControl closest = existing.stream().filter(c -> c.id().equals(spec.closest())).findFirst().orElseThrow();
            result.add(new ComplianceControl(spec.id(), spec.title(), spec.title(), spec.severity(), "device_os", "device",
                    closest.frameworks(), List.of(new VendorBinding(vendor, platform,
                            EvidenceRequirement.of("sanitized_configuration", spec.id(), spec.id(), null), AssertionRule.present()))));
        }
        return List.copyOf(result);
    }
}
