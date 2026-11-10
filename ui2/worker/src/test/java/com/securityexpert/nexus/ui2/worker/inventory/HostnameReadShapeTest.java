package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class HostnameReadShapeTest {

    @Test
    void describesCountsAndClassOnly() {
        assertEquals("exit=0 lines=1 first_token_line=1 line1_clinfr=false line1_class=token",
                HostnameReadShape.describe("synthetic-host-01\n", 0));
        assertEquals("exit=0 lines=2 first_token_line=2 line1_clinfr=true line1_class=warning",
                HostnameReadShape.describe("CLINFR0123  Synthetic warning text.\nsynthetic-host-01\n", 0));
        assertEquals("exit=1 lines=1 first_token_line=0 line1_clinfr=true line1_class=warning",
                HostnameReadShape.describe("CLINFR0329  Invalid command:'show hostname'.", 1));
        assertEquals("exit=0 lines=0 first_token_line=0 line1_clinfr=false line1_class=empty", HostnameReadShape.describe("", 0));
        assertEquals("exit=0 lines=2 first_token_line=2 line1_clinfr=false line1_class=other",
                HostnameReadShape.describe("two words here\nsynthetic-host-01", 0));
        assertEquals("exit=-1 lines=0 first_token_line=0 line1_clinfr=false line1_class=empty", HostnameReadShape.describe(null, -1));
    }

    @Test
    void neverEchoesTheText() {
        assertFalse(HostnameReadShape.describe("synthetic-host-01\n", 0).contains("synthetic"));
    }
}
