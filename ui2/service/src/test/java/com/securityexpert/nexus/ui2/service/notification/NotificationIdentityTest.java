package com.securityexpert.nexus.ui2.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/** Masked vs raw notification identity (V193): every notification type, free text, fail-closed. */
class NotificationIdentityTest {
    private static final Instant FROM = Instant.parse("2026-09-27T09:00:00Z");
    private static final String AT = "2026-09-27 10:00:00";
    // Synthetic values standing in for real identities; the pseudonymizer is taught the hostname the way the
    // fleet warm-up teaches it in production.
    private static final String RAW_HOST = "fw-raw-host-01";
    private static final String RAW_IP = "192.0.2.77";
    private static final String RAW_ID = "dev-raw-id-1";
    private static final String NOHOST_ID = "dev-nohost-id-2";
    private static final String FREE = "failed on " + RAW_HOST + " at " + RAW_IP + " (peer " + RAW_HOST + ")";
    private static final List<String> FORBIDDEN = List.of(RAW_HOST, RAW_IP, RAW_ID, NOHOST_ID);

    private TopologyNamePseudonymizer names;
    private SubnetPreservingIpMasker ips;

    @BeforeEach
    void setUp() {
        names = new TopologyNamePseudonymizer(new byte[32]);
        ips = new SubnetPreservingIpMasker(new byte[32]);
        names.maskDeviceName(RAW_HOST, null, RAW_ID);
    }

    private static Result<Record> rows(DSLContext create, String[] columns, String[]... values) {
        String[][] data = new String[values.length + 1][];
        data[0] = columns;
        System.arraycopy(values, 0, data, 1, values.length);
        return create.fetchFromStringData(data);
    }

    private static DSLContext fixture() {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        return DSL.using(new MockConnection(ctx -> {
            String sql = ctx.sql();
            Result<Record> result;
            if (sql.contains("from notification_state")) {
                result = rows(create, new String[] { "key", "value" },
                        new String[] { "c:" + RAW_ID + ":CTRL-1", "PASS" }, new String[] { "h:" + RAW_ID, "reachable" });
            } else if (sql.contains("from devices where observed_hostname is not null")) {
                result = rows(create, new String[] { "device_id", "observed_hostname" }, new String[] { RAW_ID, RAW_HOST });
            } else if (sql.contains("from audit_log")) {
                result = rows(create, new String[] { "audit_id", "action_id", "row_pk", "occurred_at" },
                        sql.contains("local_login_failure")
                                ? new String[] { "12", "local_login_failure", FREE, AT }
                                : new String[] { "11", "device_register", RAW_ID, AT },
                        sql.contains("local_login_failure") ? new String[] { "13", "session_login_takeover", FREE, AT }
                                : new String[] { "14", "role_binding_create", FREE, AT });
            } else if (sql.contains("configuration_notification n")) {
                result = rows(create, new String[] { "notification_id", "kind", "summary", "created_at", "device_name",
                        "observed_hostname", "device_id" },
                        new String[] { "config-1", "configuration_override_detected", FREE, AT, RAW_HOST, RAW_HOST, RAW_ID },
                        new String[] { "config-2", "configuration_override_detected", FREE, AT, NOHOST_ID, null, NOHOST_ID });
            } else if (sql.contains("from device_crash_alert a")) {
                result = rows(create, new String[] { "alert_id", "device_id", "process", "file_count", "newest_mtime", "job_id",
                        "created_at", "device_name", "observed_hostname" },
                        new String[] { "alert-1", RAW_ID, "fwk", "2", "1790000000", "job-5", AT, RAW_HOST, RAW_HOST },
                        new String[] { "alert-2", NOHOST_ID, "fwk", "1", "1790000000", "job-6", AT, NOHOST_ID, null });
            } else if (sql.contains("from backup_deviation_record d")) {
                result = rows(create, new String[] { "deviation_id", "deviation_class", "summary", "detected_at", "device_name",
                        "observed_hostname", "device_id" },
                        new String[] { "dev-1", "size_anomaly", "grew 26.0x on " + RAW_HOST + " " + RAW_IP, AT, RAW_HOST, RAW_HOST, RAW_ID },
                        new String[] { "dev-2", "fleet_large", "101.9x", AT, NOHOST_ID, null, NOHOST_ID });
            } else if (sql.contains("compliance_evaluation e")) {
                result = rows(create, new String[] { "device_id", "evaluated_at", "result", "device_name", "observed_hostname" },
                        new String[] { RAW_ID, AT,
                                "{\"items\":[{\"controlId\":\"CTRL-1\",\"displayStatus\":\"FAIL\",\"severity\":\"LOW\"}]}",
                                RAW_HOST, RAW_HOST });
            } else if (sql.contains("from jobs j") || sql.contains("row_number() over")) {
                if (sql.contains("row_number() over")) {
                    result = rows(create, new String[] { "job_id", "target_device_id", "state", "terminal_reason", "finished_at",
                            "device_name", "observed_hostname" },
                            new String[] { "job-3", RAW_ID, "FAILED", "connection timed out " + RAW_IP, AT, RAW_HOST, RAW_HOST },
                            new String[] { "job-2", RAW_ID, "FAILED", "transport refused", "2026-09-27 09:59:00", RAW_HOST, RAW_HOST },
                            new String[] { "job-1", RAW_ID, "FAILED", "connection failed", "2026-09-27 09:58:00", RAW_HOST, RAW_HOST });
                } else if (sql.contains("host key mismatch")) {
                    result = rows(create, new String[] { "job_id", "finished_at", "device_name", "observed_hostname",
                            "target_device_id" },
                            new String[] { "job-9", AT, NOHOST_ID, null, NOHOST_ID },
                            new String[] { "job-8", AT, RAW_HOST, RAW_HOST, RAW_ID });
                } else {
                    result = rows(create, new String[] { "job_id", "job_type", "terminal_reason", "finished_at", "device_name",
                            "observed_hostname", "target_device_id" },
                            new String[] { "job-4", sql.contains("partial:%") ? "cp_backup" : "inventory", FREE, AT, RAW_HOST, RAW_HOST, RAW_ID },
                            new String[] { "job-7", "inventory", "x", AT, NOHOST_ID, null, NOHOST_ID });
                }
            } else {
                result = rows(create, new String[] { "empty" });
            }
            return new MockResult[] { new MockResult(result.size(), result) };
        }), SQLDialect.POSTGRES);
    }

    private static List<String> allLines(NotificationIdentity identity) throws Exception {
        List<String> all = new ArrayList<>();
        for (String type : NotificationRoute.TYPES) {
            if (type.equals("security_scan")) continue; // counts and labels only; no device identity by design
            NotificationMailRouter.Batch batch = NotificationMailRouter.collect(fixture(), type, 10, FROM, identity);
            assertFalse(batch.lines().isEmpty(), type);
            all.addAll(batch.lines());
        }
        return all;
    }

    @Test
    void maskedModeEmitsNoHostnameDeviceIdIpOrFreeTextIdentityForAnyType() throws Exception {
        NotificationIdentity identity = NotificationIdentity.masked(names, ips);
        for (String type : NotificationRoute.TYPES) {
            if (type.equals("security_scan")) continue;
            for (String line : NotificationMailRouter.collect(fixture(), type, 10, FROM, identity).lines()) {
                for (String forbidden : FORBIDDEN) {
                    assertFalse(line.contains(forbidden), type + " leaked " + forbidden + ": " + line);
                }
            }
        }
        List<String> all = allLines(identity);
        String joined = String.join("\n", all);
        String devicePseudonym = names.maskDeviceName(RAW_HOST, null, RAW_ID);
        assertTrue(devicePseudonym.startsWith("FW-"), devicePseudonym);
        assertTrue(joined.contains(devicePseudonym), "the device appears as the same pseudonym the UI shows");
        assertTrue(joined.contains(names.maskManagedDeviceName(NOHOST_ID)),
                "a device with no observed hostname appears as a derived pseudonym");
        assertTrue(joined.contains(ips.mask(RAW_IP)), "free-text IPv4 addresses are masked, not dropped");
    }

    @Test
    void maskedModeKeepsEventStructureAndStateAdvance() throws Exception {
        NotificationIdentity identity = NotificationIdentity.masked(names, ips);
        NotificationMailRouter.Batch health = NotificationMailRouter.collect(fixture(), "device_health", 10, FROM, identity);
        assertEquals("unreachable", health.state().get("h:" + RAW_ID), "state keys keep the opaque device id");
        assertTrue(health.lines().stream().anyMatch(l -> l.contains("unreachable after three transport failures")));
        assertTrue(health.lines().stream().anyMatch(l -> l.contains("SSH host key mismatch")));
        assertEquals("FAIL", NotificationMailRouter.collect(fixture(), "compliance_regression", 10, FROM, identity)
                .state().get("c:" + RAW_ID + ":CTRL-1"));
        assertEquals(14, NotificationMailRouter.collect(fixture(), "admin_event", 10, FROM, identity).audit());
    }

    @Test
    void rawModeKeepsTodaysOutput() throws Exception {
        String joined = String.join("\n", allLines(NotificationIdentity.raw()));
        assertTrue(joined.contains(RAW_HOST));
        assertTrue(joined.contains(RAW_IP));
        assertTrue(joined.contains(NOHOST_ID));
        assertTrue(joined.contains("device_register  " + RAW_ID));
    }

    @Test
    void identityFollowsTheSetting() {
        NotificationSettings masked = new NotificationSettings(false, null, 514, "udp", 16, false, null, 25, false,
                null, null, false, false, null);
        assertEquals(NotificationSettings.IDENTITY_MASKED, masked.notificationIdentity());
        assertFalse(NotificationIdentity.of(masked, names, ips).isRaw());
        assertTrue(NotificationIdentity.of(masked.withNotificationIdentity("raw"), names, ips).isRaw());
    }

    @Test
    void unavailablePseudonymizerSendsFixedLabelsNeverRawValues() throws Exception {
        for (NotificationIdentity identity : List.of(NotificationIdentity.masked(null, null),
                NotificationIdentity.masked(names, null), NotificationIdentity.masked(null, ips),
                NotificationIdentity.masked(throwing(), ips))) {
            String joined = String.join("\n", allLines(identity));
            for (String forbidden : FORBIDDEN) {
                assertFalse(joined.contains(forbidden), "leaked " + forbidden + " with " + identity);
            }
            assertTrue(joined.contains(NotificationIdentity.DEVICE_UNAVAILABLE)
                    || joined.contains(NotificationIdentity.TEXT_UNAVAILABLE), joined);
        }
    }

    @Test
    void failedFleetWarmUpWithholdsFreeText() throws Exception {
        DSLContext broken = DSL.using(new MockConnection(ctx -> { throw new java.sql.SQLException("synthetic"); }),
                SQLDialect.POSTGRES);
        NotificationIdentity identity = NotificationIdentity.masked(names, ips);
        identity.warm(broken);
        assertEquals(NotificationIdentity.TEXT_UNAVAILABLE, identity.text(FREE));
    }

    @Test
    void fleetWarmUpTeachesTheTextMaskerEveryKnownHostname() {
        TopologyNamePseudonymizer fresh = new TopologyNamePseudonymizer(new byte[32]);
        NotificationIdentity identity = NotificationIdentity.masked(fresh, ips);
        assertTrue(identity.text(FREE).contains(RAW_HOST), "unknown to a fresh process before the warm-up");
        identity.warm(fixture());
        assertFalse(identity.text(FREE).contains(RAW_HOST));
        assertFalse(identity.text(FREE).contains(RAW_IP));
    }

    @Test
    void tickDigestsAreMaskedEndToEnd() {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        List<String> bodies = new ArrayList<>();
        DSLContext inner = fixture();
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            if (ctx.sql().contains("from notification_routes order by type")) {
                Result<Record> r = rows(create, new String[] { "type", "enabled", "recipients", "watermark_audit", "watermark_time" },
                        new String[] { "job_failure", "true", "admin@example.test", "10", AT });
                return new MockResult[] { new MockResult(r.size(), r) };
            }
            Result<Record> r = inner.fetch(ctx.sql(), ctx.bindings());
            return new MockResult[] { new MockResult(r.size(), r) };
        }), SQLDialect.POSTGRES);
        NotificationSettings settings = new NotificationSettings(false, null, 514, "udp", 16, true, "relay.example.test", 25,
                false, "nexus@example.test", "admin@example.test", false, false, null);
        NotificationMailRouter.tick(dsl, settings, NotificationIdentity.of(settings, new TopologyNamePseudonymizer(new byte[32]), ips),
                (s, recipients, subject, body) -> bodies.add(body));
        assertEquals(1, bodies.size());
        for (String forbidden : FORBIDDEN) assertFalse(bodies.getFirst().contains(forbidden), forbidden);
    }

    private static TopologyNamePseudonymizer throwing() {
        return new TopologyNamePseudonymizer(new byte[32]) {
            @Override public String maskDeviceName(String raw, String ref, String id) { throw new IllegalStateException("synthetic"); }
            @Override public String maskManagedDeviceName(String raw) { throw new IllegalStateException("synthetic"); }
            @Override public String maskText(String text) { throw new IllegalStateException("synthetic"); }
        };
    }
}
