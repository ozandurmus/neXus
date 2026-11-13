package com.securityexpert.nexus.ui2.service.policy;

import java.util.Map;

/**
 * A cutover segment or assessment answer with the fact of its projection. The controller masks the body itself
 * ({@link CutoverPrivacy}) for the aiview persona; the privacy body advice passes a masked body through untouched and
 * refuses an unmasked one for that persona, so a value is never masked twice.
 */
public record CutoverResponse(boolean masked, Map<String, Object> body) {
}
