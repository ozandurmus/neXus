package com.securityexpert.nexus.ui2.worker.configuration.fortinet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerExecutor;

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
        assertEquals(2, p.withheldLineCount());
        assertEquals(6, p.settingCount());
        assertFalse(p.sanitizedText().contains("AAAAbbbb"));
        assertFalse(p.sanitizedText().contains("ENC ccc"));
        assertTrue(p.sanitizedText().contains("set password [withheld]"));
        assertFalse(p.sanitizedText().contains("#config-version"));
        var counts = p.index().stream().collect(java.util.stream.Collectors.toMap(e -> e.context() + "|" + e.section(), e -> e.entryCount()));
        assertEquals(2, counts.get("global|system global"));
        assertEquals(2, counts.get("global|system admin"));
        assertEquals(1, counts.get("root|system interface"));
        assertEquals(1, counts.get("root|vpn ipsec phase1-interface"));
        assertEquals(p.canonicalHash(), FortiGateConfigProcessor.process(CONFIG.replace("446474735424624", "999")).canonicalHash());
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
}
