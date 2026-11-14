package com.securityexpert.nexus.ui2.service.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The pod row names what runs the pod and when it was created (health light, 2026-11-14). Synthetic names only. */
class SystemStatusPodOwnerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, Object> row(String metadata) throws Exception {
        JsonNode pod = JSON.readTree("{\"metadata\":" + metadata + ",\"status\":{\"phase\":\"Failed\"}}");
        return SystemStatusService.podRow(pod, null);
    }

    @Test
    void aJobPodNamesItsControllingJobAndCreationTime() throws Exception {
        Map<String, Object> row = row("{\"name\":\"nightly-29910180-abcde\",\"creationTimestamp\":\"2026-11-13T23:00:00Z\","
            + "\"ownerReferences\":[{\"kind\":\"Node\",\"name\":\"other\"},{\"kind\":\"Job\",\"name\":\"nightly-29910180\",\"controller\":true}]}");
        assertEquals("Job", row.get("owner_kind"));
        assertEquals("nightly-29910180", row.get("owner_name"));
        assertEquals("2026-11-13T23:00:00Z", row.get("created_at"));
    }

    @Test
    void aServicePodNamesItsReplicaSet() throws Exception {
        Map<String, Object> row = row("{\"name\":\"svc-a-5f79-x\",\"creationTimestamp\":\"2026-11-13T18:04:36Z\","
            + "\"ownerReferences\":[{\"kind\":\"ReplicaSet\",\"name\":\"svc-a-5f79\",\"controller\":true}]}");
        assertEquals("ReplicaSet", row.get("owner_kind"));
        assertEquals("svc-a-5f79", row.get("owner_name"));
    }

    @Test
    void aBarePodHasNoOwner() throws Exception {
        Map<String, Object> row = row("{\"name\":\"probe\"}");
        assertNull(row.get("owner_kind"));
        assertNull(row.get("owner_name"));
        assertNull(row.get("created_at"));
    }
}
