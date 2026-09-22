package com.securityexpert.nexus.ui2.persistence.device;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

class JooqDevicePlatformFactsRepositoryTest {

    @Test
    void contentVersionsRoundTripThroughTheFlatJsonCodec() {
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("app", "8900-9101");
        versions.put("url", "2026\"09\\20");
        String json = JooqDevicePlatformFactsRepository.toJson(versions);
        assertEquals("{\"app\":\"8900-9101\",\"url\":\"2026\\\"09\\\\20\"}", json);
        assertEquals(versions, JooqDevicePlatformFactsRepository.fromJson(JSONB.valueOf(json)));
        assertEquals(Map.of(), JooqDevicePlatformFactsRepository.fromJson(null));
    }
}
