package com.securityexpert.nexus.ui2.worker.compliance.evaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.securityexpert.nexus.ui2.worker.compliance.catalog.CheckPointComplianceCatalog;
import com.securityexpert.nexus.ui2.worker.compliance.engine.ComplianceAssertionEngine;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceControl;
import com.securityexpert.nexus.ui2.worker.compliance.model.DisplayStatus;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationItem;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationResult;
import com.securityexpert.nexus.ui2.worker.compliance.model.ReasonCode;
import com.securityexpert.nexus.ui2.worker.compliance.model.VendorBinding;
import com.securityexpert.nexus.ui2.worker.compliance.model.Verdict;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigSection;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigSetting;

public final class CheckPointGaiaComplianceEvaluator {

    private CheckPointGaiaComplianceEvaluator() {
    }

    public static EvaluationResult evaluate(
            String deviceId,
            String vendor,
            String platformFamily,
            List<ConfigSection> sections,
            Set<String> assignedControlIds
    ) {
        return evaluate(deviceId, vendor, platformFamily, sections, null, assignedControlIds);
    }

    public static EvaluationResult evaluate(
            String deviceId,
            String vendor,
            String platformFamily,
            List<ConfigSection> sections,
            String sanitizedText,
            Set<String> assignedControlIds
    ) {
        Map<String, ConfigSection> sectionMap = sections == null ? Map.of() :
                sections.stream().collect(Collectors.toMap(ConfigSection::id, s -> s, (a, b) -> a));

        boolean hasConfig = (sections != null && !sections.isEmpty()) || (sanitizedText != null && !sanitizedText.isBlank());

        List<ComplianceControl> catalog = CheckPointComplianceCatalog.getControls();
        List<EvaluationItem> items = new ArrayList<>();

        for (ComplianceControl control : catalog) {
            if (assignedControlIds != null && !assignedControlIds.isEmpty() && !assignedControlIds.contains(control.id())) {
                continue;
            }

            VendorBinding binding = control.findBinding("check_point", "gaia");
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
                        "Control does not apply to Check Point Gaia platform.",
                        null
                ));
                continue;
            }

            // 1. Explicit uncollected command check (DATA_UNAVAILABLE)
            String gateEntryId = binding.evidenceRequirement().gateEntryId();
            if (gateEntryId != null && gateEntryId.startsWith("CP-GATE-")) {
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

            // 3. Evaluate collected command
            String sectionId = binding.evidenceRequirement().sectionId();
            String settingKey = binding.evidenceRequirement().settingKey();
            ConfigSection section = sectionMap.get(sectionId);

            String resolvedValue = null;
            List<String> listValues = new ArrayList<>();

            // A. Search in structured sections
            if (section != null && !section.settings().isEmpty()) {
                for (ConfigSetting s : section.settings()) {
                    String normKey = s.setting().toLowerCase(Locale.ROOT).replace("·", " ").replaceAll("\\s+", " ").trim();
                    String targetNorm = settingKey.toLowerCase(Locale.ROOT).replace("-", " ").replaceAll("\\s+", " ").trim();
                    if (normKey.contains(targetNorm) || s.setting().equalsIgnoreCase(settingKey)) {
                        listValues.add(s.value());
                        if (resolvedValue == null) {
                            resolvedValue = s.value();
                        }
                    }
                }
            }

            // B. If not resolved from sections, scan sanitizedText directly
            if (resolvedValue == null && sanitizedText != null) {
                String textValue = extractFromSanitizedText(control.id(), settingKey, sanitizedText);
                if (textValue != null) {
                    resolvedValue = textValue;
                    listValues.add(textValue);
                }
            }

            // C. Assertion evaluation
            String op = binding.assertion().op().toLowerCase(Locale.ROOT);
            if ("absent".equals(op)) {
                boolean isAbsent = (resolvedValue == null || "absent".equalsIgnoreCase(resolvedValue) || "off".equalsIgnoreCase(resolvedValue));
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
                            "Setting is present ('" + resolvedValue + "') but must be disabled/absent.",
                            resolvedValue
                    ));
                }
                continue;
            }

            if (resolvedValue == null) {
                // Setting was not found in config
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

            boolean passed;
            if (op.startsWith("count_")) {
                passed = ComplianceAssertionEngine.evaluateList(listValues, binding.assertion());
            } else {
                passed = ComplianceAssertionEngine.evaluate(resolvedValue, binding.assertion());
            }

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
                        "Configuration meets benchmark criteria (observed: '" + resolvedValue + "').",
                        resolvedValue
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
                        "Observed value '" + resolvedValue + "' does not satisfy benchmark requirement.",
                        resolvedValue
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

    private static String extractFromSanitizedText(String controlId, String settingKey, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Pattern p = switch (controlId) {
            case "gaia_cis_2_1_1_password_min_length" -> Pattern.compile("(?im)^set\\s+password-controls\\s+min-password-length\\s+(\\d+)");
            case "gaia_cis_2_1_2_password_complexity" -> Pattern.compile("(?im)^set\\s+password-controls\\s+complexity\\s+(\\S+)");
            case "gaia_cis_2_1_3_password_history" -> Pattern.compile("(?im)^set\\s+password-controls\\s+(?:history-check|password-history)\\s+(\\S+)");
            case "gaia_cis_2_1_4_account_lockout_attempts" -> Pattern.compile("(?im)^set\\s+password-controls\\s+(?:deny-on-fail|lockout-threshold)\\s+(\\d+)");
            case "gaia_cis_2_1_5_account_lockout_duration" -> Pattern.compile("(?im)^set\\s+password-controls\\s+(?:deny-on-fail-duration|lockout-duration)\\s+(\\d+)");
            case "gaia_cis_2_5_2_session_timeout" -> Pattern.compile("(?im)^set\\s+(?:clienv\\s+inactivity-timeout|inactivity-timeout)\\s+(\\d+)");
            case "gaia_cis_2_5_3_ssh_protocol_v2" -> Pattern.compile("(?im)^set\\s+(?:sshd?|ssh)\\s+(?:version|protocol)\\s+(\\d+)");
            case "gaia_cis_2_5_6_telnet_disabled" -> Pattern.compile("(?im)^set\\s+telnet\\s+server\\s+(\\S+)");
            case "gaia_cis_2_5_5_web_https_only" -> Pattern.compile("(?im)^set\\s+web\\s+ssl-port\\s+(\\d+)");
            case "gaia_cis_2_1_8_non_default_admin" -> Pattern.compile("(?im)^set\\s+expert-password-hash\\s+(\\S+)");
            case "gaia_cis_2_3_1_ntp_redundancy" -> Pattern.compile("(?im)^set\\s+ntp\\s+server\\s+(\\S+)");
            case "gaia_cis_2_4_1_syslog_server" -> Pattern.compile("(?im)^set\\s+syslog\\s+(\\S+)");
            case "gaia_cis_2_4_2_log_quota" -> Pattern.compile("(?im)^set\\s+format\\s+date-format\\s+(\\S+)");
            case "gaia_cis_2_4_3_core_dump_restriction" -> Pattern.compile("(?im)^set\\s+core-dump\\s+(\\S+)");
            case "gaia_cis_2_2_1_login_banner" -> Pattern.compile("(?im)^set\\s+message\\s+banner\\s+(on|off)");
            case "gaia_cis_2_7_2_arp_cache_protection" -> Pattern.compile("(?im)^set\\s+arp\\s+table\\s+cache-size\\s+(\\d+)");
            case "gaia_cis_2_7_3_ip_conflict_monitor" -> Pattern.compile("(?im)^set\\s+ip-conflicts-monitor\\s+state\\s+(\\S+)");
            case "gaia_cis_2_7_4_clienv_debug_disabled" -> Pattern.compile("(?im)^set\\s+clienv\\s+debug\\s+(\\S+)");
            case "gaia_cis_2_1_9_grub2_password" -> Pattern.compile("(?im)^set\\s+grub2-password-hash\\s+(\\S+)");
            case "gaia_cis_2_8_1_installer_policy" -> Pattern.compile("(?im)^set\\s+installer\\s+policy\\s+(\\S+)");
            default -> null;
        };

        if (p != null) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }
}
