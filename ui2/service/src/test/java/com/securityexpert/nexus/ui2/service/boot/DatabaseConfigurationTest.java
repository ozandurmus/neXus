package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class DatabaseConfigurationTest {
    @TempDir Path directory;

    private ApplicationContextRunner context() throws Exception {
        Path user = directory.resolve("user");
        Path password = directory.resolve("password");
        Files.writeString(user, "synthetic_user\n");
        Files.writeString(password, "synthetic_password\n");
        return new ApplicationContextRunner().withUserConfiguration(DatabaseConfiguration.class)
                .withPropertyValues("ui2.db.url=jdbc:postgresql://192.0.2.1:5432/synthetic",
                        "ui2.db.user-file=" + user, "ui2.db.password-file=" + password);
    }

    @Test void boundedDefaultsWithoutOpeningConnections() throws Exception {
        context().run(context -> {
            assertNull(context.getStartupFailure());
            var pool = context.getBean(HikariDataSource.class);
            assertEquals(20, pool.getMaximumPoolSize());
            assertEquals(2, pool.getMinimumIdle());
            assertEquals(10_000, pool.getConnectionTimeout());
            assertEquals(1_800_000, pool.getMaxLifetime());
            assertEquals(60_000, pool.getLeakDetectionThreshold());
            assertNull(pool.getHikariPoolMXBean());
        });
    }

    @Test void propertiesOverridePoolDefaults() throws Exception {
        context().withPropertyValues("ui2.db.pool.maximum-pool-size=7", "ui2.db.pool.minimum-idle=1",
                "ui2.db.pool.connection-timeout=5000", "ui2.db.pool.max-lifetime=900000",
                "ui2.db.pool.leak-detection-threshold=120000",
                "ui2.db.pool.username=ignored", "ui2.db.pool.password=ignored").run(context -> {
            assertNull(context.getStartupFailure());
            var pool = context.getBean(HikariDataSource.class);
            assertEquals(7, pool.getMaximumPoolSize());
            assertEquals(1, pool.getMinimumIdle());
            assertEquals(5000, pool.getConnectionTimeout());
            assertEquals(900000, pool.getMaxLifetime());
            assertEquals(120000, pool.getLeakDetectionThreshold());
            assertNull(pool.getUsername());
            assertNull(pool.getPassword());
        });
    }

    @Test void missingOrEmptySecretsStillFailClosed() throws Exception {
        var configuration = new DatabaseConfiguration("jdbc:postgresql://192.0.2.1/synthetic",
                directory.resolve("missing").toString(), directory.resolve("missing-password").toString());
        assertThrows(IllegalStateException.class, () -> configuration.dataSource(new MockEnvironment()));
        Path empty = directory.resolve("empty");
        Files.writeString(empty, " ");
        assertThrows(IllegalStateException.class, () -> DatabaseConfiguration.readSecretFile(empty, "synthetic"));
    }
}
