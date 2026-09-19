package com.securityexpert.nexus.ui2.jobs.transport;

/** PAN XML API endpoint reference -- declared, not implemented at this movement. */
public record ApiTarget(String endpointId, String baseUrl) {

    public ApiTarget {
        java.util.Objects.requireNonNull(endpointId, "endpointId");
        java.util.Objects.requireNonNull(baseUrl, "baseUrl");
        baseUrl = baseUrl.trim();
    }
}
