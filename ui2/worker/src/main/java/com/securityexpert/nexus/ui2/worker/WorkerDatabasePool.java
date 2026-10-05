package com.securityexpert.nexus.ui2.worker;

import java.util.Properties;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;

/** One bounded pool shared by job transactions and credential resolution. */
final class WorkerDatabasePool {
    private WorkerDatabasePool() {}
    // This process owns one worker pool; counters are process-local and projected under its role.
    static final java.util.concurrent.atomic.LongAdder TIMEOUTS = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder WAIT_NANOS = new java.util.concurrent.atomic.LongAdder();

    static HikariDataSource create(DataSource source, Properties properties) {
        HikariDataSource pool = new HikariDataSource();
        pool.setDataSource(source);
        pool.setMetricsTrackerFactory((name, stats) -> new com.zaxxer.hikari.metrics.IMetricsTracker() {
            @Override public void recordConnectionAcquiredNanos(long elapsed) { WAIT_NANOS.add(elapsed); }
            @Override public void recordConnectionTimeout() { TIMEOUTS.increment(); }
        });
        pool.setPoolName("worker-database");
        pool.setMaximumPoolSize(Integer.parseInt(properties.getProperty("ui2.db.pool.maximum-pool-size", "10")));
        pool.setMinimumIdle(Integer.parseInt(properties.getProperty("ui2.db.pool.minimum-idle", "2")));
        pool.setConnectionTimeout(Long.parseLong(properties.getProperty("ui2.db.pool.connection-timeout", "10000")));
        pool.setMaxLifetime(Long.parseLong(properties.getProperty("ui2.db.pool.max-lifetime", "1800000")));
        pool.setLeakDetectionThreshold(Long.parseLong(properties.getProperty("ui2.db.pool.leak-detection-threshold", "60000")));
        return pool;
    }
}
