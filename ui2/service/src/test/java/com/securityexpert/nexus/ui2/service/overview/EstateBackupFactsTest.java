package com.securityexpert.nexus.ui2.service.overview;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * The estate map's backup facts per device, which the Overview attention list reads for its backup rules
 * (OVERVIEW_ATTENTION_LIST_DESIGN_2026_11_13.md, DRAFT): a target without a live archive is missing one, and the
 * newest archive's time is carried for the stale-backup rule.
 */
class EstateBackupFactsTest {

    @Test
    void aTargetWithoutAnArchiveIsMissingOneAndHasNoBackupTime() {
        var facts = OverviewService.backupFacts(true, null);
        assertThat(facts).containsEntry("no_archive", true).containsEntry("backup_target", true);
        assertThat(facts.get("last_backup_at")).isNull();
    }

    @Test
    void aTargetWithAnArchiveCarriesTheNewestArchiveTime() {
        Instant at = Instant.parse("2026-11-05T02:00:00Z");
        var facts = OverviewService.backupFacts(true, at);
        assertThat(facts).containsEntry("no_archive", false).containsEntry("last_backup_at", "2026-11-05T02:00:00Z");
    }

    @Test
    void aDeviceThatIsNotABackupTargetIsNeverMissingAnArchive() {
        var facts = OverviewService.backupFacts(false, null);
        assertThat(facts).containsEntry("no_archive", false).containsEntry("backup_target", false);
        // an archive collected before the device stopped being a target is still reported as it is
        assertThat(OverviewService.backupFacts(false, Instant.parse("2026-11-01T00:00:00Z")))
                .containsEntry("last_backup_at", "2026-11-01T00:00:00Z").containsEntry("no_archive", false);
    }
}
