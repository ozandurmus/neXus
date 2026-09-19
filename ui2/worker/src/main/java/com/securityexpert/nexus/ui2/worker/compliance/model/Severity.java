package com.securityexpert.nexus.ui2.worker.compliance.model;

public enum Severity {
    CRITICAL(10),
    HIGH(5),
    MEDIUM(2),
    LOW(1),
    INFORMATIONAL(0);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
