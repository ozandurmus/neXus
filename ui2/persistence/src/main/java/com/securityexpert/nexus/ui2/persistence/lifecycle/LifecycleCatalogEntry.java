package com.securityexpert.nexus.ui2.persistence.lifecycle;

import java.time.Instant;
import java.time.LocalDate;

public record LifecycleCatalogEntry(String catalogId, String vendor, String kind, String product,
        LocalDate endOfSale, LocalDate endOfSupport, LocalDate endOfEngineering,
        String source, String note, String importedBy, Instant importedAt) { }
