package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Synthetic text only. */
class PanLogRedactorTest {
    private static Map<String, String> fields(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void aSecretBearingPathHidesTheWholeChangeBodyAndCountsEachField() {
        for (String path : new String[] {"/config/mgt-config/users/entry[@name='a']/phash", "/config/shared/certificate/entry[@name='c']/private-key",
                "/config/devices/entry/deviceconfig/system/snmp-setting/snmp-community-string", "/config/x/radius/server/entry/secret",
                "/config/x/y/key"}) {
            var result = PanLogRedactor.redact("pan_config", fields("path", path, "before-change-preview", "<a>b</a>",
                    "after-change-preview", "<a>c</a>", "before-change-detail", "d", "cmd", "set"));
            assertEquals("<redacted>", result.fields().get("before-change-preview"), path);
            assertEquals("<redacted>", result.fields().get("after-change-preview"), path);
            assertEquals("<redacted>", result.fields().get("before-change-detail"), path);
            assertEquals("set", result.fields().get("cmd"));
            assertEquals(3, result.redactions(), path);
        }
    }

    @Test
    void anOrdinaryPathKeepsItsChangeBodyButSecretElementsAndValuesInsideAreMasked() {
        var result = PanLogRedactor.redact("pan_config", fields("full-path", "vsys vsys1 rulebase security rules r1",
                "before-change-preview", "<entry name=\"r1\"><to><member>z</member></to></entry>",
                "after-change-preview", "<entry name=\"r1\"><password>P1</password><auth-key>K1</auth-key><key>K2</key>"
                        + "<pre-shared-key>K3</pre-shared-key><description>fine</description></entry>"));
        assertEquals("<entry name=\"r1\"><to><member>z</member></to></entry>", result.fields().get("before-change-preview"));
        String after = result.fields().get("after-change-preview");
        for (String secret : new String[] {"P1", "K1", "K2", "K3"}) assertFalse(after.contains(secret), secret);
        assertTrue(after.contains("<description>fine</description>"));
        assertEquals(4, result.redactions());
    }

    @Test
    void setStyleTextMasksTheValueAfterASecretKeyword() {
        var result = PanLogRedactor.redact("pan_config", fields("full-path",
                "set mgt-config users admin phash $1$SYNTH$abc; set shared snmp-community-string SYNTHSTR"));
        String text = result.fields().get("full-path");
        assertFalse(text.contains("SYNTH"));
        assertTrue(text.contains("phash <redacted>") || text.contains("phash <redacted>;"));
        assertEquals(2, result.redactions());
    }

    @Test
    void theGenericAssignmentRuleAppliesToEverySourceButNeedsAnExplicitSeparator() {
        var system = PanLogRedactor.redact("pan_system", fields("opaque", "Login failed for user u1, password=SYNTHPW key: SYNTHK token=\"SYNTH T\""));
        assertEquals("Login failed for user u1, password=<redacted> key: <redacted> token=<redacted>", system.fields().get("opaque"));
        assertEquals(3, system.redactions());
        var prose = PanLogRedactor.redact("pan_system", fields("opaque", "password policy applied; secret rotation due; key exchange done"));
        assertEquals("password policy applied; secret rotation due; key exchange done", prose.fields().get("opaque"));
        assertEquals(0, prose.redactions());
    }

    @Test
    void theInputIsNeverModifiedAndAnEmptyEntryCostsNothing() {
        var in = fields("path", "/a/phash", "before-change-preview", "<phash>x</phash>");
        var copy = new LinkedHashMap<>(in);
        PanLogRedactor.redact("pan_config", in);
        assertEquals(copy, in);
        assertEquals(0, PanLogRedactor.redact("pan_config", fields("cmd", "")).redactions());
    }

    @Test
    void configFieldRulesDoNotTouchASystemLogEntryThatHappensToUseThoseNames() {
        var result = PanLogRedactor.redact("pan_system", fields("path", "/config/mgt-config/users/phash", "before-change-preview", "<phash>x</phash>"));
        assertEquals("<phash>x</phash>", result.fields().get("before-change-preview"));
        assertEquals(0, result.redactions());
    }
}
