package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch.Kind;

/** Masked shapes of the group B reads (PO 2026-11-10): the CLI allow-lists, the XML shape and the Management Center envelope. */
class LifecycleReadShapeGroupBTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Kind> CLI_KINDS = List.of(Kind.FGT_UPDATE_INFO_CONTRACT, Kind.FGT_AUTOUPDATE_VERSIONS,
            Kind.FMG_FMUPDATE_DBCONTRACT, Kind.MC_SHOW_LICENSES);

    /** Synthetic customer-looking values, built at runtime so no literal looks like a real secret. */
    private static final List<String> SENSITIVE = List.of(
            "192.0.2." + "77", "2001:db8::" + "1", "0123456789abcdef" + "0123456789abcdef", "FGT60F" + "1234567890", "FMG-VM" + "0000000001",
            "FTX" + "1234ABCD", "9AB" + "CD12345", "admin@" + "example.com", "fw-" + "paris-01.corp.example.net", "Acme" + "Holdings",
            "Contoso", "Zurich", "Smith", "ACME" + "HOLDINGS", "Acme" + "Corp", "jsmith" + "01", "10.1.2." + "3/24", "04Dec" + "2026",
            "1234" + "5678", "SGOS" + "9876");

    @Test
    void sensitiveTokensNeverAppearVerbatimInAnyGroupBKindsShape() {
        for (Kind kind : CLI_KINDS) {
            for (String value : SENSITIVE) {
                String out = "Company: " + value + "\nAccount ID: " + value + " user " + value + "\n" + value + "\nSerial Number: "
                        + value + "\nMail:" + value + "," + value + " (" + value + ")";
                String shape = LifecycleReadShape.cliShape(kind, out);
                assertFalse(shape.contains(value), kind + " leaked " + value + ": " + shape);
            }
        }
    }

    @Test
    void organisationEmailHostAndSerialLikeTokensAreMasked() {
        assertEquals("a4 m12 ~a.a.a9 ~a9a9", LifecycleReadShape.cliShape(Kind.FMG_FMUPDATE_DBCONTRACT,
                "Acme jsmith@x.io0 fw.corp.io0 FGT60F123456789"));
        assertEquals("a4 m12 ~a.a.a9 ~a9a9", LifecycleReadShape.cliShape(Kind.FGT_UPDATE_INFO_CONTRACT,
                "Acme jsmith@x.io0 fw.corp.io0 FGT60F123456789"));
    }

    @Test
    void upperCaseContractCodesAreVerbatimOnlyForTheContractAndDbcontractReads() {
        String line = "FMWR COMP ENHN ABCDEFG ab A 12 SN";
        assertEquals("FMWR COMP ENHN a7 a2 a1 9 U2", LifecycleReadShape.cliShape(Kind.FGT_UPDATE_INFO_CONTRACT, line));
        assertEquals("FMWR COMP ENHN a7 a2 a1 9 SN", LifecycleReadShape.cliShape(Kind.FMG_FMUPDATE_DBCONTRACT, line));
        assertEquals("a4 a4", LifecycleReadShape.cliShape(Kind.FGT_AUTOUPDATE_VERSIONS, "FMWR COMP"));
        assertEquals("a4 a4", LifecycleReadShape.cliShape(Kind.MC_SHOW_LICENSES, "FMWR COMP"));
        // a code with a digit, a dash or a dot is not a code
        assertEquals("~a-a ~a9a ~a.a", LifecycleReadShape.cliShape(Kind.FGT_UPDATE_INFO_CONTRACT, "FMW-R FMW1R FMW.R"));
    }

    @Test
    void theAllowListsKeepOnlyGenericVendorWords() {
        assertEquals("Contract Expiry Date: 9 Dec 9 // AV Engine: Version 9",
                LifecycleReadShape.cliShape(Kind.FGT_AUTOUPDATE_VERSIONS, "Contract Expiry Date: 2030 Dec 31\nAV Engine: Version 7"));
        assertEquals("Mon Dec 9 AI/ML Flow-Based n/a", LifecycleReadShape.cliShape(Kind.FGT_AUTOUPDATE_VERSIONS, "Mon Dec 31 AI/ML Flow-Based n/a"));
        assertEquals("License Valid Expiration 9 Nov 9 // Serial Number: ~9a9",
                LifecycleReadShape.cliShape(Kind.MC_SHOW_LICENSES, "License Valid Expiration 12 Nov 2030\nSerial Number: 1234ABCD56"));
        assertEquals("fgt: a2 serial: 9 AccountID: a4",
                LifecycleReadShape.cliShape(Kind.FMG_FMUPDATE_DBCONTRACT, "fgt: ab serial: 5 AccountID: abcd"));
        assertEquals("a9 a7", LifecycleReadShape.cliShape(Kind.MC_SHOW_LICENSES, "internals secrets"));
    }

    @Test
    void xmlShapeNamesAllowListedElementsAndAttributeNamesAndNeverAnyText() throws Exception {
        String xml = "<response status=\"success\" code=\"19\"><result><licenses>"
                + "<entry name=\"" + "Acme" + "\"><feature>Secret" + " Feature</feature><serial>0123" + "456789</serial></entry>"
                + "<entry><feature>x</feature><customerNote>hello</customerNote></entry><entry/></licenses></result></response>";
        String shape = LifecycleReadShape.xmlShape(xml);
        assertEquals("response[code=19,status=success]{result*1{licenses*1{entry*3[name]{feature*1,serial*1,m12*1}}}}", shape);
        assertFalse(shape.contains("Acme") || shape.contains("Secret") || shape.contains("0123") || shape.contains("hello") || shape.contains("customerNote"), shape);
    }

    @Test
    void onlyTheRootStatusAndCodeValuesCanBeEmittedAndOnlyInTheirClosedForms() throws Exception {
        assertEquals("response[code=7,status=error]", LifecycleReadShape.xmlShape("<response status=\"error\" code=\"7\"/>"));
        assertEquals("response[code,status]", LifecycleReadShape.xmlShape("<response status=\"weird" + "value\" code=\"7a\"/>"));
        assertEquals("response[code,status]", LifecycleReadShape.xmlShape("<response status=\"Success\" code=\"\"/>"));
        assertEquals("response[code,status]", LifecycleReadShape.xmlShape("<response status=\"success \" code=\"1234567\"/>"));
        // a status attribute below the root never carries a value
        assertEquals("response{result*1{msg*1[status]}}", LifecycleReadShape.xmlShape("<response><result><msg status=\"success\"/></result></response>"));
        // an unknown root or attribute name is masked by length
        assertEquals("m6[m4]", LifecycleReadShape.xmlShape("<secret abcd=\"1\"/>"));
    }

    @Test
    void xmlParsingRefusesDoctypesEntitiesAndOversizedBodies() {
        assertThrows(org.xml.sax.SAXException.class, () -> LifecycleReadShape.xmlShape(
                "<!DOCTYPE response [<!ENTITY e SYSTEM \"file:///etc/hostname\">]><response>&e;</response>"));
        assertThrows(org.xml.sax.SAXException.class, () -> LifecycleReadShape.xmlShape("<response"));
        assertThrows(java.io.IOException.class, () -> LifecycleReadShape.xmlShape("<response>" + "a".repeat(300_000) + "</response>"));
        assertEquals("-", org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> LifecycleReadShape.xmlShape("  ")));
    }

    @Test
    void xmlShapeIsBoundedInDepthWidthAndLength() throws Exception {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            deep.append("<entry>");
        }
        for (int i = 0; i < 30; i++) {
            deep.append("</entry>");
        }
        assertTrue(LifecycleReadShape.xmlShape(deep.toString()).contains("..."));
        StringBuilder wide = new StringBuilder("<response>");
        for (int i = 0; i < 200; i++) {
            wide.append("<x").append("y".repeat(i + 1)).append("/>");
        }
        String shape = LifecycleReadShape.xmlShape(wide.append("</response>").toString());
        assertTrue(shape.length() <= LifecycleReadShape.MAX_TOTAL_CHARS + 30, shape.length() + "");
    }

    @Test
    void xmlLineHasTheFixedLogShape() {
        assertEquals("[LIFECYCLE_MEASURE] kind=pan_support_info device=dev-1 outcome=EMPTY len=0 lines=0 shape=-",
                LifecycleReadShape.xmlLine(Kind.PAN_SUPPORT_INFO, "dev-1", ""));
        assertEquals("[LIFECYCLE_MEASURE] kind=pan_support_info device=dev-1 outcome=ERROR:XmlUnusable len=8 lines=1 shape=-",
                LifecycleReadShape.xmlLine(Kind.PAN_SUPPORT_INFO, "dev-1", "<broken>"));
        assertTrue(LifecycleReadShape.xmlLine(Kind.PAN_LICENSE_INFO, "dev-1", "<response status=\"success\"><result/></response>")
                .contains("outcome=OK len=") && LifecycleReadShape.xmlLine(Kind.PAN_LICENSE_INFO, "dev-1",
                        "<response status=\"success\"><result/></response>").endsWith("lines=1 shape=response[status=success]{result*1}"));
    }

    @Test
    void upgradeStatusFieldNamesAreDocumentedAndAnythingElseIsMasked() throws Exception {
        String body = "[{\"current_version\":\"9.0.0\",\"type\":\"GRID\",\"upgrade_state\":\"NONE\",\"member\":\"gm.corp.example\","
                + "\"status_value\":\"WORKING\",\"steps_total\":3,\"reverted\":false,\"hotfixes\":[],\"unique_id\":\"x\",\"customer_field\":1}]";
        String shape = LifecycleReadShape.jsonShape(JSON.readTree(body));
        assertEquals("[1]{current_version:string,type:string,upgrade_state:string,member:string,status_value:string,steps_total:number,"
                + "reverted:boolean,hotfixes[0],unique_id:string,m14:number}", shape);
        assertFalse(shape.contains("customer_field") || shape.contains("gm.corp"));
        for (String field : List.of("current_version_summary", "upgrade_group", "status_text", "status_time", "ha_status", "grid_state",
                "group_state", "element_status", "distribution_version", "distribution_state", "alternate_version", "upload_version",
                "steps", "steps_completed", "comment", "pnode_role", "status_value_update_time", "allow_distribution", "allow_upgrade",
                "message", "reply", "messages")) {
            assertEquals("{" + field + ":string}", LifecycleReadShape.jsonShape(JSON.readTree("{\"" + field + "\":\"v\"}")), field);
        }
    }

    @Test
    void theManagementCenterEnvelopeLineCarriesTheClosedStatusWordAndTheReplyShapeOnly() {
        String serial = "FTX" + "1234ABCD";
        String ok = "{\"reply\":\"License: Valid\\nExpiration: 12 Nov 2030\\nSerial Number: " + serial + "\\nCustomer: Acme" + "Corp\","
                + "\"messages\":[],\"status\":\"SUCCESS\"}";
        String line = LifecycleReadShape.commandEnvelopeLine(Kind.MC_SHOW_LICENSES, "dev-1", 200, ok, JSON);
        assertTrue(line.startsWith("[LIFECYCLE_MEASURE] kind=mc_show_licenses device=dev-1 outcome=OK "), line);
        assertTrue(line.contains("shape=status=SUCCESS envelope={reply:string,messages[0],status:string} reply=License: Valid // "
                + "Expiration: 9 Nov 9 // Serial Number: ~a9a // a8: a8"), line);
        assertFalse(line.contains(serial) || line.contains("Acme") || line.contains("2030"), line);
        String failed = LifecycleReadShape.commandEnvelopeLine(Kind.MC_SHOW_LICENSES, "dev-1", 200,
                "{\"reply\":\"\",\"messages\":[\"% No such" + " command\"],\"status\":\"FAILURE\"}", JSON);
        assertTrue(failed.contains("status=FAILURE ") && failed.contains("messages[1]string") && failed.endsWith("reply=-"), failed);
        String odd = LifecycleReadShape.commandEnvelopeLine(Kind.MC_SHOW_LICENSES, "dev-1", 200, "{\"reply\":\"x\",\"status\":\"Acme" + "Status\"}", JSON);
        assertTrue(odd.contains("status=m10 "), odd);
        assertFalse(odd.contains("AcmeStatus"), odd);
        assertTrue(LifecycleReadShape.commandEnvelopeLine(Kind.MC_SHOW_LICENSES, "d", 200, "{\"reply\":\"x\"}", JSON).contains("status=absent "));
        assertTrue(LifecycleReadShape.commandEnvelopeLine(Kind.MC_SHOW_LICENSES, "d", 500, "boom", JSON).contains("outcome=ERROR:Http500"));
        assertTrue(LifecycleReadShape.commandEnvelopeLine(Kind.MC_SHOW_LICENSES, "d", 200, "", JSON).contains("outcome=EMPTY"));
        assertTrue(LifecycleReadShape.commandEnvelopeLine(Kind.MC_SHOW_LICENSES, "d", 200, "{oops", JSON).contains("outcome=ERROR:JsonParseException"));
    }
}
