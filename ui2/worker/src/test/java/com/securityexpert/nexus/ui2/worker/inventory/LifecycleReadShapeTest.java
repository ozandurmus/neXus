package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch.Kind;

class LifecycleReadShapeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Kind> CLI_KINDS = List.of(Kind.CP_CPLIC_PRINT, Kind.CP_CPLP_LIST, Kind.CP_SPARK_SHOW_LICENSE,
            Kind.ASA_SHOW_LICENSE_ALL, Kind.FGT_UPDATE_INFO_CONTRACT, Kind.FGT_AUTOUPDATE_VERSIONS, Kind.FMG_FMUPDATE_DBCONTRACT,
            Kind.MC_SHOW_LICENSES);

    /** Synthetic customer-looking values, built at runtime so no literal looks like a real secret. */
    private static final List<String> SENSITIVE = List.of(
            "192.0.2." + "77", "2001:db8::" + "1", "0123456789abcdef" + "0123456789abcdef", "FTX" + "1234ABCD", "9AB" + "CD12345",
            "admin@" + "example.com", "fw-" + "paris-01.corp.example.net", "Acme" + "Holdings", "Contoso", "Zurich", "Smith",
            "CK-" + "ABCDEF0123456789", "sk" + "12345", "04Dec" + "2026", "gw-a", "10.1.2." + "3/24");

    @Test
    void sensitiveTokensNeverAppearVerbatimInAnyKindsShape() {
        for (Kind kind : CLI_KINDS) {
            for (String value : SENSITIVE) {
                String out = "Host " + value + " Expiration 04Dec" + "2030 Features " + value + " name=" + value + " (" + value + ")\n"
                        + value + "\n" + "Serial Number: " + value + "\nMail:" + value + "," + value;
                String shape = LifecycleReadShape.cliShape(kind, out);
                assertFalse(shape.contains(value), kind + " leaked " + value + ": " + shape);
                // also no fragment of the longer values survives
                if (value.length() > 6) {
                    assertFalse(shape.contains(value.substring(0, 6)) && !LifecycleReadShape.allowList(kind).contains(value.substring(0, 6).toLowerCase()),
                            kind + " leaked a prefix of " + value + ": " + shape);
                }
            }
        }
    }

    @Test
    void maskingRulesFollowTheSpec() {
        assertEquals("host a4 9 ~a9a", LifecycleReadShape.maskLine(LifecycleReadShape.allowList(Kind.CP_CPLIC_PRINT), "host abcd 1234 abcd1234x"));
        assertEquals("CK-m16", LifecycleReadShape.maskLine(LifecycleReadShape.allowList(Kind.CP_CPLIC_PRINT), "CK-ABCDEF0123456789"));
        assertEquals("sku:sk9", LifecycleReadShape.maskLine(LifecycleReadShape.allowList(Kind.CP_CPLIC_PRINT), "sku:sk4711"));
        assertEquals("id=9,status=a4", LifecycleReadShape.maskLine(LifecycleReadShape.allowList(Kind.CP_CPLP_LIST), "id=12,status=Zzzz".replace("Zzzz", "abcd")));
        assertEquals("a5(~a.a)[9]|a3", LifecycleReadShape.maskLine(LifecycleReadShape.allowList(Kind.CP_CPLP_LIST), "hello(a.b)[7]|abc"));
        assertEquals("Status: a4", LifecycleReadShape.maskLine(LifecycleReadShape.allowList(Kind.CP_CPLP_LIST).contains("status") ? LifecycleReadShape.allowList(Kind.CP_CPLP_LIST) : java.util.Set.of(),
                "Status: abcd"), "an allow-listed keyword is kept with its own case");
    }

    @Test
    void identicalLinesCollapseWithACountAndBoundsAreEnforced() {
        String shape = LifecycleReadShape.cliShape(Kind.CP_CPLP_LIST, "patch 1\npatch 2\npatch 3\n\nstatus ready\n");
        assertEquals("patch 9 x3 // status ready", shape);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            many.append("w").append("x".repeat(i + 1)).append('\n');
        }
        String capped = LifecycleReadShape.cliShape(Kind.CP_CPLP_LIST, many.toString());
        assertTrue(capped.contains("[truncated:lines"), capped);
        assertTrue(capped.split(" // ").length <= LifecycleReadShape.MAX_LINES + 0, capped);
        String longLine = LifecycleReadShape.cliShape(Kind.CP_CPLP_LIST, ("a1 ".repeat(200)));
        assertTrue(longLine.length() <= LifecycleReadShape.MAX_LINE_CHARS + 1, longLine.length() + "");
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            wide.append("q".repeat(i + 1) + " ".repeat(1)).append("zz".repeat(60)).append(i).append('\n');
        }
        String total = LifecycleReadShape.cliShape(Kind.CP_CPLP_LIST, wide.toString());
        assertTrue(total.length() <= LifecycleReadShape.MAX_TOTAL_CHARS + 80, total.length() + "");
        assertEquals("-", LifecycleReadShape.cliShape(Kind.CP_CPLP_LIST, "  \n"));
    }

    @Test
    void jsonShapeIsFieldPathsTypesAndArrayLengthsOnly() throws Exception {
        String body = "{\"components\":[{\"componentName\":\"Acme" + "Gateway\",\"expirationDate\":\"2030-01-01\",\"validity\":\"VALID\"},"
                + "{\"componentName\":\"x\",\"expirationDate\":\"2031-01-01\",\"validity\":\"EXPIRED\"},"
                + "{\"componentName\":\"y\",\"expirationDate\":null,\"validity\":\"VALID\"}],\"count\":3,\"ok\":true}";
        String shape = LifecycleReadShape.jsonShape(JSON.readTree(body));
        assertEquals("{components[3]{componentName:string,expirationDate:(null|string),validity:string},count:number,ok:boolean}", shape);
        assertFalse(shape.contains("Acme"));
        assertEquals("[2]number", LifecycleReadShape.jsonShape(JSON.readTree("[1,2]")));
        assertEquals("{m11:string}", LifecycleReadShape.jsonShape(JSON.readTree("{\"fw1234.corp\":\"v\"}")));
        // A reply keyed by something that looks like a plain word (a device name, a serial) is masked too: only documented
        // field names are ever named.
        assertEquals("{m12{expirationDate:string}}",
                LifecycleReadShape.jsonShape(JSON.readTree("{\"fwistanbul01\":{\"expirationDate\":\"2030-01-01\"}}")));
    }

    @Test
    void theLogLineHasTheFixedShapeAndNoRawOutput() {
        String line = LifecycleReadShape.cliLine(Kind.CP_CPLP_LIST, "dev-1", "patch 7\nstatus ready\n");
        assertEquals("[LIFECYCLE_MEASURE] kind=cp_cplp_list device=dev-1 outcome=OK len=21 lines=2 shape=patch 9 // status ready", line);
        assertEquals("[LIFECYCLE_MEASURE] kind=cp_cplp_list device=dev-1 outcome=EMPTY len=1 lines=0 shape=-",
                LifecycleReadShape.cliLine(Kind.CP_CPLP_LIST, "dev-1", "\n"));
        assertEquals("[LIFECYCLE_MEASURE] kind=cp_cplic_print device=unknown outcome=ERROR:TimedOut len=0 lines=0 shape=-",
                LifecycleReadShape.errorLine(Kind.CP_CPLIC_PRINT, "bad id with spaces", "TimedOut"));
        assertEquals("[LIFECYCLE_MEASURE] kind=cp_cplic_print device=dev-1 outcome=SKIPPED:no_approved_form_for_landing_shell len=0 lines=0 shape=-",
                LifecycleReadShape.skippedLine(Kind.CP_CPLIC_PRINT, "dev-1", "no_approved_form_for_landing_shell"));
        assertTrue(LifecycleReadShape.jsonLine(Kind.MC_SYSTEM_VERSION, "d", 404, "x", JSON).contains("outcome=ERROR:Http404"));
        assertTrue(LifecycleReadShape.jsonLine(Kind.MC_SYSTEM_VERSION, "d", 200, "{oops", JSON).contains("outcome=ERROR:JsonParseException"));
        assertTrue(LifecycleReadShape.jsonLine(Kind.MC_SYSTEM_VERSION, "d", 200, "{\"version\":\"1\"}", JSON).contains("shape={version:string}"));
    }

    @Test
    void aMixedTokenBecomesItsCharacterClassPatternAndCarriesNoCharacterOfTheToken() {
        assertEquals("~9-9-9", LifecycleReadShape.classPattern("2027-07-30"));
        assertEquals("~9a9", LifecycleReadShape.classPattern("30Dec2026"));
        assertEquals("~9.9.9.9", LifecycleReadShape.classPattern("192.0.2.14"));
        assertEquals("~a9a9", LifecycleReadShape.classPattern("FGT60FTK20001234"));
        // Any other character, or a pattern longer than 20 characters, stays a length class.
        assertEquals("m17", LifecycleReadShape.classPattern("admin@example.org"));
        assertEquals("m44", LifecycleReadShape.classPattern("a1-b2-c3-d4-e5-f6-a1-b2-c3-d4-e5-f6-a1-b2-c3"));
        for (String token : java.util.List.of("2027-07-30", "30Dec2026", "192.0.2.14", "fw-ist-01.example", "ACME-Bank_2")) {
            String pattern = LifecycleReadShape.classPattern(token);
            for (char c : pattern.toCharArray()) {
                assertTrue(c == '~' || c == '9' || c == 'a' || "-/._+".indexOf(c) >= 0 || c == 'm' , pattern);
            }
        }
    }
}
