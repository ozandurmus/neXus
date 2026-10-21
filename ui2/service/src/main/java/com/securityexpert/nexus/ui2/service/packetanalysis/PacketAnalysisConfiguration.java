package com.securityexpert.nexus.ui2.service.packetanalysis;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.changes.JooqChangeFeedRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;
import com.securityexpert.nexus.ui2.service.changes.ChangeFeedConfiguration;
import com.securityexpert.nexus.ui2.service.changes.ChunkTextReader;
import com.securityexpert.nexus.ui2.service.policy.PolicyQueryService;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;

/**
 * Packet Analysis composition (decision record 2026-10-15 §5): the analysis volume root and its two limits are
 * configuration -- 2 GB per capture, 200 MB per host telemetry file and a 100 GB store by default.
 */
@Configuration
public class PacketAnalysisConfiguration {

    @Bean
    public PacketAnalysisRepository packetAnalysisRepository(TransactionBoundary transactions) {
        return new PacketAnalysisRepository(transactions);
    }

    @Bean
    public PacketCaptureStore packetCaptureStore(
            @Value("${ui2.packet-analysis.root:/app/packet-analysis}") String root,
            @Value("${ui2.packet-analysis.max-file-bytes:2147483648}") long maxFileBytes,
            @Value("${ui2.packet-analysis.max-store-bytes:107374182400}") long maxStoreBytes,
            @Value("${ui2.packet-analysis.max-telemetry-bytes:209715200}") long maxTelemetryBytes) {
        return new PacketCaptureStore(Path.of(root), maxFileBytes, maxStoreBytes, Math.min(maxTelemetryBytes, maxFileBytes));
    }

    @Bean
    public PacketAnalysisCorrelationService packetAnalysisCorrelationService(
            PacketAnalysisRepository packetAnalysisRepository,
            PacketCaptureStore packetCaptureStore,
            DeviceRepository deviceRepository,
            DeviceInventoryRepository deviceInventoryRepository,
            PolicyQueryService policyQueryService) {
        return new PacketAnalysisCorrelationService(
                packetAnalysisRepository,
                packetCaptureStore,
                deviceRepository,
                deviceInventoryRepository,
                policyQueryService);
    }

    /**
     * Packet Analysis > case > Device events (mockup 2026-10-17 screen 4): the stored report,
     * topology and correlation of a case beside the system-log index and the stored cpwd chunk text, read through the
     * same chunk reader and projections as Operations > Change feed.
     */
    @Bean
    public PacketDeviceEventsService packetDeviceEventsService(PacketAnalysisRepository packetAnalysisRepository,
            PacketCaptureStore packetCaptureStore, SystemLogReadRepository systemLogReadRepository, TransactionBoundary transactions,
            ArtefactStoreAccess artefacts, DeviceRepository deviceRepository, TopologyNamePseudonymizer names, LogMasking masking) {
        return new PacketDeviceEventsService(packetAnalysisRepository, PacketCaseFacts.onVolume(packetCaptureStore.root()),
                systemLogReadRepository, new JooqChangeFeedRepository(transactions), new ChunkTextReader(artefacts.storeOrNull()),
                deviceRepository, names, ChangeFeedConfiguration.plainProjection(deviceRepository, names),
                ChangeFeedConfiguration.maskedProjection(deviceRepository, names, masking));
    }
}
