package com.securityexpert.nexus.ui2.service.packetanalysis;

import java.util.Map;

/**
 * The Device events answer with the fact of its projection. The service masks the body itself (device pseudonyms for
 * linked devices, topology label pseudonyms, the system-log masking rules on cpwd line text) when the caller is the
 * aiview persona; the privacy body advice passes a masked body through untouched and refuses an unmasked one for that
 * persona, so a pseudonym is never masked twice.
 */
public record PacketDeviceEventsResponse(boolean masked, Map<String, Object> body) {
}
