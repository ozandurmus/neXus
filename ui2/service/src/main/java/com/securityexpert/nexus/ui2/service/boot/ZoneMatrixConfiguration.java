package com.securityexpert.nexus.ui2.service.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.zonematrix.JooqZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Zone matrix delivery step 1 wiring (docs/design/ZONE_MATRIX_CONTRACT.md, FROZEN 2026-11-13). */
@Configuration
public class ZoneMatrixConfiguration {
    @Bean
    public ZoneMatrixRepository zoneMatrixRepository(TransactionBoundary transactionBoundary) {
        return new JooqZoneMatrixRepository(transactionBoundary);
    }

    @Bean
    public ZoneMatrixService zoneMatrixService(ZoneMatrixRepository repository, RbacEvaluator rbac) {
        return new ZoneMatrixService(repository, principals(rbac), new ObjectMapper(), Clock.systemUTC());
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
