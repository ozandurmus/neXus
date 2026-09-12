package com.securityexpert.nexus.ui2.jobs.transport;

/** {@code {http_method, type, category, target_scope}} (C4 §2.3) -- declared, not implemented at this movement. */
public record XmlApiSpec(String httpMethod, String type, String category, String targetScope) {
}
