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
    /** V149 (M1/M2) and V157 (M5): every phase A measurement read is a runnable diagnostic on Gaia gateways only. */
    @Test void syslogMeasurementReadsAreRunnableOnGaiaGatewaysOnly() {
        var rows=GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates=key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        var expected=java.util.Map.ofEntries(
            java.util.Map.entry("cp_syslog_measure_stat","bash -lc 'stat -c \"%i %s %Y\" /var/log/messages /var/log/messages.1 $FWDIR/log/fwd.elg $CPDIR/log/cpd.elg $CPDIR/log/cpwd.elg'"),
            java.util.Map.entry("cp_syslog_measure_tail_messages","bash -lc 'tail -n 20 /var/log/messages'"),
            java.util.Map.entry("cp_syslog_measure_tail_fwd","bash -lc 'tail -n 20 $FWDIR/log/fwd.elg'"),
            java.util.Map.entry("cp_syslog_measure_tail_cpd","bash -lc 'tail -n 20 $CPDIR/log/cpd.elg'"),
            java.util.Map.entry("cp_syslog_measure_tail_cpwd","bash -lc 'tail -n 20 $CPDIR/log/cpwd.elg'"),
            java.util.Map.entry("cp_syslog_measure_stat_ext","bash -lc 'stat -c \"%n %s %Y\" $FWDIR/log/fwk.elg $FWDIR/log/mq_mng.elg /var/log/hcp.elg /var/log/spike_detective/spike_detective.log /var/log/spike_detective/spike_detective.csv'"),
            java.util.Map.entry("cp_syslog_measure_ls_sa","bash -lc 'ls -lgo --time-style=+%s /var/log/sa/'"),
            java.util.Map.entry("cp_syslog_measure_ls_spike","bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/'"),
            java.util.Map.entry("cp_syslog_measure_ls_hcp","bash -lc 'ls -lgo --time-style=+%s /var/log/hcp/last/'"),
            java.util.Map.entry("cp_syslog_measure_ls_crash","bash -lc 'ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/'"),
            java.util.Map.entry("cp_syslog_measure_tail_fwk","bash -lc 'tail -n 20 $FWDIR/log/fwk.elg'"),
            java.util.Map.entry("cp_syslog_measure_tail_spike","bash -lc 'tail -n 20 /var/log/spike_detective/spike_detective.log'"),
            java.util.Map.entry("cp_syslog_measure_tail_spike_csv","bash -lc 'tail -n 5 /var/log/spike_detective/spike_detective.csv'"),
            java.util.Map.entry("cp_syslog_measure_head_sar","bash -lc 'head -n 20 /var/log/sa/sar$(date -d yesterday +%d)'"),
            java.util.Map.entry("cp_syslog_measure_hcp_listing","bash -lc 'f=$(ls -t /var/log/hcp/last/*.tar.gz | head -1); tar -tzvf \"$f\" | head -n 50'"));
        assertEquals(expected.keySet(),rows.stream().map(GateRow::gateId).filter(id -> id.startsWith("cp_syslog_measure_"))
            .collect(java.util.stream.Collectors.toSet()));
        for (var row: rows) {
            if (!row.gateId().startsWith("cp_syslog_measure_")) continue;
            assertEquals(expected.get(row.gateId()),row.canonicalCommandKey(),row.gateId());
            assertEquals("cp_gaia_gateway",row.platformRoleScope(),row.gateId());
            assertEquals(SignOffState.SIGNED_OFF,row.signOffState(),row.gateId());
            assertEquals(30,row.timeoutS(),row.gateId());
            assertEquals("none",row.retryRule(),row.gateId());
        }
        var gateway=DiagnosticRead.commands("check_point","gateway","known gaia",gates);
        var unknownModel=DiagnosticRead.commands("check_point","gateway",null,gates);
        for (String gateId: expected.keySet()) {
            assertTrue(gateway.stream().anyMatch(c -> c.gateId().equals(gateId) && c.commandTemplate().equals(expected.get(gateId))),gateId);
            assertTrue(unknownModel.stream().anyMatch(c -> c.gateId().equals(gateId)),gateId);
            assertTrue(DiagnosticRead.resolve("check_point","gateway","known gaia",gateId,null,gates).isPresent(),gateId);
            assertTrue(DiagnosticRead.resolveStored("check_point","gateway","known gaia",gateId,expected.get(gateId),gates).isPresent(),gateId);
            assertTrue(DiagnosticRead.commands("check_point","management_server",null,gates).stream()
                .noneMatch(c -> c.gateId().equals(gateId)),gateId);
            assertTrue(DiagnosticRead.commands("check_point","gateway","1550",gates).stream()
                .noneMatch(c -> c.gateId().equals(gateId)),gateId);
            for (String vendor: new String[]{"palo_alto","fortinet","cisco_asa","radware"})
                for (String role: new String[]{"gateway","management_server"})
                    assertTrue(DiagnosticRead.commands(vendor,role,null,gates).stream()
                        .noneMatch(c -> c.gateId().equals(gateId)),gateId);
        }
    }
    /** V160: the phase B collection reads (own rows, own terms) are never operator diagnostics, and no registry key is ambiguous. */
    @Test void syslogCollectionReadsAreNeverDiagnosticsAndKeepEveryRegistryKeyUnique() {
        var rows=GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates=key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        var collect=rows.stream().filter(row -> row.gateId().contains("_syslog_collect_")).toList();
        assertEquals(13,collect.size());
        for (var row: collect) assertEquals(1,gates.findByCanonicalKey(row.key()).size(),row.gateId());
        assertEquals(rows.size(),rows.stream().map(GateRow::key).distinct().count(),"two gate rows share one canonical key");
        for (String role: new String[]{"gateway","management_server"})
            for (String vendor: new String[]{"check_point","palo_alto"})
                for (String model: new String[]{null,"known gaia"})
                    for (var row: collect) {
                        assertTrue(DiagnosticRead.commands(vendor,role,model,gates).stream()
                            .noneMatch(c -> c.gateId().equals(row.gateId())),row.gateId());
                        assertTrue(DiagnosticRead.resolve(vendor,role,model,row.gateId(),null,gates).isEmpty(),row.gateId());
                    }
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
