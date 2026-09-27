package com.securityexpert.nexus.ui2.worker.configuration.fortinet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerExecutor;
import com.securityexpert.nexus.ui2.worker.compliance.evaluator.FortinetAsaComplianceEvaluator;
import com.securityexpert.nexus.ui2.worker.compliance.model.Verdict;
import com.securityexpert.nexus.ui2.configuration.FortiGateConfigurationAllowlist;

class FortiGateConfigProcessorTest {

    static final String CONFIG = """
            #config-version=FG10E1-7.0.12-FW-build0523-230606:opmode=0:vdom=1:user=x
            #conf_file_ver=446474735424624
            config vdom
            edit root
            next
            edit VD-ALPHA
            next
            end
            config global
            config system global
                set hostname "FGT-TANGO-04"
                set timezone 04
            end
            config system admin
                edit "fwadm"
                    set password ENC AAAAbbbb
                    set accprofile "super_admin"
                next
            end
            end
            config vdom
            edit root
            config system interface
                edit "port1"
                    set ip 192.0.2.1 255.255.255.0
                next
            end
            config vpn ipsec phase1-interface
                edit "to-hq"
                    set psksecret ENC ccc
                next
            end
            next
            end
            """;

    @Test
    void sectionsPerVdomSettingsCountedSecretsWithheld() {
        var p = FortiGateConfigProcessor.process(CONFIG);
        assertEquals(1, p.withheldLineCount());
        assertEquals(5, p.settingCount());
        assertFalse(p.sanitizedText().contains("AAAAbbbb"));
        assertFalse(p.sanitizedText().contains("ENC ccc"));
        assertTrue(p.sanitizedText().contains("set password [withheld]"));
        assertFalse(p.sanitizedText().contains("#config-version"));
        var counts = p.index().stream().collect(java.util.stream.Collectors.toMap(e -> e.context() + "|" + e.section(), e -> e.entryCount()));
        assertEquals(2, counts.get("global|system global"));
        assertEquals(2, counts.get("global|system admin"));
        assertEquals(1, counts.get("root|system interface"));
        assertFalse(counts.containsKey("root|vpn ipsec phase1-interface"));
        assertEquals(p.canonicalHash(), FortiGateConfigProcessor.process(CONFIG.replace("446474735424624", "999")).canonicalHash());
    }

    @Test
    void curatedSectionsKeepOnlyApprovedFamiliesAndBalancedWrappers() {
        String[] kept = {"system global", "system settings", "system console", "system central-management", "system fortiguard",
                "system dns", "system ntp", "system admin", "system accprofile", "system password-policy",
                "system snmp sysinfo", "system snmp community", "system snmp user", "user tacacs+", "user radius", "user ldap",
                "system ha", "log setting", "log syslogd setting", "log syslogd2 setting", "log syslogd3 setting",
                "log syslogd4 setting", "log fortianalyzer setting", "log fortianalyzer2 setting", "log fortianalyzer3 setting",
                "system interface", "system zone", "router static", "router static6", "router bgp", "router ospf"};
        String[] dropped = {"firewall policy", "firewall address", "firewall service custom", "firewall internet-service",
                "dlp sensor", "webfilter profile", "wanopt settings", "vpn ipsec phase1-interface", "user local"};
        StringBuilder raw = new StringBuilder();
        for (String scope : new String[] {"", "config global\n", "config vdom\nedit root\n"}) {
            raw.append(scope);
            for (String name : kept) raw.append("#nexus-full-configuration ").append(name).append("\nconfig ").append(name)
                    .append("\nset status enable\nconfig child\nedit invented\nset mode enable\nnext\nend\nend\n");
            for (String name : dropped) raw.append("config ").append(name)
                    .append("\nedit invented\nset note \"first line\nend\nconfig system dns\nlast line\"\nnext\nend\n");
            if (!scope.isEmpty()) raw.append(scope.startsWith("config vdom") ? "next\nend\n" : "end\n");
        }
        var p = FortiGateConfigProcessor.process(raw.toString());
        assertEquals(kept.length * 3 * 2, p.settingCount());
        assertEquals(kept.length * 2, p.index().size());
        for (String name : kept) assertTrue(p.sanitizedText().contains("config " + name + "\n"));
        for (String name : dropped) assertFalse(p.sanitizedText().contains("config " + name + "\n"));
        assertFalse(p.sanitizedText().contains("last line"));
        assertTrue(p.sanitizedText().contains("config global\n"));
        assertTrue(p.sanitizedText().contains("config vdom\nedit root\n"));
    }

    @Test
    void fortiManagerInterfaceStates() {
        Map<String, String> st = FortiManagerExecutor.parseInterfaceStates("""
                == [ port1 ]
                name: port1   status: up   ip: 192.0.2.10 255.255.255.0
                == [ port2 ]
                name: port2
                status: down
                == [ port3 ]
                name: port3    status: enable    ip: 192.0.2.3 255.255.255.0
                == [ port4 ]
                name: port4    status: disable
                """);
        assertEquals("up", st.get("port1"));
        assertEquals("down", st.get("port2"));
        assertEquals("up", st.get("port3"));
        assertEquals("down", st.get("port4"));
    }

    @Test
    void fullSectionsReplaceTopLevelAndExposeDefaultControlValues() {
        String show = "#config-version=synthetic\nconfig system global\nset timezone 04\nend\n"
                + "config system interface\nedit \"port1\"\nnext\nend\n";
        String global = "config system global\nset admin-telnet disable\nset admin-ssh-v1 disable\n"
                + "set admin-https-ssl-versions tlsv1-2 tlsv1-3\nset secret ENC synthetic-value\nend\n";
        String interfaces = "config system interface\nedit \"port1\"\nset allowaccess ssh https\nnext\nend\n";
        String ha = "config system ha\nset mode a-p\nset hbdev \"ha1\" 50\nend\n";
        String merged = FortiGateConfigProcessor.replaceFullSection(show, "system global", global);
        merged = FortiGateConfigProcessor.replaceFullSection(merged, "system interface", interfaces);
        merged = FortiGateConfigProcessor.replaceFullSection(merged, "system ha", ha);
        assertEquals(1, merged.split("config system global", -1).length - 1);
        assertEquals(1, merged.split("config system interface", -1).length - 1);
        assertEquals(1, merged.split("config system ha", -1).length - 1);
        var processed = FortiGateConfigProcessor.process(merged);
        assertTrue(processed.sanitizedText().contains("set secret [withheld]"));
        assertFalse(processed.sanitizedText().contains("synthetic-value"));
        assertEquals(Verdict.PASS, verdict(processed.sanitizedText(), "fg_global_telnet_disabled"));
        assertEquals(Verdict.PASS, verdict(processed.sanitizedText(), "fg_ssh_v1_disabled"));
        assertEquals(Verdict.PASS, verdict(processed.sanitizedText(), "fg_admin_tls_minimum"));
        assertEquals(Verdict.PASS, verdict(processed.sanitizedText(), "fg_admin_no_cleartext"));
        assertEquals(Verdict.PASS, verdict(processed.sanitizedText(), "fg_ha_heartbeat_defined"));
        assertEquals(Verdict.NOT_APPLICABLE, verdict(FortiGateConfigProcessor.process(
                FortiGateConfigProcessor.replaceFullSection(merged, "system ha",
                        "config system ha\nset mode standalone\nend\n")).sanitizedText(), "fg_ha_heartbeat_defined"));
        assertEquals(show, FortiGateConfigProcessor.replaceFullSection(show, "system global", null));
        assertEquals(show, FortiGateConfigProcessor.replaceFullSection(show, "system global",
                "Command fail. Return code -61\n"));
    }

    @Test
    void fullSectionInGlobalScopeAndAmbiguousReplacement() {
        String show = "#config-version=synthetic\nconfig global\nconfig system global\nset timezone 04\nend\nend\n";
        String full = "config system global\nset admin-telnet enable\nset admin-ssh-v1 enable\n"
                + "set admin-https-ssl-versions tlsv1-1 tlsv1-2\nend\n";
        String merged = FortiGateConfigProcessor.replaceFullSection(show, "system global", full);
        merged = FortiGateConfigProcessor.replaceFullSection(merged, "system interface",
                "config system interface\nedit \"port1\"\nset allowaccess http ssh\nnext\nend\n");
        merged = FortiGateConfigProcessor.replaceFullSection(merged, "system ha",
                "config system ha\nset mode a-p\nset hbdev \"\"\nend\n");
        var text = FortiGateConfigProcessor.process(merged).sanitizedText();
        assertEquals(Verdict.FAIL, verdict(text, "fg_global_telnet_disabled"));
        assertEquals(Verdict.FAIL, verdict(text, "fg_ssh_v1_disabled"));
        assertEquals(Verdict.FAIL, verdict(text, "fg_admin_tls_minimum"));
        assertEquals(Verdict.FAIL, verdict(text, "fg_admin_no_cleartext"));
        assertEquals(Verdict.FAIL, verdict(text, "fg_ha_heartbeat_defined"));
        assertEquals(show + show, FortiGateConfigProcessor.replaceFullSection(show + show, "system global", full));
        assertEquals(CONFIG, FortiGateConfigProcessor.replaceFullSection(CONFIG, "system interface",
                "config system interface\nedit \"port1\"\nset allowaccess ssh\nnext\nend\n"));
    }

    @Test
    void singleEndClosesFinalVdomAndAllowsGlobalFullSectionMerge() {
        String show = """
                #config-version=synthetic
                config vdom
                edit root
                next
                end
                config global
                config system global
                set admin-telnet enable
                end
                config system interface
                edit port1
                set allowaccess http
                next
                end
                config system ha
                set mode standalone
                end
                end
                config vdom
                edit root
                config system settings
                set opmode nat
                end
                config router bgp
                set as 65001
                end
                end
                """;
        String merged = FortiGateConfigProcessor.replaceFullSection(show, "system global",
                "config system global\nset admin-telnet disable\nset admin-ssh-v1 disable\n"
                        + "set admin-https-ssl-versions tlsv1-2 tlsv1-3\nend\n");
        merged = FortiGateConfigProcessor.replaceFullSection(merged, "system interface",
                "config system interface\nedit port1\nset allowaccess ssh https\nnext\nend\n");
        merged = FortiGateConfigProcessor.replaceFullSection(merged, "system ha",
                "config system ha\nset mode a-p\nset hbdev \"ha1\" 50\nend\n");
        assertEquals(1, merged.split("config system global", -1).length - 1);
        assertEquals(1, merged.split("config system interface", -1).length - 1);
        assertEquals(1, merged.split("config system ha", -1).length - 1);
        assertTrue(merged.contains("config global\n#nexus-full-configuration system global\n"));
        assertTrue(merged.endsWith("config router bgp\nset as 65001\nend\nend\n"));

        String filtered = FortiGateConfigurationAllowlist.filter(merged).text();
        assertTrue(filtered.endsWith("config router bgp\nset as 65001\nend\nend\n"));
        var processed = FortiGateConfigProcessor.process(merged);
        assertTrue(processed.index().stream().anyMatch(e -> e.context().equals("root") && e.section().equals("router bgp")));
        for (String id : new String[] {"fg_global_telnet_disabled", "fg_ssh_v1_disabled", "fg_admin_tls_minimum",
                "fg_admin_no_cleartext", "fg_ha_heartbeat_defined"}) {
            assertEquals(Verdict.PASS, verdict(processed.sanitizedText(), id), id);
        }

        String invalid = merged.replace("set as 65001\nend\nend\n", "edit invented\nset as 65001\nend\nend\nend\n");
        assertEquals(Verdict.UNKNOWN, verdict(invalid, "fg_global_telnet_disabled"));
        assertEquals(invalid, FortiGateConfigProcessor.replaceFullSection(invalid, "system global",
                "config system global\nset admin-telnet disable\nend\n"));
    }

    private static Verdict verdict(String text, String id) {
        return FortinetAsaComplianceEvaluator.evaluate("synthetic-device", "fortinet", text).items().stream()
                .filter(i -> i.controlId().equals(id)).findFirst().orElseThrow().verdict();
    }
}
