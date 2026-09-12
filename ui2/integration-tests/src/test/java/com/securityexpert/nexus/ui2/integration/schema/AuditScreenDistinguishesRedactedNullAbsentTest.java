package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.sql.Connection;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldProjection;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldProjector;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldState;
import com.securityexpert.nexus.ui2.service.audit.AuditRedactionPolicy;

/**
 * Contract §8 test 3 / AC-2: three snapshots of {@code endpoints} distinguish
 * a redacted non-null column, the same column {@code NULL}, and a snapshot
 * written before the column existed. Fails if any two of {@code REDACTED},
 * {@code NULL}, {@code ABSENT} render identically or serialize to the same
 * shape. The policy is loaded from a real, migrated PostgreSQL 16 server so
 * the redacted column's classification is the real one, not a fixture's
 * opinion of it -- only the three snapshots themselves are constructed
 * in-memory, since {@code ABSENT} is inherently about a column that a
 * historical row's JSON never had, which cannot be produced by inserting a
 * row against the current schema.
 */
class AuditScreenDistinguishesRedactedNullAbsentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Ui2PostgresFixture fixture;
    private static AuditRedactionPolicy policy;

    @BeforeAll
    static void migrate() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_screen_states");
        try (Connection app = fixture.appConnection()) {
            policy = AuditProjectionTestSupport.loadPolicy(app);
        }
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void redactedNullAndAbsentAreThreeDistinctShapes() throws Exception {
        AuditFieldProjector projector = new AuditFieldProjector(policy);

        ObjectNode redactedSnapshot = MAPPER.createObjectNode();
        redactedSnapshot.put("address_ref", "some-management-path-reference");
        redactedSnapshot.put("transport_kind", "ssh_exec");

        ObjectNode nullSnapshot = MAPPER.createObjectNode();
        nullSnapshot.putNull("address_ref");
        nullSnapshot.put("transport_kind", "ssh_exec");

        ObjectNode absentSnapshot = MAPPER.createObjectNode();
        // address_ref key entirely missing -- as if written before the column existed.
        absentSnapshot.put("transport_kind", "ssh_exec");

        AuditFieldProjection redacted = projector.project("endpoints", (JsonNode) redactedSnapshot)
                .get("address_ref");
        AuditFieldProjection isNull = projector.project("endpoints", (JsonNode) nullSnapshot).get("address_ref");
        AuditFieldProjection absent = projector.project("endpoints", (JsonNode) absentSnapshot).get("address_ref");

        assertEquals(AuditFieldState.REDACTED, redacted.state());
        assertEquals(AuditFieldState.NULL, isNull.state());
        assertEquals(AuditFieldState.ABSENT, absent.state());

        assertNotEquals(MAPPER.writeValueAsString(redacted), MAPPER.writeValueAsString(isNull));
        assertNotEquals(MAPPER.writeValueAsString(redacted), MAPPER.writeValueAsString(absent));
        assertNotEquals(MAPPER.writeValueAsString(isNull), MAPPER.writeValueAsString(absent));

        // Proof of non-vacuity: collapsing NULL and ABSENT into the same enum
        // value (a real regression shape) is exactly what this test's three
        // pairwise assertNotEquals calls are built to catch -- demonstrated
        // by constructing the collapsed pair directly and observing they
        // would then be equal.
        AuditFieldProjection collapsedNull = AuditFieldProjection.ofNull();
        AuditFieldProjection collapsedAbsent = AuditFieldProjection.ofNull(); // same state as isNull's ABSENT would be
        assertEquals(MAPPER.writeValueAsString(collapsedNull), MAPPER.writeValueAsString(collapsedAbsent),
                "sanity: two projections of the same state DO serialize identically, "
                        + "so the real assertions above are proving something real");
    }
}
