package com.securityexpert.nexus.ui2.jobs.diagnostic;

import java.util.*;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.admission.CheckPointSparkModelHint;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/** Exact signed-off SSH reads, resolved again against the live gate at dispatch. */
public final class DiagnosticRead {
    public static final String CAPABILITY = "diagnostic_read";
    // Read-class gates that alter CLI state, export/copy data, write files, or require collector-owned paths.
    public static final Set<String> DIAGNOSTIC_EXCLUDED = Set.of(
        "cp_spark_backup_push", "cp_backup_archive_digest", "mds_mdsstat", "mds_gaia_configuration",
        "mds_cplic_print", "mds_netstat_rn", "mds_uname", "mds_backup_poll", "mds_list_workdir",
        "mds_sha256sum", "asa_terminal_pager_0", "asa_scp_fetch_archive",
        "pan_backup_ssh_scripting_mode_on", "pan_backup_ssh_pager_off", "pan_backup_ssh_config_output_format_set",
        "fgt_execute_ha_manage_login", "fgt_context_moves",
        "rb3b_add_backup_local", "rb3b_delete_backup_local", "cp_backup_add_backup_local",
        "cp_backup_delete_backup_local", "mds_backup_start", "rdw_cc_backup_create",
        "rdw_cc_backup_export", "rdw_cc_backup_delete", "asa_backup_archive", "asa_delete_archive",
        "pan_backup_export_device_state", "pan_panorama_export_device_state");
    private static final List<GateRow> ROWS = GateRegistryFixtureLoader.loadFromStream(
        DiagnosticRead.class.getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    public record Command(String gateId, String commandTemplate, int timeoutS) {}
    public record Read(String command, String gateId, int timeoutSeconds, String platformRoleScope) {}

    public static List<Command> commands(String vendor, String role, String model, GateRegistryPort gates) {
        return ROWS.stream().filter(row -> eligible(row, vendor, role, model))
            .map(row -> resolveGate(row, gates).map(known ->
                new Command(row.gateId(), row.canonicalCommandKey(), known.timeoutS())))
            .flatMap(Optional::stream).toList();
    }

    public static Optional<Read> resolve(String vendor, String role, String model, String gateId,
            String parameter, GateRegistryPort gates) {
        if (gateId == null || gateId.isBlank() || (parameter != null && !parameter.matches("[A-Za-z0-9_.-]{1,31}")))
            return Optional.empty();
        for (GateRow row : ROWS) {
            if (!row.gateId().equals(gateId) || !eligible(row, vendor, role, model)) continue;
            String template = row.canonicalCommandKey();
            int start = template.indexOf('<'), end = template.indexOf('>', start + 1);
            if ((start < 0) != (parameter == null) || (start >= 0 &&
                    (end < 0 || template.indexOf('<', end + 1) >= 0))) continue;
            String command = start < 0 ? template : template.substring(0, start) + parameter + template.substring(end + 1);
            if ("check_point".equals(vendor) && !"gaia_embedded".equals(row.platformRoleScope())
                    && "clish".equals(row.shellContext()) && !command.startsWith("clish -c "))
                command = "clish -c '" + command + "'";
            if (command.length() > 512) continue;
            var known = resolveGate(row, gates);
            if (known.isPresent()) return Optional.of(new Read(command, gateId, known.get().timeoutS(), row.platformRoleScope()));
        }
        return Optional.empty();
    }

    /** Compatibility for jobs submitted by the previous typed-command UI. */
    public static Optional<Read> resolve(String vendor, String role, String command, GateRegistryPort gates) {
        return resolve(vendor, role, null, command, gates);
    }

    public static Optional<Read> resolve(String vendor, String role, String model, String command, GateRegistryPort gates) {
        return resolveCommand(vendor, role, model, null, command, gates);
    }

    private static Optional<Read> resolveCommand(String vendor, String role, String model, String gateId,
            String command, GateRegistryPort gates) {
        if (command == null || command.isBlank() || command.length() > 512) return Optional.empty();
        for (Command option : commands(vendor, role, model, gates)) {
            if (gateId != null && !gateId.equals(option.gateId())) continue;
            String template = option.commandTemplate();
            int start = template.indexOf('<'), end = template.indexOf('>', start + 1);
            String parameter = null;
            if (start >= 0 && end > start && template.indexOf('<', end + 1) < 0
                    && command.startsWith(template.substring(0, start)) && command.endsWith(template.substring(end + 1)))
                parameter = command.substring(start, command.length() - (template.length() - end - 1));
            var read = resolve(vendor, role, model, option.gateId(), parameter, gates);
            if (read.isPresent() && (command.equals(read.get().command()) ||
                    (start < 0 && command.equals(template)) ||
                    (parameter != null && command.equals(template.substring(0,start) + parameter + template.substring(end+1)))))
                return read;
        }
        return Optional.empty();
    }

    public static Optional<Read> resolveStored(String vendor, String role, String model, String gateId,
            String command, GateRegistryPort gates) {
        if (gateId == null || command == null) return Optional.empty();
        return resolveCommand(vendor,role,model,gateId,command,gates).filter(read -> command.equals(read.command()));
    }

    private static boolean eligible(GateRow row, String vendor, String role, String model) {
        if (!row.vendor().equals(vendor) || !"SSH_EXEC".equals(row.transportKind())
                || row.actionClass() != ActionClass.CLASS_0_READ || row.signOffState() != SignOffState.SIGNED_OFF
                || row.gateId().startsWith("cp_policy_") || row.gateId().startsWith("pan_policy_")
                || DIAGNOSTIC_EXCLUDED.contains(row.gateId()) || row.canonicalCommandKey().contains("%s")) return false;
        String scope = switch (vendor) {
            case "check_point" -> "management_server".equals(role) ? "cp_multi_domain_server" :
                "gateway".equals(role) ? CheckPointSparkModelHint.isKnownSparkModel(Optional.ofNullable(model))
                    ? "gaia_embedded" : model == null || model.isBlank() ? "unknown_cp_gateway" : "cp_gaia_gateway" : "unsupported";
            case "palo_alto" -> "gateway".equals(role) ? "pan_firewall" : "panorama";
            case "fortinet" -> "management_server".equals(role) ? "fortimanager" :
                "gateway".equals(role) ? "fortigate" : "unsupported";
            case "cisco_asa" -> "gateway".equals(role) ? "cisco_asa_firewall" : "unsupported";
            case "radware" -> "management_server".equals(role) ? "radware_cyber_controller" : "unsupported";
            default -> "unsupported";
        };
        if ("unknown_cp_gateway".equals(scope) && ("cp_spark_show_diag".equals(row.gateId())
                || "cp_spark_show_software_version".equals(row.gateId()))) return false;
        return row.platformRoleScope().equals(scope) || "unknown_cp_gateway".equals(scope)
            && ("cp_gaia_gateway".equals(row.platformRoleScope()) || "gaia_embedded".equals(row.platformRoleScope()));
    }

    private static Optional<GateResolution.Known> resolveGate(GateRow row, GateRegistryPort gates) {
        try {
            var resolution = GateResolver.resolve(row.key(), Optional.of(ActionClass.CLASS_0_READ), gates);
            return resolution instanceof GateResolution.Known known && known.gateId().equals(row.gateId())
                && known.actionClass() == ActionClass.CLASS_0_READ ? Optional.of(known) : Optional.empty();
        } catch (RuntimeException invalidGate) { return Optional.empty(); }
    }
    private DiagnosticRead() {}
}
