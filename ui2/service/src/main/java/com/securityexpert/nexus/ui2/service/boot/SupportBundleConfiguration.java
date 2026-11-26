package com.securityexpert.nexus.ui2.service.boot;

import java.util.logging.Logger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.supportbundle.SupportBundleDownloadService;
import com.securityexpert.nexus.ui2.service.supportbundle.SupportBundleService;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;

/** Support bundle collection composition (V230, V274): the store, the request service, the download service, and busy retry sweep. */
@Configuration
public class SupportBundleConfiguration {

    @Bean
    public JooqSupportBundleStore supportBundleStore(TransactionBoundary transactionBoundary) {
        return new JooqSupportBundleStore(transactionBoundary);
    }

    @Bean
    public SupportBundleService supportBundleService(DeviceRepository devices, JobAdmissionService admission,
            JooqSupportBundleStore store) {
        return new SupportBundleService(devices, admission, store);
    }

    @Bean
    public BusyRetrySweep supportBundleBusyRetrySweep(SupportBundleService service) {
        return new BusyRetrySweep(service);
    }

    /** Once a minute while scheduling is enabled: closes stale queued requests and schedules the one automatic quiet-hour retry. */
    public static final class BusyRetrySweep {
        private static final Logger LOG = Logger.getLogger(BusyRetrySweep.class.getName());
        private final SupportBundleService service;

        BusyRetrySweep(SupportBundleService service) {
            this.service = service;
        }

        @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
        public void sweep() {
            try {
                service.retryExpiredBusy();
            } catch (RuntimeException e) {
                LOG.warning("[SUPPORT_BUNDLE] busy retry sweep failed: " + e.getClass().getSimpleName());
            }
        }
    }

    @Bean
    public SupportBundleDownloadService supportBundleDownloadService(
            DeviceCompositionConfiguration.ArtefactStoreAccess artefactStoreAccess, JooqSupportBundleStore store,
            DeviceRepository devices, TopologyNamePseudonymizer names, LogMasking logMasking) {
        return new SupportBundleDownloadService(artefactStoreAccess.storeOrNull(), store, devices, names, logMasking);
    }

    @Bean
    public com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore supportBundleUploadStore(
            TransactionBoundary transactionBoundary) {
        return new com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore(transactionBoundary);
    }

    @Bean
    public com.securityexpert.nexus.ui2.service.supportbundle.upload.SupportBundleUploadService supportBundleUploadService(
            SupportBundleDownloadService downloads, JooqSupportBundleStore store,
            com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore uploads) {
        var service = new com.securityexpert.nexus.ui2.service.supportbundle.upload.SupportBundleUploadService(downloads, store, uploads);
        service.reapAtStartup();
        return service;
    }

    @Bean
    public com.securityexpert.nexus.ui2.service.supportbundle.SupportBundleCleanupService supportBundleCleanupService(
            DeviceRepository devices, JobAdmissionService admission,
            com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleUploadStore uploads) {
        return new com.securityexpert.nexus.ui2.service.supportbundle.SupportBundleCleanupService(devices, admission, uploads);
    }
}
