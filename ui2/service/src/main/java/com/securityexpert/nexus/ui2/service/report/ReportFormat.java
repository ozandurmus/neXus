package com.securityexpert.nexus.ui2.service.report;

import java.util.Optional;

/** V190 {@code report_run.format}: JSON is the on-screen projection; PDF, XLSX and CSV are files. */
public enum ReportFormat {
    JSON("application/json", "json"),
    PDF("application/pdf", "pdf"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    CSV("text/csv; charset=UTF-8", "csv");

    private final String contentType;
    private final String extension;

    ReportFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    public static Optional<ReportFormat> parse(String value) {
        for (ReportFormat f : values()) {
            if (f.name().equals(value)) return Optional.of(f);
        }
        return Optional.empty();
    }
}
