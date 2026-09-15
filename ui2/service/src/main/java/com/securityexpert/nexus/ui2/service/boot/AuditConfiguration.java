package com.securityexpert.nexus.ui2.service.boot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.audit.AuditLogRepository;
import com.securityexpert.nexus.ui2.persistence.audit.JooqAuditLogRepository;
import com.securityexpert.nexus.ui2.service.audit.AuditService;

/**
 * Composition root for the read-only Audit screen
 * (`UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md`). {@code AuditController} is
 * picked up by {@link Ui2Application}'s existing component scan -- it is not
 * excluded there, unlike {@code SessionAdminController} -- and reaches
 * {@link GateChain}/{@link RbacEvaluator} through the beans {@link
 * RbacConfiguration} already wires.
 */
@Configuration
public class AuditConfiguration {

    @Bean
    public AuditLogRepository auditLogRepository(TransactionBoundary transactionBoundary) {
        return new JooqAuditLogRepository(transactionBoundary);
    }

    @Bean
    public AuditService auditService(AuditLogRepository auditLogRepository) {
        return new AuditService(auditLogRepository);
    }
}
