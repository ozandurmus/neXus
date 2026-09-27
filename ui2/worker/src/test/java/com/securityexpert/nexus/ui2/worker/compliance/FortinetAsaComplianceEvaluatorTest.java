package com.securityexpert.nexus.ui2.worker.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.compliance.evaluator.FortinetAsaComplianceEvaluator;
import com.securityexpert.nexus.ui2.worker.compliance.model.Verdict;

class FortinetAsaComplianceEvaluatorTest {
    private static Verdict result(String vendor, String id, String text) {
        return FortinetAsaComplianceEvaluator.evaluate("synthetic-device", vendor, text).items().stream()
                .filter(i -> i.controlId().equals(id)).findFirst().orElseThrow().verdict();
    }

    private static String fg(String section, String settings) {
        return "config " + section + "\n" + settings + "\nend\n";
    }

    private static String asa(String lines) { return lines + "\nend\n"; }

    @Test
    void fortinetControlsUseExplicitScopedEvidence() {
        Map<String, String[]> cases = Map.of(
                "fg_admin_no_cleartext", new String[] {
                        fg("system interface", "edit \"port1\"\nset allowaccess ssh https\nnext"),
                        fg("system interface", "edit \"port1\"\nset allowaccess ssh http\nnext"),
                        fg("system interface", "edit \"port1\"\nnext")},
                "fg_remote_logging", new String[] {
                        fg("log syslogd setting", "set status enable\nset server 192.0.2.10"),
                        fg("log syslogd setting", "set status disable"),
                        fg("log syslogd setting", "set status enable")},
                "fg_ntp_configured", new String[] {
                        fg("system ntp", "set ntpsync enable\nset type fortiguard"),
                        fg("system ntp", "set ntpsync disable"),
                        fg("system ntp", "set ntpsync enable")},
                "fg_ssh_v1_disabled", new String[] {
                        fg("system global", "set admin-ssh-v1 disable"),
                        fg("system global", "set admin-ssh-v1 enable"), fg("system global", "set admintimeout 10")},
                "fg_admin_tls_minimum", new String[] {
                        fg("system global", "set admin-https-ssl-versions tlsv1-2 tlsv1-3"),
                        fg("system global", "set admin-https-ssl-versions tlsv1-1 tlsv1-2"),
                        fg("system global", "set admintimeout 10")},
                "fg_global_telnet_disabled", new String[] {
                        fg("system global", "set admin-telnet disable"),
                        fg("system global", "set admin-telnet enable"), fg("system global", "set admintimeout 10")},
                "fg_ha_heartbeat_defined", new String[] {
                        fg("system ha", "set mode a-p\nset hbdev \"ha1\" 50"),
                        fg("system ha", "set mode a-p\nset hbdev \"\""),
                        fg("system ha", "set mode a-p")});
        cases.forEach((id, configs) -> {
            assertEquals(Verdict.PASS, result("fortinet", id, configs[0]), id + " pass");
            assertEquals(Verdict.FAIL, result("fortinet", id, configs[1]), id + " fail");
            assertEquals(Verdict.UNKNOWN, result("fortinet", id, configs[2]), id + " unavailable");
        });
        assertEquals(Verdict.NOT_APPLICABLE, result("fortinet", "fg_ha_heartbeat_defined", fg("system ha", "set mode standalone")));
        assertEquals(Verdict.UNKNOWN, result("fortinet", "fg_ssh_v1_disabled", "config system global\nset admin-ssh-v1 disable\n"));
        assertEquals(Verdict.UNKNOWN, result("fortinet", "fg_ssh_v1_disabled", fg("system global", "set admin-ssh-v1 [withheld]")));
        assertEquals(Verdict.PASS, result("fortinet", "fg_ssh_v1_disabled",
                fg("system global", "set admin-ssh-v1 disable\nset unrelated-secret [withheld]")));
    }

    @Test
    void fortinetAbsentAllowaccessIsEmptyOnlyInAFullConfigurationSection() {
        String interfaces = "config system interface\nedit \"port1\"\nset vdom \"root\"\nnext\n"
                + "edit \"port2\"\nset allowaccess ping https ssh\nnext\nend\n";
        assertEquals(Verdict.PASS, result("fortinet", "fg_admin_no_cleartext",
                "#nexus-full-configuration system interface\n" + interfaces));
        assertEquals(Verdict.UNKNOWN, result("fortinet", "fg_admin_no_cleartext", interfaces));
        assertEquals(Verdict.FAIL, result("fortinet", "fg_admin_no_cleartext",
                "#nexus-full-configuration system interface\n" + interfaces.replace("ping https ssh", "ping http")));
    }

    @Test
    void fortinetMultiLineQuotedValueIsNotAStatement() {
        String text = "config system replacemsg admin \"pre_admin-disclaimer-text\"\n"
                + "set buffer \"Line one of the banner\nSecond line: %%LINK%%\n\"\nend\n"
                + fg("system global", "set admin-telnet disable");
        assertEquals(Verdict.PASS, result("fortinet", "fg_global_telnet_disabled", text));
        assertEquals(Verdict.UNKNOWN, result("fortinet", "fg_global_telnet_disabled",
                "config system global\nset admin-telnet disable\nset buffer \"never closed\nend\n"));
    }

    @Test
    void fortinetSettingAfterANestedConfigBelongsToTheEnclosingBlock() {
        String text = "config firewall invented\nedit \"one\"\nconfig members 1\nset status enable\nend\n"
                + "set trailing-setting 1 2\nnext\nend\n" + fg("system global", "set admin-telnet disable");
        assertEquals(Verdict.PASS, result("fortinet", "fg_global_telnet_disabled", text));
        String nestedInConfig = "config system invented\nconfig inner 1\nset a b\nend\nset after-inner 1\nend\n"
                + fg("system global", "set admin-telnet enable");
        assertEquals(Verdict.FAIL, result("fortinet", "fg_global_telnet_disabled", nestedInConfig));
    }

    @Test
    void asaControlsUseOnlyCompleteVisibleConfiguration() {
        Map<String, String[]> cases = Map.of(
                "asa_http_sources_restricted", new String[] {
                        asa("http server enable\nhttp 192.0.2.0 255.255.255.0 inside"),
                        asa("http server enable\nhttp 0.0.0.0 0.0.0.0 inside"), asa("http 192.0.2.0 255.255.255.0 inside")},
                "asa_telnet_absent", new String[] { asa("logging enable"), asa("telnet 192.0.2.0 255.255.255.0 inside"), "telnet [withheld]\nend\n" },
                "asa_ssh_aaa", new String[] {
                        asa("aaa authentication ssh console LOCAL\nusername invented privilege 15"),
                        asa("no aaa authentication ssh console"), asa("aaa authentication ssh console LOCAL")},
                "asa_http_aaa", new String[] {
                        asa("http server enable\naaa authentication http console LOCAL\nusername invented privilege 15"),
                        asa("http server enable\nno aaa authentication http console"), asa("http server enable")},
                "asa_logging_enabled", new String[] { asa("logging enable"), asa("no logging enable"), asa("logging timestamp")},
                "asa_remote_syslog", new String[] {
                        asa("logging enable\nlogging host inside 198.51.100.10"), asa("logging enable"), asa("logging host inside 198.51.100.10")},
                "asa_log_timestamps", new String[] { asa("logging timestamp"), asa("no logging timestamp"), asa("logging enable")},
                "asa_ntp_server", new String[] { asa("ntp server 203.0.113.10"), asa("logging enable"), "ntp server 203.0.113.10\n" });
        cases.forEach((id, configs) -> {
            assertEquals(Verdict.PASS, result("cisco_asa", id, configs[0]), id + " pass");
            assertEquals(Verdict.FAIL, result("cisco_asa", id, configs[1]), id + " fail");
            assertEquals(Verdict.UNKNOWN, result("cisco_asa", id, configs[2]), id + " unavailable");
        });
        assertEquals(Verdict.NOT_APPLICABLE, result("cisco_asa", "asa_http_aaa", asa("no http server enable")));
        assertEquals(Verdict.UNKNOWN, result("cisco_asa", "asa_http_sources_restricted", asa("http server enable\nhttp unparsed mask inside")));
        assertEquals(Verdict.PASS, result("cisco_asa", "asa_telnet_absent", asa("telnet timeout 5")));
        assertEquals(Verdict.PASS, result("cisco_asa", "asa_ssh_aaa", asa("username [withheld]\n"
                + "aaa-server INVENTED protocol tacacs+\naaa-server INVENTED (inside) host 192.0.2.5\n"
                + "aaa authentication ssh console INVENTED LOCAL")));
        assertEquals(Verdict.PASS, result("cisco_asa", "asa_http_sources_restricted",
                asa("http server enable\nhttp 192.0.2.0 255.255.255.0 inside\nhttp redirect outside 80")));
        assertEquals(Verdict.NOT_APPLICABLE, result("cisco_asa", "asa_failover_link", asa("no failover")));
        assertEquals(Verdict.FAIL, result("cisco_asa", "asa_failover_link", asa("failover\nno failover lan interface")));
        assertEquals(Verdict.UNKNOWN, result("cisco_asa", "asa_failover_link", asa("failover\nfailover lan interface HA GigabitEthernet0/1")));
    }
}
