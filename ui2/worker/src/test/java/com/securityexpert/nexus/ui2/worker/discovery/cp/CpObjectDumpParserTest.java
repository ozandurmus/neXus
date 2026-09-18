package com.securityexpert.nexus.ui2.worker.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * AC-3: the {@code cpmiquerybin object} tree-format parser (record §10 row
 * 4) turns fixture text into maps that keep CR-0's "a missing key is
 * absent" and HR-2's "an empty value is empty, never repaired" distinction,
 * reads the stable identifier out of the nested {@code AdminInfo} block, and
 * does not shift any other field when one object's own address is empty or
 * its management address is missing entirely.
 */
class CpObjectDumpParserTest {

    @Test
    void ignoresPreambleAndReturnsNoObjectsWhenNoDumpStarts() {
        assertTrue(CpObjectDumpParser.parseObjects("login banner\nmdsenv changed context\n").isEmpty());

        List<Map<String, Object>> objects = CpObjectDumpParser.parseObjects("banner\n(\n fixture-name\n :name (fixture-name)\n)\n");
        assertEquals("fixture-name", objects.get(0).get("name"));
    }

    @Test
    void missingKeyIsAbsentAndEmptyValueIsPresentButEmpty() {
        String text = "(fixture-name-1\n"
                + "\t:name (fixture-name-1)\n"
                + "\t:ipaddr ()\n"
                + ")\n";
        List<Map<String, Object>> objects = CpObjectDumpParser.parseObjects(text);

        assertEquals(1, objects.size());
        Map<String, Object> object = objects.get(0);
        assertEquals("fixture-name-1", object.get("name"));
        assertTrue(object.containsKey("ipaddr"), "an empty leaf still keeps its key present");
        assertEquals("", object.get("ipaddr"));
        assertFalse(object.containsKey("mgmt_ip"), "a key never sent is absent, not empty");
    }

    @Test
    void stableIdentifierIsReadFromTheNestedAdminInfoBlock() {
        String text = "(fixture-name-1\n"
                + "\t:name (fixture-name-1)\n"
                + "\t:AdminInfo (\n"
                + "\t\t:chkpf_uid (fixture-uid-1)\n"
                + "\t\t:CreatedBy (fixture-admin)\n"
                + "\t)\n"
                + ")\n";
        Map<String, Object> object = CpObjectDumpParser.parseObjects(text).get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> adminInfo = (Map<String, Object>) object.get("AdminInfo");
        assertEquals("fixture-uid-1", adminInfo.get("chkpf_uid"));
        // an unknown key inside the block is tolerated, never rejected (hundreds of keys exist per object).
        assertEquals("fixture-admin", adminInfo.get("CreatedBy"));
    }

    @Test
    void clusterReferenceCarriesAnIdentifierAlongsideADisplayName() {
        String text = "(fixture-member-1\n"
                + "\t:name (fixture-member-1)\n"
                + "\t:cluster_object (\n"
                + "\t\t:chkpf_uid (fixture-cluster-uid-1)\n"
                + "\t\t:name (fixture-cluster-name-1)\n"
                + "\t)\n"
                + ")\n";
        Map<String, Object> object = CpObjectDumpParser.parseObjects(text).get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> clusterObject = (Map<String, Object>) object.get("cluster_object");
        assertEquals("fixture-cluster-uid-1", clusterObject.get("chkpf_uid"));
        assertEquals("fixture-cluster-name-1", clusterObject.get("name"));
    }

    @Test
    void aQuotedValueIsUnquoted() {
        String text = "(fixture-name-1\n"
                + "\t:name (\"fixture quoted name\")\n"
                + ")\n";
        Map<String, Object> object = CpObjectDumpParser.parseObjects(text).get(0);
        assertEquals("fixture quoted name", object.get("name"));
    }

    @Test
    void quotedValueMayContainParentheses() {
        String text = "(fixture-name-1\n"
                + "\t:comments (\"Created (HA active)\")\n"
                + ")\n";

        Map<String, Object> object = CpObjectDumpParser.parseObjects(text).get(0);

        assertEquals("Created (HA active)", object.get("comments"));
    }

    @Test
    void quotedValueSkipsEscapedQuotesBeforeItsClosingQuote() {
        String text = "(fixture-name-1\n"
                + "\t:comments (\"Created (HA \\\"active\\\")\")\n"
                + ")\n";

        Map<String, Object> object = CpObjectDumpParser.parseObjects(text).get(0);

        assertEquals("Created (HA \\\"active\\\")", object.get("comments"));
    }

    @Test
    void objectNameIsUsedWhenNameAttributeIsAbsent() {
        String text = "(fixture-name-1\n"
                + "\t:ipaddr (198.51.100.9)\n"
                + ")\n";

        Map<String, Object> object = CpObjectDumpParser.parseObjects(text).get(0);

        assertEquals("fixture-name-1", object.get("name"));
    }

    /** An empty own address and a missing management address must not shift any other field on that object or the next. */
    @Test
    void emptyOwnAddressAndMissingManagementAddressDoNotShiftOtherFields() {
        String text = "(fixture-vs-member-1\n"
                + "\t:name (fixture-vs-member-1)\n"
                + "\t:ipaddr ()\n"
                + "\t:cp_products_installed (true)\n"
                + ")\n"
                + "(fixture-name-2\n"
                + "\t:name (fixture-name-2)\n"
                + "\t:ipaddr (198.51.100.9)\n"
                + "\t:mgmt_ip (198.51.100.9)\n"
                + "\t:cp_products_installed (false)\n"
                + ")\n";
        List<Map<String, Object>> objects = CpObjectDumpParser.parseObjects(text);

        assertEquals(2, objects.size());
        Map<String, Object> first = objects.get(0);
        assertEquals("", first.get("ipaddr"));
        assertFalse(first.containsKey("mgmt_ip"));
        assertEquals("true", first.get("cp_products_installed"));

        Map<String, Object> second = objects.get(1);
        assertEquals("198.51.100.9", second.get("ipaddr"));
        assertEquals("198.51.100.9", second.get("mgmt_ip"));
        assertEquals("false", second.get("cp_products_installed"));
    }
}
