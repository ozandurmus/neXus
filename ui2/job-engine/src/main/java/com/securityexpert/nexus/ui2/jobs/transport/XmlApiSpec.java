package com.securityexpert.nexus.ui2.jobs.transport;

import java.util.Map;

/**
 * {@code {http_method, type, category, target_scope}} (C4 §2.3), widened by
 * the Palo Alto discovery transport movement to carry what a real XML API
 * call needs and {@code xmlApiCall}'s session-less signature has no other
 * room for: {@code formParams} is the POST body (PAN discovery contract
 * T-1: the vendor's username/password are form-encoded body fields, never a
 * URL or header value the URL could log), and {@code headers} carries the
 * per-call header set (T-1: the session key travels in a header, never a
 * query parameter). Both are empty maps where a call needs neither. No
 * production caller before this movement constructed this record with any
 * value, so widening it changes no existing behaviour.
 */
public record XmlApiSpec(
        String httpMethod,
        String type,
        String category,
        String targetScope,
        Map<String, String> formParams,
        Map<String, String> headers) {
}
