package com.securityexpert.nexus.ui2.service.changes;

import java.util.Map;

/**
 * The feed's answer with the fact of its projection. The service masks the body itself (device pseudonyms, keyed
 * {@code USR-} principal tokens, the system-log masking rules on free text) when the caller is the aiview persona;
 * the privacy body advice therefore passes a masked body through untouched and refuses an unmasked one for that
 * persona, so one event is never masked twice (a pseudonym re-masked is a different pseudonym).
 */
public record ChangeFeedResponse(boolean masked, Map<String, Object> body) {
}
