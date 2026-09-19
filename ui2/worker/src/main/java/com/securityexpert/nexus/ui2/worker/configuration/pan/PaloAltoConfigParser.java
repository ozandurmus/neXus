package com.securityexpert.nexus.ui2.worker.configuration.pan;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import javax.xml.stream.XMLStreamException;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigFormat;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseContext;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseResult;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigVendor;
import com.securityexpert.nexus.ui2.worker.configuration.core.VendorConfigParser;

/**
 * Extensible parser for Palo Alto PAN-OS XML configurations (Phase 2),
 * wrapping {@link PaloAltoConfigStreamProcessor}.
 */
public final class PaloAltoConfigParser implements VendorConfigParser {

    @Override
    public ConfigVendor vendor() {
        return ConfigVendor.PALO_ALTO;
    }

    @Override
    public ConfigFormat format() {
        return ConfigFormat.PAN_OS_XML;
    }

    @Override
    public ConfigParseResult parse(ConfigParseContext context, InputStream content) {
        try {
            PaloAltoConfigStreamProcessor.Processed processed = PaloAltoConfigStreamProcessor.process(content);
            List<ConfigurationIndexEntry> index = processed.index();
            int totalSettings = index.stream().mapToInt(ConfigurationIndexEntry::entryCount).sum();

            return new ConfigParseResult(
                    ConfigVendor.PALO_ALTO,
                    Optional.empty(),
                    0,
                    "", // PAN-OS has no text view per CG-5/CG-6
                    index,
                    List.of(),
                    List.of(),
                    totalSettings
            );
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to parse Palo Alto XML configuration stream", e);
        }
    }
}
