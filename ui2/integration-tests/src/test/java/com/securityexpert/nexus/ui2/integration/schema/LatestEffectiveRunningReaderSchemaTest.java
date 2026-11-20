package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Statement;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.configuration.LatestEffectiveRunningReader;

/**
 * PO 2026-10-24 / Step 0 (V259): the read inventory uses to find a PAN-OS 12 firewall's newest stored
 * effective-running configuration runs under both the shared application role ({@code ui2_app}) and the least-privilege
 * {@code ui2_inventory} module role via {@code v_latest_effective_running_config}; a device with no stored
 * configuration gives none.
 */
class LatestEffectiveRunningReaderSchemaTest {
    @Test
    void theLookupRunsUnderTheAppRoleAndInventoryRoleAndADeviceWithoutConfigurationHasNone() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("latest_effective_running")) {
            var reader = new LatestEffectiveRunningReader(
                    new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            assertTrue(reader.latest("00000000-0000-0000-0000-00000000c0f1").isEmpty());
            assertTrue(reader.latest(null).isEmpty());

            try (var conn = fixture.adminDatabaseConnection(); Statement statement = conn.createStatement()) {
                statement.execute("SET ROLE ui2_inventory");
                var inventoryReader = new LatestEffectiveRunningReader(
                        new JooqTransactionBoundary(DSL.using(conn, SQLDialect.POSTGRES)));
                assertTrue(inventoryReader.latest("00000000-0000-0000-0000-00000000c0f1").isEmpty());
                statement.execute("RESET ROLE");
            }
        }
    }
}
