package com.securityexpert.nexus.ui2.service.boot;

import java.time.Clock;
import java.util.logging.Logger;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportRunStore;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;
import com.securityexpert.nexus.ui2.service.privacy.ReportProjectionMasker;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.report.JooqReportData;
import com.securityexpert.nexus.ui2.service.report.ReportAccess;
import com.securityexpert.nexus.ui2.service.report.ReportDocumentMasker;
import com.securityexpert.nexus.ui2.service.report.ReportExecutor;
import com.securityexpert.nexus.ui2.service.report.ReportService;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Reports R1 composition (docs/design/REPORTS_R1_CONTRACT.md): the bounded executor, the read-only report queries, the
 * one masking pass over the existing pseudonymizer and address masker, and the service's own artefact store (absent
 * when the pod has no store mounted: generation then answers STORE_UNAVAILABLE, the preview still works).
 */
@Configuration
public class ReportConfiguration {

    private static final Logger LOG = Logger.getLogger(ReportConfiguration.class.getName());

    @Bean(destroyMethod = "close")
    public ReportExecutor reportExecutor() {
        return new ReportExecutor();
    }

    @Bean
    public ReportService reportService(TransactionBoundary transactionBoundary, DeviceRepository deviceRepository,
            ObjectProvider<CpFailoverService> failover, TopologyNamePseudonymizer names, SubnetPreservingIpMasker addresses,
            DeviceCompositionConfiguration.ArtefactStoreAccess artefactStoreAccess, RbacEvaluator rbac,
            ActionRegistry registry, ReportExecutor executor) {
        ReportService service = new ReportService(
                new JooqReportData(new JooqReportReadRepository(transactionBoundary), deviceRepository, failover.getIfAvailable()),
                new JooqReportRunStore(transactionBoundary), reportStore(artefactStoreAccess),
                new ReportDocumentMasker(new ReportProjectionMasker(names, addresses)), executor,
                new ReportAccess(rbac, registry), Clock.systemUTC());
        try {
            int recovered = service.recoverInterrupted();
            if (recovered > 0) LOG.info("[REPORTS] " + recovered + " run(s) left open by a restart marked FAILED");
        } catch (RuntimeException e) {
            LOG.warning("[REPORTS] could not close runs left open by a restart: " + e.getMessage());
        }
        return service;
    }

    /**
     * Reports R1: report files go through the same encrypted {@code FileArtefactStore} (same key, same envelope) as
     * backups, rooted at {@code UI2_REPORT_STORE_ROOT} -- their own volume, because the service mounts the recovery
     * volume read-only (BK-17). Without that variable (tests, local runs) the artefact store itself is used.
     */
    static com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore reportStore(
            DeviceCompositionConfiguration.ArtefactStoreAccess artefactStoreAccess) {
        String root = System.getenv().getOrDefault("UI2_REPORT_STORE_ROOT", "");
        String keyFile = System.getenv().getOrDefault("UI2_ARTEFACT_STORE_KEY_FILE", "");
        if (root.isBlank() || keyFile.isBlank()) return artefactStoreAccess.storeOrNull();
        String keyBase64 = com.securityexpert.nexus.ui2.platform.SecretFile.readRequired(
                java.nio.file.Path.of(keyFile), "artefact_store_key");
        return new com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore(java.nio.file.Path.of(root),
                com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher.fromBase64Key(keyBase64));
    }
}
