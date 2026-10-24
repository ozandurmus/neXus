package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.configuration.LatestEffectiveRunningReader;

/**
 * PO 2026-10-24: the read inventory uses to find a PAN-OS 12 firewall's newest stored effective-running configuration runs
 * under the application role against the migrated schema (every joined table and column exists and is readable); a
 * device with no stored configuration gives none.
 */
class LatestEffectiveRunningReaderSchemaTest {
    @Test
    void theLookupRunsUnderTheAppRoleAndADeviceWithoutConfigurationHasNone() {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("latest_effective_running")) {
            var reader = new LatestEffectiveRunningReader(
                    new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            assertTrue(reader.latest("00000000-0000-0000-0000-00000000c0f1").isEmpty());
            assertTrue(reader.latest(null).isEmpty());
        }
    }
}
