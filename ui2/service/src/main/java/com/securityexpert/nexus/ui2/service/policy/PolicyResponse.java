package com.securityexpert.nexus.ui2.service.policy;

import java.util.Map;

/** Typed marker ensures all policy responses pass through the dedicated privacy projection. */
public record PolicyResponse(Map<String, Object> body) {}
