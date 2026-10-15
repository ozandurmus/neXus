package com.securityexpert.nexus.ui2.sanitizer.format;

import java.util.Locale;

/** The input kinds the sanitizer accepts, decided from the file's content (DATA_SANITIZER_CONTRACT.md §3.1). */
public enum FileType {
    PCAP("pcap"), PCAPNG("pcapng"), XLSX("xlsx"), JSON("json"), CSV("csv"), TEXT("txt");

    private final String extension;

    FileType(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
