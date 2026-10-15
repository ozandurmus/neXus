package com.securityexpert.nexus.ui2.service.packetanalysis;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;

/**
 * Packet Analysis composition (decision record 2026-10-15 §5): the analysis volume root and its two limits are
 * configuration -- 2 GB per file and a 100 GB store by default.
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
            @Value("${ui2.packet-analysis.max-store-bytes:107374182400}") long maxStoreBytes) {
        return new PacketCaptureStore(Path.of(root), maxFileBytes, maxStoreBytes);
    }
}
