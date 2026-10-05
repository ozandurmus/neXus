package com.securityexpert.nexus.ui2.jobs.diagnostic;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.capability.*;
class DiagnosticReadTest {
    @Test void v124PolicyGatesStayOutOfDiagnostics() {
        var rows=GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates=key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        var v124Ids=java.util.Set.of(
            "cp_policy_access_rulebase_hits", "cp_policy_packages_50", "cp_policy_times", "cp_policy_time_groups",
            "cp_policy_gateways_and_servers", "cp_policy_hosts", "cp_policy_networks", "cp_policy_groups",
            "cp_policy_groups_with_exclusion", "cp_policy_address_ranges", "cp_policy_services_tcp",
            "cp_policy_services_udp", "cp_policy_services_icmp", "cp_policy_services_icmp6", "cp_policy_services_other",
            "cp_policy_services_sctp", "cp_policy_services_dce_rpc", "cp_policy_services_rpc", "cp_policy_service_groups",
            "cp_policy_access_roles", "cp_policy_dynamic_objects", "cp_policy_dns_domains", "cp_policy_security_zones",
            "cp_policy_unused_objects");
        for (String gateId: v124Ids)
            assertTrue(rows.stream().anyMatch(row -> row.gateId().equals(gateId)),gateId);
        var cp=DiagnosticRead.commands("check_point","management_server",null,gates);
        assertTrue(cp.stream().anyMatch(c -> c.gateId().equals("mds_show_version_all")));
        assertTrue(cp.stream().noneMatch(c -> v124Ids.contains(c.gateId())));
        assertTrue(cp.stream().noneMatch(c -> c.gateId().startsWith("cp_policy_")));
        for (String role: new String[]{"gateway","management_server"})
            assertTrue(DiagnosticRead.commands("palo_alto",role,null,gates).stream()
                .noneMatch(c -> c.gateId().startsWith("pan_policy_")));
    }
    @Test void exactReviewedReadsOnlyForMatchingDeviceScope() {
        var rows=GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates=key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        assertTrue(DiagnosticRead.resolve("fortinet","management_server","diagnose fmnetwork interface detail port5",gates).isPresent());
        assertTrue(DiagnosticRead.resolve("fortinet","gateway","get system status",gates).isPresent());
        assertTrue(DiagnosticRead.resolve("cisco_asa","gateway","show version",gates).isPresent());
        for(String invalid: new String[]{"execute reboot","show","diagnose fmnetwork interface detail port5; reboot","diagnose fmnetwork interface detail port5\nget system status"})
            assertTrue(DiagnosticRead.resolve("fortinet","management_server",invalid,gates).isEmpty());
        assertTrue(DiagnosticRead.resolve("palo_alto","gateway","show version",gates).isEmpty());
        assertTrue(DiagnosticRead.resolve("fortinet","gateway","get system status",key -> java.util.List.of()).isEmpty());
    }
    @Test void fixtureScopesAndUnsafeReadClassRowsStayOutOfDiagnostics() {
        var rows=GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates=key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        var cp=DiagnosticRead.commands("check_point","gateway",null,gates);
        assertTrue(cp.stream().anyMatch(c -> c.gateId().equals("cp_inventory_vsid_cphaprob_stat")));
        assertTrue(cp.stream().anyMatch(c -> c.gateId().equals("cp_spark_backup_log")));
        assertTrue(cp.stream().noneMatch(c -> c.gateId().equals("cp_spark_show_diag")));
        assertTrue(DiagnosticRead.commands("check_point","gateway","1550",gates).stream()
            .noneMatch(c -> c.gateId().equals("cp_configuration_show_version_all")));
        assertTrue(DiagnosticRead.commands("check_point","gateway","known gaia",gates).stream()
            .noneMatch(c -> c.gateId().equals("cp_spark_backup_log")));
        assertTrue(DiagnosticRead.resolveStored("check_point","gateway",null,"cp_configuration_show_version_all",
            "clish -c 'show version all'",gates).isPresent());
        assertFalse(cp.stream().anyMatch(c -> c.gateId().equals("rb3b_add_backup_local")));
        assertTrue(DiagnosticRead.commands("check_point","management_server",null,gates).stream()
            .anyMatch(c -> c.gateId().equals("mds_show_version_all")));
        assertTrue(DiagnosticRead.commands("palo_alto","gateway",null,gates).stream()
            .anyMatch(c -> c.gateId().equals("pan_backup_ssh_show_config_running")));
        assertTrue(DiagnosticRead.commands("check_point","gateway","1550",gates).stream()
            .anyMatch(c -> c.gateId().equals("cp_spark_backup_log")));
        var spark=DiagnosticRead.commands("check_point","gateway","gaia_embedded",gates);
        assertTrue(spark.stream().anyMatch(c -> c.gateId().equals("cp_spark_show_diag")));
        assertTrue(spark.stream().anyMatch(c -> c.gateId().equals("cp_spark_show_software_version")));
        assertTrue(DiagnosticRead.commands("check_point","gateway","known gaia",gates).stream()
            .noneMatch(c -> c.gateId().equals("cp_spark_show_diag")));
        assertTrue(DiagnosticRead.resolve("check_point","gateway",null,"cp_inventory_vsid_cphaprob_stat","13",gates).isPresent());
        assertTrue(DiagnosticRead.resolve("check_point","gateway",null,"cp_inventory_vsid_cphaprob_stat","13;id",gates).isEmpty());
        assertTrue(DiagnosticRead.resolve("check_point","gateway",null,"unknown_gate",null,gates).isEmpty());
        for (String destructive: new String[]{"cp_spark_backup_push","rb3b_add_backup_local",
                "cp_backup_delete_backup_local","mds_backup_start","rdw_cc_backup_export",
                "rdw_cc_backup_delete","asa_delete_archive"})
            assertTrue(DiagnosticRead.DIAGNOSTIC_EXCLUDED.contains(destructive),destructive);
        for (var row: rows) {
            String command=row.canonicalCommandKey();
            if (row.actionClass()==com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_0_READ
                    && row.transportKind().equals("SSH_EXEC") &&
                    (command.contains(" > ") || command.startsWith("backup settings to ")
                    || command.startsWith("set cli ") || command.startsWith("terminal pager ")
                    || command.startsWith("scp -f ") || command.startsWith("execute ha manage <")
                    || command.startsWith("config global |")))
                assertTrue(DiagnosticRead.DIAGNOSTIC_EXCLUDED.contains(row.gateId()),row.gateId());
        }
    }
}
