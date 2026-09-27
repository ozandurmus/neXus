package com.securityexpert.nexus.ui2.worker.backup.pan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PanSetConfigReaderFormatTest {

    private static String brace(int sections) {
        StringBuilder b = new StringBuilder("config {\n");
        for (int i = 0; i < sections; i++) b.append("  section-").append(i).append(" {\n    key value;\n  }\n");
        return b.append("}").toString();
    }

    @Test
    void aCompleteBraceConfigIsAccepted() {
        assertTrue(PanSetConfigReader.isHierarchicalConfig(brace(10)));
    }

    @Test
    void truncatedTinyOrForeignOutputIsRefused() {
        String full = brace(10);
        assertFalse(PanSetConfigReader.isHierarchicalConfig(full.substring(0, full.length() - 1)));
        assertFalse(PanSetConfigReader.isHierarchicalConfig(brace(1)));
        assertFalse(PanSetConfigReader.isHierarchicalConfig("Invalid syntax.\n}"));
    }
}
