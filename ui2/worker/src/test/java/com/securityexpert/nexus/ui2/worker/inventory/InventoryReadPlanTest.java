package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * AC-5: the read plan equals {@code PO_DECISION_RECORD_2026_09_14C} §3's
 * command/request set literally -- this test transcribes that section's
 * own text and asserts the production constants equal it exactly, the
 * same closed-set proof {@code DeviceFirstContactCommandSetTest} already
 * establishes for the confirm.
 */
class InventoryReadPlanTest {

    @Test
    void checkPointBaseStepsEqualSection3Literally() {
        assertEquals(List.of(
                "ip -details -4 addr show",
                "ip -6 addr show",
                "ip -4 route show table all",
                "cphaprob -a -m if",
                "cphaprob stat",
                "vsx stat -v"),
                InventoryReadPlan.CHECK_POINT_BASE_STEPS);
    }

    @Test
    void checkPointVsxTemplatesEqualSection3Literally() {
        assertEquals(List.of(
                "vsenv 2; ip -4 addr show; ip -4 route show",
                "vsenv 2; cphaprob stat"),
                InventoryReadPlan.checkPointVsidSteps("2"));
    }

    @Test
    void paloAltoBaseStepsEqualSection3Literally() {
        assertEquals(List.of(
                "<show><system><info/></system></show>",
                "<show><high-availability><state/></high-availability></show>",
                "<show><interface>all</interface></show>",
                "<show><routing><route/></routing></show>"),
                InventoryReadPlan.PALO_ALTO_BASE_STEPS);
    }

    @Test
    void paloAltoVsysFormParamCarriesTheDeclaredKey() {
        assertEquals(Map.of("vsys", "vsys2"), InventoryReadPlan.paloAltoVsysFormParam("vsys2"));
    }

    @Test
    void checkPointClosedSetHasExactlySixBaseStepsAndTwoVsidTemplates() {
        assertEquals(6, InventoryReadPlan.CHECK_POINT_BASE_STEPS.size());
        assertEquals(2, InventoryReadPlan.CHECK_POINT_VSX_STEP_TEMPLATES.size());
    }
}
