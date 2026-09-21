package com.securityexpert.nexus.ui2.worker.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * AC-1: the read plan equals {@code PO_DECISION_RECORD_2026_09_14D} CF-2/
 * CF-3 literally -- this test transcribes those clauses' own text and
 * asserts the production constants/methods equal them exactly, the same
 * closed-set proof {@code DeviceFirstContactCommandSetTest} already
 * establishes for the confirm.
 */
class InventoryReadPlanTest {

    @Test
    void checkPointPhysicalReadsEqualCf3Literally() {
        assertEquals(List.of(
                "fw getifs",
                "ip -4 addr show",
                "ip -4 route show table all",
                "cphaprob stat",
                "cphaprob -a if",
                "vsx stat -v"),
                InventoryReadPlan.CHECK_POINT_PHYSICAL_READS);
    }

    @Test
    void checkPointPhysicalStepsWrapWithVsenvZeroOnAVsxHostCf2() {
        assertEquals(List.of(
                "bash -lc 'vsenv 0 && fw getifs'",
                "bash -lc 'vsenv 0 && ip -4 addr show'",
                "bash -lc 'vsenv 0 && ip -4 route show table all'",
                "bash -lc 'vsenv 0 && cphaprob stat'",
                "bash -lc 'vsenv 0 && cphaprob -a if'",
                "bash -lc 'vsenv 0 && vsx stat -v'"),
                InventoryReadPlan.checkPointPhysicalSteps(true));
    }

    @Test
    void checkPointPhysicalStepsAreBareOnANonVsxGatewayCf2() {
        assertEquals(InventoryReadPlan.CHECK_POINT_PHYSICAL_READS, InventoryReadPlan.checkPointPhysicalSteps(false));
    }

    @Test
    void checkPointVsidStepsEqualCf2Cf3Literally() {
        assertEquals(List.of(
                "bash -lc 'vsenv 2 && fw getifs && ip -4 route show'",
                "bash -lc 'vsenv 2 && cphaprob -a if'",
                "bash -lc 'vsenv 2 && cphaprob stat'"),
                InventoryReadPlan.checkPointVsidSteps("2"));
    }

    @Test
    void checkPointVsidStepsRejectAnyNonDigitVsidBeforeSubstitution() {
        assertThrows(IllegalArgumentException.class, () -> InventoryReadPlan.checkPointVsidSteps("2; id"));
        assertThrows(IllegalArgumentException.class, () -> InventoryReadPlan.checkPointVsidSteps(""));
        assertThrows(IllegalArgumentException.class, () -> InventoryReadPlan.checkPointVsidSteps(null));
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
    void checkPointClosedSetHasExactlySixPhysicalReadsAndThreeVsidComposites() {
        assertEquals(6, InventoryReadPlan.CHECK_POINT_PHYSICAL_READS.size());
        assertEquals(3, InventoryReadPlan.checkPointVsidSteps("2").size());
    }

    /** 14E PF-2: the {@code &vsys=<id>} form is dropped -- exactly PF-1's four unscoped requests remain. */
    @Test
    void paloAltoClosedSetHasExactlyFourUnscopedRequestsNoVsysForm() {
        assertEquals(4, InventoryReadPlan.PALO_ALTO_BASE_STEPS.size());
    }
}
