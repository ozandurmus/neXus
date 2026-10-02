package com.securityexpert.nexus.ui2.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class WorkerDatabasePoolTest {
    @Test void boundedDefaultsAndPropertyOverrides() {
        var source = mock(DataSource.class);
        try (var pool = WorkerDatabasePool.create(source, new Properties())) {
            assertSame(source, pool.getDataSource());
            assertEquals(10, pool.getMaximumPoolSize());
            assertEquals(2, pool.getMinimumIdle());
            assertEquals(10_000, pool.getConnectionTimeout());
            assertEquals(1_800_000, pool.getMaxLifetime());
            assertEquals(60_000, pool.getLeakDetectionThreshold());
            assertNull(pool.getHikariPoolMXBean());
        }
        Properties properties = new Properties();
        properties.setProperty("ui2.db.pool.maximum-pool-size", "4");
        properties.setProperty("ui2.db.pool.minimum-idle", "1");
        properties.setProperty("ui2.db.pool.connection-timeout", "5000");
        properties.setProperty("ui2.db.pool.max-lifetime", "900000");
        properties.setProperty("ui2.db.pool.leak-detection-threshold", "120000");
        try (var pool = WorkerDatabasePool.create(source, properties)) {
            assertEquals(4, pool.getMaximumPoolSize());
            assertEquals(1, pool.getMinimumIdle());
            assertEquals(5000, pool.getConnectionTimeout());
            assertEquals(900000, pool.getMaxLifetime());
            assertEquals(120000, pool.getLeakDetectionThreshold());
        }
    }
}
