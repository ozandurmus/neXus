package com.securityexpert.nexus.ui2.service.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.JooqZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.JooqZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.policy.PolicyQueryService;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Zone matrix wiring (docs/design/ZONE_MATRIX_CONTRACT.md, FROZEN 2026-11-13): steps 1-3, and step 4b-1's durable runs
 * (run repository, the run service on its own bounded pool, shut down with the context, and the startup recovery of
 * runs a pod kill left RUNNING). No route and no trigger submits a run yet (step 4b-2).
 */
@Configuration
public class ZoneMatrixConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(ZoneMatrixConfiguration.class);

    @Bean
    public ZoneMatrixRepository zoneMatrixRepository(TransactionBoundary transactionBoundary) {
        return new JooqZoneMatrixRepository(transactionBoundary);
    }

    @Bean
    public ZoneMatrixService zoneMatrixService(
            ZoneMatrixRepository repository,
            RbacEvaluator rbac,
            ObjectProvider<PolicyQueryService> policies,
            ObjectProvider<DeviceRepository> devices,
            ObjectProvider<TopologyNamePseudonymizer> nameMasker,
            ObjectProvider<SubnetPreservingIpMasker> ipMasker) {
        return new ZoneMatrixService(
                repository,
                principals(rbac),
                new ObjectMapper(),
                Clock.systemUTC(),
                policies.getIfAvailable(),
                devices.getIfAvailable(),
                nameMasker.getIfAvailable(() -> new TopologyNamePseudonymizer(new byte[32])),
                ipMasker.getIfAvailable(() -> new SubnetPreservingIpMasker(new byte[32])));
    }

    @Bean
    public ZoneMatrixRunRepository zoneMatrixRunRepository(TransactionBoundary transactionBoundary) {
        return new JooqZoneMatrixRunRepository(transactionBoundary);
    }

    /** The own bounded pool lives inside the service; {@code close} shuts it down in order when the context stops. */
    @Bean(destroyMethod = "close")
    public ZoneMatrixRunService zoneMatrixRunService(
            ZoneMatrixRunRepository runs,
            ZoneMatrixRepository versions,
            ObjectProvider<PolicyQueryService> policies,
            @Value("${ui2.zone-matrix.run-time-limit-seconds:30}") long timeLimitSeconds) {
        return new ZoneMatrixRunService(runs, versions, ZoneMatrixRunService.catalogOf(policies.getIfAvailable()),
                new ObjectMapper(), Clock.systemUTC(), Duration.ofSeconds(timeLimitSeconds));
    }

    /** Section 5.1: a run left RUNNING by a pod kill is set FAILED at startup; only the count is logged. */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> zoneMatrixRunStartupRecovery(ZoneMatrixRunService runs) {
        return event -> {
            try {
                runs.recoverInterrupted();
            } catch (RuntimeException e) {
                LOG.warn("zone_matrix_runs_startup_recovery_failed error_class={}", e.getClass().getSimpleName());
            }
        };
    }

    /** compliance_admin through a real binding (the root identity never counts as holding it); root by its authority. */
    static ZoneMatrixService.Principals principals(RbacEvaluator rbac) {
        return new ZoneMatrixService.Principals() {
            @Override public boolean holdsComplianceAdmin(String actor) {
                var decision = rbac.evaluate(actor, Optional.of(RoleToken.COMPLIANCE_ADMIN), Instant.now());
                return decision.outcome().proceeds() && !RbacEvaluator.ROOT_AUTHORITY.equals(decision.authority().orElse(null));
            }
            @Override public boolean isRoot(String actor) {
                var decision = rbac.evaluate(actor, Optional.empty(), Instant.now());
                return RbacEvaluator.ROOT_AUTHORITY.equals(decision.authority().orElse(null));
            }
        };
    }
}
