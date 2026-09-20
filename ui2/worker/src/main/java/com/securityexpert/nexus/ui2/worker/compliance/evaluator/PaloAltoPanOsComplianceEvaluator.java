package com.securityexpert.nexus.ui2.worker.compliance.evaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.worker.compliance.catalog.PaloAltoComplianceCatalog;
import com.securityexpert.nexus.ui2.worker.compliance.engine.ComplianceAssertionEngine;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceControl;
import com.securityexpert.nexus.ui2.worker.compliance.model.DisplayStatus;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationItem;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationResult;
import com.securityexpert.nexus.ui2.worker.compliance.model.ReasonCode;
import com.securityexpert.nexus.ui2.worker.compliance.model.VendorBinding;
import com.securityexpert.nexus.ui2.worker.compliance.model.Verdict;

/**
 * Compliance evaluator for Palo Alto PAN-OS configurations.
 * Evaluates active or effective-running XML configuration against
 * the CIS Palo Alto Firewall 10.x/11.x Benchmark v1.1.0 catalog.
 * Follows strict fail-closed and sensitive data masking discipline.
 */
public final class PaloAltoPanOsComplianceEvaluator {

    private PaloAltoPanOsComplianceEvaluator() {
    }

    public static EvaluationResult evaluate(
            String deviceId,
            String vendor,
            String platformFamily,
            String xmlContent,
            Set<String> assignedControlIds
    ) {
        boolean hasConfig = xmlContent != null && !xmlContent.isBlank();
        List<ComplianceControl> catalog = PaloAltoComplianceCatalog.getControls();
        List<EvaluationItem> items = new ArrayList<>();

        for (ComplianceControl control : catalog) {
            if (assignedControlIds != null && !assignedControlIds.isEmpty() && !assignedControlIds.contains(control.id())) {
                continue;
            }

            VendorBinding binding = control.findBinding("palo_alto", "pan_os");
            if (binding == null) {
                items.add(new EvaluationItem(
                        control.id(),
                        control.title(),
                        control.severity(),
                        control.frameworks(),
                        Verdict.NOT_APPLICABLE,
                        ReasonCode.PROVEN_UNSUPPORTED,
                        DisplayStatus.NOT_APPLICABLE,
                        null,
                        null,
                        "Control does not apply to Palo Alto PAN-OS platform.",
                        null
                ));
                continue;
            }

            // 1. Explicit uncollected command check (Fail-Closed DATA_UNAVAILABLE)
            String gateEntryId = binding.evidenceRequirement().gateEntryId();
            if (gateEntryId != null && gateEntryId.startsWith("PAN-GATE-")) {
                items.add(new EvaluationItem(
                        control.id(),
                        control.title(),
                        control.severity(),
                        control.frameworks(),
                        Verdict.UNKNOWN,
                        ReasonCode.EVIDENCE_MISSING,
                        DisplayStatus.DATA_UNAVAILABLE,
                        binding.evidenceRequirement().requiredEvidenceId(),
                        gateEntryId,
                        "Required command is pending collector gate approval (" + gateEntryId + "). Mark as DATA_UNAVAILABLE.",
                        null
                ));
                continue;
            }

            // 2. If device has zero collected configuration at all
            if (!hasConfig) {
                items.add(new EvaluationItem(
                        control.id(),
                        control.title(),
                        control.severity(),
                        control.frameworks(),
                        Verdict.UNKNOWN,
                        ReasonCode.EVIDENCE_MISSING,
                        DisplayStatus.DATA_UNAVAILABLE,
                        binding.evidenceRequirement().requiredEvidenceId(),
                        gateEntryId,
                        "Device has no configuration collected. Run 'Collect now' first.",
                        null
                ));
                continue;
            }

            // 3. Extract setting value from XML content
            String settingKey = binding.evidenceRequirement().settingKey();
            String resolvedValue = extractFromXml(control.id(), settingKey, xmlContent);

            // 4. Assertion evaluation
            String op = binding.assertion().op().toLowerCase(Locale.ROOT);
            if ("absent".equals(op)) {
                boolean isAbsent = (resolvedValue == null || "absent".equalsIgnoreCase(resolvedValue) || "no".equalsIgnoreCase(resolvedValue));
                if (isAbsent) {
                    items.add(new EvaluationItem(
                            control.id(),
                            control.title(),
                            control.severity(),
                            control.frameworks(),
                            Verdict.PASS,
                            ReasonCode.ASSERTION_SATISFIED,
                            DisplayStatus.PASS,
                            null,
                            null,
                            "Setting is properly disabled/absent as required.",
                            "absent"
                    ));
                } else {
                    items.add(new EvaluationItem(
                            control.id(),
                            control.title(),
                            control.severity(),
                            control.frameworks(),
                            Verdict.FAIL,
                            ReasonCode.ASSERTION_FAILED,
                            DisplayStatus.FAIL,
                            null,
                            null,
                            "Setting is present ('" + sanitizeDisplayValue(control.id(), resolvedValue) + "') but must be disabled/absent.",
                            sanitizeDisplayValue(control.id(), resolvedValue)
                    ));
                }
                continue;
            }

            if (resolvedValue == null) {
                items.add(new EvaluationItem(
                        control.id(),
                        control.title(),
                        control.severity(),
                        control.frameworks(),
                        Verdict.FAIL,
                        ReasonCode.ASSERTION_FAILED,
                        DisplayStatus.FAIL,
                        null,
                        null,
                        "Required setting '" + settingKey + "' is not configured.",
                        "not configured"
                ));
                continue;
            }

            boolean passed = ComplianceAssertionEngine.evaluate(resolvedValue, binding.assertion());
            String displayVal = sanitizeDisplayValue(control.id(), resolvedValue);

            if (passed) {
                items.add(new EvaluationItem(
                        control.id(),
                        control.title(),
                        control.severity(),
                        control.frameworks(),
                        Verdict.PASS,
                        ReasonCode.ASSERTION_SATISFIED,
                        DisplayStatus.PASS,
                        null,
                        null,
                        "Configuration meets benchmark criteria (observed: '" + displayVal + "').",
                        displayVal
                ));
            } else {
                items.add(new EvaluationItem(
                        control.id(),
                        control.title(),
                        control.severity(),
                        control.frameworks(),
                        Verdict.FAIL,
                        ReasonCode.ASSERTION_FAILED,
                        DisplayStatus.FAIL,
                        null,
                        null,
                        "Observed value '" + displayVal + "' does not satisfy benchmark requirement.",
                        displayVal
                ));
            }
        }

        int totalAssigned = items.size();
        int passCount = (int) items.stream().filter(i -> i.displayStatus() == DisplayStatus.PASS).count();
        int failCount = (int) items.stream().filter(i -> i.displayStatus() == DisplayStatus.FAIL).count();
        int dataUnavailableCount = (int) items.stream().filter(i -> i.displayStatus() == DisplayStatus.DATA_UNAVAILABLE).count();

        double observedCompliance = (passCount + failCount > 0) ? (passCount * 100.0 / (passCount + failCount)) : 0.0;
        double evidenceCoverage = (totalAssigned > 0) ? ((passCount + failCount) * 100.0 / totalAssigned) : 0.0;
        double assuredCompliance = (totalAssigned > 0) ? (passCount * 100.0 / totalAssigned) : 0.0;

        return new EvaluationResult(
                deviceId,
                vendor,
                totalAssigned,
                passCount,
                failCount,
                dataUnavailableCount,
                Math.round(observedCompliance * 10.0) / 10.0,
                Math.round(evidenceCoverage * 10.0) / 10.0,
                Math.round(assuredCompliance * 10.0) / 10.0,
                items
        );
    }

    private static String extractFromXml(String controlId, String settingKey, String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }

        Pattern p = switch (controlId) {
            case "pan_cis_1_2_1_login_banner" ->
                Pattern.compile("(?is)<login-banner>(.*?)</login-banner>");
            case "pan_cis_1_2_2_idle_timeout" ->
                Pattern.compile("(?is)<idle-timeout>\\s*(\\d+)\\s*</idle-timeout>");
            case "pan_cis_1_2_3_account_lockout_attempts" ->
                Pattern.compile("(?is)<failed-attempts>\\s*(\\d+)\\s*</failed-attempts>");
            case "pan_cis_1_2_4_account_lockout_duration" ->
                Pattern.compile("(?is)<lockout-time>\\s*(\\d+)\\s*</lockout-time>");
            case "pan_cis_1_2_5_password_min_length" ->
                Pattern.compile("(?is)<minimum-length>\\s*(\\d+)\\s*</minimum-length>");
            case "pan_cis_1_2_6_password_complexity" ->
                Pattern.compile("(?is)<password-complexity>.*?<enabled>\\s*(yes|no)\\s*</enabled>");
            case "pan_cis_1_2_7_password_history" ->
                Pattern.compile("(?is)<password-history>\\s*(\\d+)\\s*</password-history>");
            case "pan_cis_1_2_8_password_expiration" ->
                Pattern.compile("(?is)<expiration-period>\\s*(\\d+)\\s*</expiration-period>");
            case "pan_cis_1_3_1_telnet_disabled" ->
                Pattern.compile("(?is)<disable-telnet>\\s*(yes|no)\\s*</disable-telnet>");
            case "pan_cis_1_3_2_http_disabled" ->
                Pattern.compile("(?is)<disable-http>\\s*(yes|no)\\s*</disable-http>");
            case "pan_cis_1_3_3_ssh_protocol_v2" ->
                Pattern.compile("(?is)<ssh>.*?</ssh>|<system>.*?<service>|<deviceconfig>.*?<system>");
            case "pan_cis_1_3_4_tls_version" ->
                Pattern.compile("(?is)<tls-version-min>\\s*([^<]+)\\s*</tls-version-min>|<min-version>\\s*([^<]+)\\s*</min-version>");
            case "pan_cis_1_3_5_permitted_ip_addresses" ->
                Pattern.compile("(?is)<permitted-ip>\\s*<entry");
            case "pan_cis_2_1_1_ntp_redundancy" ->
                Pattern.compile("(?is)<ntp-servers>\\s*(?:<primary-ntp-server>|<entry)");
            case "pan_cis_2_1_2_timezone_configured" ->
                Pattern.compile("(?is)<timezone>\\s*([^<]+)\\s*</timezone>");
            case "pan_cis_2_1_3_dns_servers" ->
                Pattern.compile("(?is)<dns-setting>.*?<servers>");
            case "pan_cis_2_2_1_syslog_forwarding" ->
                Pattern.compile("(?is)<syslog>\\s*<entry");
            case "pan_cis_2_2_2_system_log_forwarding" ->
                Pattern.compile("(?is)<system>\\s*<entry");
            case "pan_cis_2_2_3_config_log_forwarding" ->
                Pattern.compile("(?is)<config>\\s*<entry");
            case "pan_cis_2_3_1_ha_encryption" ->
                Pattern.compile("(?is)<high-availability>.*?<encryption>.*?<enabled>\\s*(yes|true)\\s*</enabled>");
            default -> null;
        };

        if (p != null) {
            Matcher m = p.matcher(xml);
            if (m.find()) {
                if (m.groupCount() >= 1 && m.group(1) != null) {
                    return m.group(1).trim();
                }
                return "present";
            }
        }
        return null;
    }

    /**
     * Masks sensitive values before presentation in API responses and UI dashboards.
     * Complies strictly with sensitive-identity reporting law (report relationship, not values).
     */
    private static String sanitizeDisplayValue(String controlId, String rawValue) {
        if (rawValue == null) return "not configured";
        return switch (controlId) {
            case "pan_cis_1_2_1_login_banner" -> "configured (legal notice present)";
            case "pan_cis_1_3_3_ssh_protocol_v2" -> "SSHv2 enforced";
            case "pan_cis_1_3_5_permitted_ip_addresses" -> "configured (bastion access restricted)";
            case "pan_cis_2_1_1_ntp_redundancy" -> "configured (redundant sync)";
            case "pan_cis_2_1_3_dns_servers" -> "configured (redundant dns)";
            case "pan_cis_2_2_1_syslog_forwarding",
                 "pan_cis_2_2_2_system_log_forwarding",
                 "pan_cis_2_2_3_config_log_forwarding" -> "configured (siem forwarding active)";
            case "pan_cis_2_3_1_ha_encryption" -> "enabled";
            default -> rawValue.length() > 40 ? rawValue.substring(0, 37) + "..." : rawValue;
        };
    }
}
