package com.securityexpert.nexus.ui2.service.search;

import java.util.Map;

/** IP results are matched on stored values, then explicitly projected once by the search service. */
public record IpSearchResponse(Map<String, Object> body, boolean masked) {}
