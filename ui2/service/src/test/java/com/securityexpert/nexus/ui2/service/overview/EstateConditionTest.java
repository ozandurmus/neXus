package com.securityexpert.nexus.ui2.service.overview;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.compliance.ComplianceService.DeviceCriticals;

/**
 * Estate-map cell classification (UI review 2026-10-17, P0-1): green only for a demonstrated critical pass. A device
 * without evidence, or without any critical control, is not assessed -- zero is neutral, never green.
 */
class EstateConditionTest {

    @Test
    void aDeviceWithNoEvidencedResultIsNotAssessedNeverClear() {
        // every check "evidence not collected" (the 12 Cisco ASA): 0 pass, 0 fail
        DeviceCriticals noEvidence = new DeviceCriticals(0, 0, 0, 0);
        assertThat(OverviewService.estateCondition(noEvidence, true)).isEqualTo("not_assessed");
        assertThat(OverviewService.estateConditionReason(noEvidence, "not_assessed")).isEqualTo("no_evidence");
        // even when critical controls exist and all of them lack evidence
        assertThat(OverviewService.estateCondition(new DeviceCriticals(0, 3, 0, 0), true)).isEqualTo("not_assessed");
    }

    @Test
    void aDeviceWithoutAnyCriticalControlIsNotAssessed() {
        // a FortiGate: checks evidenced, a high-severity one failing, no critical control in the catalog
        DeviceCriticals noCritical = new DeviceCriticals(0, 0, 0, 7);
        assertThat(OverviewService.estateCondition(noCritical, true)).isEqualTo("not_assessed");
        assertThat(OverviewService.estateConditionReason(noCritical, "not_assessed")).isEqualTo("no_critical_control");
    }

    @Test
    void noEvaluationIsNotAssessed() {
        assertThat(OverviewService.estateCondition(null, true)).isEqualTo("not_assessed");
        assertThat(OverviewService.estateConditionReason(null, "not_assessed")).isEqualTo("not_evaluated");
    }

    @Test
    void zeroFailingCriticalChecksWithEvidenceIsClear() {
        DeviceCriticals demonstrated = new DeviceCriticals(0, 0, 4, 20);
        assertThat(OverviewService.estateCondition(demonstrated, true)).isEqualTo("clear");
        assertThat(OverviewService.estateConditionReason(demonstrated, "clear")).isNull();
    }

    @Test
    void theOtherConditionsKeepTheirOrder() {
        assertThat(OverviewService.estateCondition(new DeviceCriticals(2, 1, 0, 9), false)).isEqualTo("critical");
        assertThat(OverviewService.estateCondition(new DeviceCriticals(0, 0, 4, 20), false)).isEqualTo("ageing");
        assertThat(OverviewService.estateCondition(null, false)).isEqualTo("ageing");
        assertThat(OverviewService.estateCondition(new DeviceCriticals(0, 1, 3, 20), true)).isEqualTo("partly_assessed");
    }
}
