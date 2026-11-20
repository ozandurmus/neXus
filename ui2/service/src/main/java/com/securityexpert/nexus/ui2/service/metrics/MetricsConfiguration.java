package com.securityexpert.nexus.ui2.service.metrics;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import com.securityexpert.nexus.ui2.platform.metrics.MetricsServer;
import com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Metrics wiring for the service role (enterprise phase 0.4, docs/operations/METRICS.md). The scrape endpoint is NOT a
 * Spring MVC route and NOT an Actuator endpoint: it is {@link MetricsServer} on its own port ({@code UI2_METRICS_PORT},
 * off when unset), so nothing about the main port's authentication, routing or interceptors changes.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {

    /** Holder so the (possibly disabled) server has a bean lifecycle. */
    public static final class ScrapeServer implements AutoCloseable {
        private final MetricsServer server;

        ScrapeServer(MetricsServer server) {
            this.server = server;
        }

        public MetricsServer server() {
            return server;
        }

        @Override
        public void close() {
            if (server != null) server.close();
        }
    }

    @Bean(destroyMethod = "close")
    ScrapeServer scrapeServer(
            @Value("${ui2.metrics.enabled:false}") boolean enabled,
            @Value("${ui2.metrics.port:0}") int port) throws IOException {
        var registry = NexusMetrics.init("service");
        if (!enabled) return new ScrapeServer(null);
        int resolvedPort = port == 0 ? NexusMetrics.DEFAULT_PORT : port;
        return new ScrapeServer(resolvedPort >= 1 && resolvedPort <= 65535 ? MetricsServer.start(resolvedPort, registry) : null);
    }

    @Bean
    FilterRegistrationBean<HttpServerMetricsFilter> httpServerMetricsFilter() {
        var registration = new FilterRegistrationBean<>(new HttpServerMetricsFilter(NexusMetrics.init("service")));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    JobMetricsBinder jobMetricsBinder(DataSource dataSource,
            org.springframework.beans.factory.ObjectProvider<com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess> artefactStoreAccess) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(2);
        var registry = NexusMetrics.init("service");
        JobMetrics.register(registry, () -> {
            Map<String, Long> counts = new HashMap<>();
            jdbc.query("SELECT state, count(*) AS n FROM jobs GROUP BY state",
                    rs -> {
                        counts.put(rs.getString("state"), rs.getLong("n"));
                    });
            return counts;
        });
        com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess access =
                artefactStoreAccess == null ? null : artefactStoreAccess.getIfAvailable();
        if (access != null && access.storeOrNull() instanceof com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore fileStore) {
            NexusMetrics.registerArtefactStoreBytes(registry, fileStore::cachedUsedBytes);
        } else {
            NexusMetrics.registerArtefactStoreBytes(registry, () -> {
                Long bytes = jdbc.queryForObject(
                        "SELECT coalesce((SELECT sum(ciphertext_bytes) FROM backup_artefact WHERE removed_at IS NULL), 0) "
                                + "+ coalesce((SELECT sum(ciphertext_bytes) FROM configuration_artefact), 0)",
                        Long.class);
                return bytes == null ? 0L : bytes;
            });
        }
        return new JobMetricsBinder();
    }

    /** Marker bean: the gauges live in the registry for the process lifetime. */
    static final class JobMetricsBinder {
    }
}
