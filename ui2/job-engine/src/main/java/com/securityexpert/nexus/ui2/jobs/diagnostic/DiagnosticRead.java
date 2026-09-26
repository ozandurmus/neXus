package com.securityexpert.nexus.ui2.jobs.diagnostic;

import java.util.*;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/** Exact read-only diagnostic proposals, resolved independently at admission and dispatch. */
public final class DiagnosticRead {
    public static final String CAPABILITY = "diagnostic_read";
    private static final Set<String> APPROVED = Set.of(
        "fmg_ssh_get_system_interface", "fmg_ssh_diagnose_hardware_info_nic",
        "fmg_ssh_diagnose_system_print_interface", "fmg_ssh_fmnetwork_interface_detail",
        "fgt_get_system_status", "asa_show_version", "asa_show_mode",
        "asa_show_ip_address", "asa_show_interface_ip_brief", "asa_show_route");
    private static final List<GateRow> ROWS = GateRegistryFixtureLoader.loadFromStream(
        DiagnosticRead.class.getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    public record Read(String command, String gateId, int timeoutSeconds) {}

    public static Optional<Read> resolve(String vendor, String role, String command, GateRegistryPort gates) {
        if (command == null || command.length() > 512 || command.isBlank()) return Optional.empty();
        String scope = "fortinet".equals(vendor) ? ("management_server".equals(role) ? "fortimanager" :
            "gateway".equals(role) ? "fortigate" : "unsupported") :
            "cisco_asa".equals(vendor) && "gateway".equals(role) ? "cisco_asa_firewall" : "unsupported";
        for (GateRow row : ROWS) {
            if (!APPROVED.contains(row.gateId()) || !row.vendor().equals(vendor) || !row.platformRoleScope().equals(scope)) continue;
            String template = row.canonicalCommandKey();
            String expected = template;
            if (template.contains("<")) {
                int start = template.indexOf('<'), end = template.indexOf('>');
                String prefix = template.substring(0, start), suffix = template.substring(end + 1);
                if (!command.startsWith(prefix) || !command.endsWith(suffix)) continue;
                String parameter = command.substring(prefix.length(), command.length() - suffix.length());
                if (!parameter.matches("[A-Za-z0-9_.-]{1,31}")) continue;
                expected = prefix + parameter + suffix;
            }
            if (!command.equals(expected)) continue;
            var resolution = GateResolver.resolve(row.key(), Optional.of(ActionClass.CLASS_0_READ), gates);
            if (resolution instanceof GateResolution.Known known && known.gateId().equals(row.gateId())
                    && known.actionClass() == ActionClass.CLASS_0_READ)
                return Optional.of(new Read(expected, known.gateId(), known.timeoutS()));
        }
        return Optional.empty();
    }
    private DiagnosticRead() {}
}
