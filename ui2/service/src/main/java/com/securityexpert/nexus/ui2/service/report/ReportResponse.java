package com.securityexpert.nexus.ui2.service.report;

/**
 * A Reports response body that was already projected by {@link ReportDocumentMasker} (or carries no identity at all).
 * {@code PrivacyMaskingResponseBodyAdvice} passes it through unchanged -- masking a pseudonym again would give a
 * different pseudonym -- and refuses to send an unmasked one to a replay viewer.
 */
public record ReportResponse(Object body, boolean masked) {
}
