package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;

/**
 * V160 (system-log phase B, DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §15 item 1): the 13 approved collection gate rows with
 * their exact command text, the module mapping, the system_log artefact class, the device_crash_file notification type,
 * the tables of §5.2 with their constraints, the audit classification and the settings defaults. Synthetic values only.
 */
class SystemLogPhaseBSchemaTest {
    /** The 13 approved command texts, from DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.1 and §10.2, by gate id. */
    private static final Map<String, String> COMMANDS = Map.ofEntries(
            Map.entry("cp_syslog_collect_stat", "bash -lc 'date +%s; stat -c \"%n %i %s %Y\" /var/log/messages* $FWDIR/log/fwd.elg* $CPDIR/log/cpd.elg* $CPDIR/log/cpwd.elg* $FWDIR/log/fwk.elg* $FWDIR/log/mq_mng.elg* /var/log/spike_detective/spike_detective.log* /var/log/spike_detective/spike_detective.csv*'"),
            Map.entry("cp_syslog_collect_read", "no shell command. Read-only SFTP open of `<path>`, read `[<pos>, <pos> + <cap>)`, close. `<pos>`, `<cap>` non-negative integers computed by neXus, `<cap> ≤ 8388672`; `<path>` = an absolute path printed by C1 in the same run, basename in the source's family pattern, directory equal to the family base directory"),
            Map.entry("cp_syslog_collect_ls_crash", "bash -lc 'ls -lgo --time-style=+%s /var/log/crash/ /var/log/dump/usermode/'"),
            Map.entry("cp_syslog_collect_ls_spike", "bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/'"),
            Map.entry("cp_syslog_collect_ls_spike_dir", "bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/<dir>/'"),
            Map.entry("cp_syslog_collect_ls_sa", "bash -lc 'ls -lgo --time-style=+%s /var/log/sa/'"),
            Map.entry("cp_syslog_collect_ls_hcp", "bash -lc 'ls -lgo --time-style=+%s /var/log/hcp/last/'"),
            Map.entry("cp_syslog_collect_fetch", "fetch one absolute path printed by C4/C5 (spike data), C6 (`sar<DD>`) or C7 (HCP archive) in the same run; no other path"),
            Map.entry("pan_syslog_collect_system_seed", "type=log&log-type=system&nlogs=<n>"),
            Map.entry("pan_syslog_collect_config_seed", "type=log&log-type=config&nlogs=<n>"),
            Map.entry("pan_syslog_collect_system_enqueue", "type=log&log-type=system&nlogs=<n>&dir=forward&query=(receive_time geq '<ts>')"),
            Map.entry("pan_syslog_collect_config_enqueue", "type=log&log-type=config&nlogs=<n>&dir=forward&query=(receive_time geq '<ts>')"),
            Map.entry("pan_syslog_collect_job_get", "type=log&action=get&job-id=<id>")
);

    /** Rows whose command text equals an approved measurement row (V151, V157): the key is narrowed by shell_context. */
    private static final Map<String, String> NARROWED_SHELL = Map.of(
            "cp_syslog_collect_ls_crash", "expert_collect", "cp_syslog_collect_ls_spike", "expert_collect",
            "cp_syslog_collect_ls_sa", "expert_collect", "cp_syslog_collect_ls_hcp", "expert_collect",
            "pan_syslog_collect_job_get", "not_applicable_collect");

    private static final String HEX = "a".repeat(64);
    private static final String HEX2 = "b".repeat(64);
    private static final Map<String, Set<String>> COLUMNS = Map.of(
            "system_log_cursor", Set.of("device_id", "source_key", "cursor_key", "inode", "offset_bytes", "anchor_hmac",
                    "family_snapshot", "pan_last_receive_time", "pan_boundary_seqnos", "device_epoch_at_read",
                    "fast_rotating", "retention_horizon_seconds", "updated_job_id", "updated_at"),
            "system_log_chunk", Set.of("chunk_id", "device_id", "source_key", "job_id", "collected_at", "log_day",
                    "file_name_hmac", "inode", "start_offset", "end_offset", "bytes", "lines", "first_line_time",
                    "last_line_time", "line_time_basis", "redaction_count", "flags", "artefact_ref", "wrapped_data_key",
                    "plaintext_hmac", "ciphertext_bytes", "pruned_at", "prune_reason", "redaction_rule_set"),
            "system_log_gap", Set.of("gap_id", "device_id", "source_key", "job_id", "kind", "known_lost_bytes",
                    "pending_bytes", "extent_unknown", "window_start_epoch", "window_end_epoch", "detail", "created_at"),
            "system_log_file", Set.of("file_id", "device_id", "kind", "job_id", "remote_name_hmac", "name_time", "size",
                    "mtime", "status", "flags", "artefact_ref", "wrapped_data_key", "content_hmac", "ciphertext_bytes",
                    "collected_at", "expires_at", "pruned_at"),
            "device_crash_entry", Set.of("entry_id", "device_id", "directory", "name_hmac", "process", "size", "mtime",
                    "baseline", "first_seen_job", "last_seen_job", "first_seen_at", "removed_at"),
            "device_crash_alert", Set.of("alert_id", "device_id", "process", "file_count", "newest_mtime", "job_id",
                    "created_at", "notified_at"),
            "system_log_run", Set.of("run_id", "device_id", "job_id", "source_key", "outcome", "bytes_read",
                    "bytes_stored", "reads_issued", "duration_ms", "detail", "created_at"),
            "system_log_settings", Set.of("settings_id", "raw_retention_days", "device_cap_bytes", "hcp_retention_days",
                    "spike_data_retention_days", "spike_data_cap_bytes", "ledger_retention_days",
                    "include_management_servers", "spike_data_enabled", "updated_at", "updated_by"));

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("system_log_phase_b");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) fixture.close();
    }

    @Test
    void thirteenApprovedGateRowsMatchTheFixtureAndTheContractTextAndStayUnambiguous() throws Exception {
        var fixtureRows = new java.util.HashMap<String, GateRow>();
        try (var stream = getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml")) {
            GateRegistryFixtureLoader.loadFromStream(stream).stream()
                    .filter(row -> row.gateId().contains("_syslog_collect_"))
                    .forEach(row -> fixtureRows.put(row.gateId(), row));
        }
        assertEquals(COMMANDS.keySet(), fixtureRows.keySet());
        assertEquals(13, fixtureRows.size());
        try (var db = fixture.migrateConnection()) {
            assertEquals("13", scalar(db, "select count(*) from gate_registry where gate_id like '%\\_syslog\\_collect\\_%' "
                    + "and sign_off_state='SIGNED_OFF' and action_class='read' and retry_rule='none'"));
            assertEquals("8", scalar(db, "select count(*) from gate_registry where gate_id like 'cp\\_syslog\\_collect\\_%' "
                    + "and vendor='check_point' and platform_role_scope='cp_gaia_gateway'"));
            assertEquals("5", scalar(db, "select count(*) from gate_registry where gate_id like 'pan\\_syslog\\_collect\\_%' "
                    + "and vendor='palo_alto' and platform_role_scope='pan_firewall' and transport_kind='PAN_XML_API'"));
            assertEquals("6", scalar(db, "select count(*) from gate_registry where gate_id like 'cp\\_syslog\\_collect\\_%' "
                    + "and transport_kind='SSH_EXEC'"));
            assertEquals("2", scalar(db, "select count(*) from gate_registry where gate_id like 'cp\\_syslog\\_collect\\_%' "
                    + "and transport_kind='SFTP_GET' and shell_context='not_applicable'"));
            for (var entry : COMMANDS.entrySet()) {
                String id = entry.getKey();
                GateRow row = fixtureRows.get(id);
                assertEquals(entry.getValue(), row.canonicalCommandKey(), id + " fixture command text");
                assertEquals(entry.getValue(), scalar(db, "select canonical_command_key from gate_registry where gate_id='" + id + "'"),
                        id + " migration command text");
                assertEquals(NARROWED_SHELL.getOrDefault(id, row.shellContext()), row.shellContext(), id + " shell_context");
                for (String[] column : new String[][] {
                        {"shell_context", row.shellContext()}, {"transport_kind", row.transportKind()},
                        {"timeout_s", String.valueOf(row.timeoutS())}, {"retry_rule", row.retryRule()},
                        {"max_frequency", row.maxFrequency()}, {"session_reuse_rule", row.sessionReuseRule()},
                        {"unsupported_behavior_ref", row.unsupportedBehaviorRef()}, {"secret_output_risk", row.secretOutputRisk()},
                        {"source_document_pointer", row.sourceDocumentPointer()}})
                    assertEquals(column[1], scalar(db, "select " + column[0] + "::text from gate_registry where gate_id='" + id + "'"),
                            id + "." + column[0]);
                assertEquals(String.join(",", row.safeTelemetryFields()), scalar(db,
                        "select string_agg(v, ',' order by o) from gate_registry, jsonb_array_elements_text(safe_telemetry_fields) "
                                + "with ordinality as t(v, o) where gate_id='" + id + "'"), id + " telemetry");
            }
            // V173 (PO 2026-10-21, option A): only the C4 listing bound moved, 256 KiB to 1 MiB; C3, C5 and the rest keep theirs.
            assertEquals("once per device per job window; max bytes: 1 MiB", scalar(db,
                    "select max_frequency from gate_registry where gate_id='cp_syslog_collect_ls_spike'"));
            assertEquals("once per device per job window; max bytes: 256 KiB", scalar(db,
                    "select max_frequency from gate_registry where gate_id='cp_syslog_collect_ls_crash'"));
            assertEquals("1", scalar(db, "select count(*) from gate_registry where max_frequency like '%max bytes: 64 KiB%' and "
                    + "gate_id='cp_syslog_collect_ls_spike_dir'"));
            assertEquals("30|none|SIGNED_OFF", scalar(db, "select timeout_s || '|' || retry_rule || '|' || sign_off_state "
                    + "from gate_registry where gate_id='cp_syslog_collect_ls_spike'"));
            // The five commands that equal an approved measurement row stay byte-identical; only the key is narrowed.
            assertEquals("0", scalar(db, "select count(*) from (select 1 from gate_registry group by vendor, platform_role_scope, "
                    + "shell_context, transport_kind, canonical_command_key having count(*) > 1) d"),
                    "two gate rows share one canonical key (C4 section 3.3 step 6)");
            assertEquals("5", scalar(db, "select count(*) from gate_registry c join gate_registry m "
                    + "on m.canonical_command_key = c.canonical_command_key and m.gate_id like '%\\_syslog\\_measure\\_%' "
                    + "where c.gate_id like '%\\_syslog\\_collect\\_%'"));
            assertEquals("18", scalar(db, "select count(*) from gate_registry where gate_id like '%\\_syslog\\_measure\\_%' "
                    + "and sign_off_state='SIGNED_OFF'"), "the 18 measurement rows are untouched");
        }
    }

    @Test
    void theSpikeListingBoundMigrationIsRepeatableAndTouchesOnlyTheC4Row() throws Exception {
        try (var resource = getClass().getResourceAsStream("/db/migration/V173__spike_listing_bound_one_mebibyte.sql")) {
            assertNotNull(resource);
            String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                db.setAutoCommit(false);
                String before = scalar(db, "select string_agg(gate_id || '=' || max_frequency || '=' || source_document_pointer, ';' "
                        + "order by gate_id) from gate_registry where gate_id like '%\\_syslog\\_collect\\_%'");
                statement.execute(sql);
                statement.execute(sql);
                assertEquals(before, scalar(db, "select string_agg(gate_id || '=' || max_frequency || '=' || source_document_pointer, ';' "
                        + "order by gate_id) from gate_registry where gate_id like '%\\_syslog\\_collect\\_%'"),
                        "already applied: re-running changes nothing, and the pointer is not appended twice");
                assertEquals("1", scalar(db, "select count(*) from gate_registry where gate_id='cp_syslog_collect_ls_spike' "
                        + "and source_document_pointer like '%PO_DECISION_RECORD_2026_10_21_SPIKE_LISTING_BOUND.md%'"));
                db.rollback();
            }
        }
    }

    @Test
    void migrationIsRepeatableMapsTheModulesAndAdmitsTheSystemLogArtefactClass() throws Exception {
        try (var resource = getClass().getResourceAsStream("/db/migration/V160__system_log_phase_b.sql")) {
            assertNotNull(resource);
            String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                db.setAutoCommit(false);
                statement.execute(sql);
                statement.execute(sql);
                assertEquals("13", scalar(db, "select count(*) from gate_registry where gate_id like '%\\_syslog\\_collect\\_%'"));
                db.rollback();
            }
        }
        try (var db = fixture.migrateConnection()) {
            assertEquals("diagnostics", scalar(db, "select ui2_job_module('cp_system_log_collect')"));
            assertEquals("diagnostics", scalar(db, "select ui2_job_module('pan_system_log_collect')"));
            // V154's mapping survives byte-for-byte.
            assertEquals("diagnostics", scalar(db, "select ui2_job_module('pan_syslog_measure')"));
            assertEquals("diagnostics", scalar(db, "select ui2_job_module('diagnostic_read')"));
            assertEquals("packet_analysis", scalar(db, "select ui2_job_module('packet_analysis_run')"));
            assertEquals("policy", scalar(db, "select ui2_job_module('cp_policy_collect')"));
            assertEquals("backup", scalar(db, "select ui2_job_module('cp_gateway_backup')"));
            assertNull(scalar(db, "select ui2_job_module('cp_system_log_collect_typo')"));
        }
        assertEquals("system_log", ArtefactClass.SYSTEM_LOG);
        audited(artefactInsert("pb-artefact-1", ArtefactClass.SYSTEM_LOG));
        audited(artefactInsert("pb-artefact-2", ArtefactClass.BACKUP));
        assertThrows(SQLException.class, () -> audited(artefactInsert("pb-artefact-3", "made_up")));
        try (var db = fixture.migrateConnection()) {
            assertEquals("1", scalar(db, "select count(*) from backup_artefact where artefact_class='system_log'"));
        }
    }

    @Test
    void tablesHaveExactlyTheContractedColumns() throws Exception {
        try (var db = fixture.migrateConnection()) {
            for (var entry : COLUMNS.entrySet()) {
                Set<String> live = new TreeSet<>();
                try (var statement = db.createStatement(); var result = statement.executeQuery(
                        "select column_name from information_schema.columns where table_schema='public' and table_name='"
                                + entry.getKey() + "'")) {
                    while (result.next()) live.add(result.getString(1));
                }
                assertEquals(new TreeSet<>(entry.getValue()), live, entry.getKey());
                assertEquals("1", scalar(db, "select count(*) from information_schema.triggers where trigger_name='trg_audit_"
                        + entry.getKey() + "' and event_object_table='" + entry.getKey() + "' and event_manipulation='INSERT'"),
                        entry.getKey() + " audit trigger");
            }
        }
    }

    @Test
    void constraintsAcceptTheContractShapesAndRefuseEverythingElse() throws Exception {
        String device = "dev-pb-constraints";
        // Cursor: incremental sources only; inode is digits; anchor is a lowercase hex digest; PAN time is verbatim.
        audited("insert into system_log_cursor(device_id, source_key, inode, offset_bytes, anchor_hmac, family_snapshot, "
                + "device_epoch_at_read, updated_job_id) values ('" + device + "', 'cp_messages', '184467440737095516', 4096, '"
                + HEX + "', '[{\"inode\":\"1\"}]'::jsonb, 1790000000, 'job-1')");
        audited("insert into system_log_cursor(device_id, source_key, pan_last_receive_time, pan_boundary_seqnos, updated_job_id) "
                + "values ('" + device + "', 'pan_system', '2026/10/17 10:00:00', '[\"7\"]'::jsonb, 'job-1')");
        refused("insert into system_log_cursor(device_id, source_key, updated_job_id) values ('" + device + "', 'cp_sar_daily', 'j')");
        refused("insert into system_log_cursor(device_id, source_key, updated_job_id) values ('" + device + "', 'cp_messages', 'j')");
        refused("insert into system_log_cursor(device_id, source_key, inode, updated_job_id) values ('d2', 'cp_messages', 'abc', 'j')");
        refused("insert into system_log_cursor(device_id, source_key, offset_bytes, updated_job_id) values ('d2', 'cp_messages', -1, 'j')");
        refused("insert into system_log_cursor(device_id, source_key, anchor_hmac, updated_job_id) values ('d2', 'cp_messages', 'XYZ', 'j')");
        refused("insert into system_log_cursor(device_id, source_key, pan_last_receive_time, updated_job_id) "
                + "values ('d2', 'pan_system', '2026-10-17', 'j')");
        refused("insert into system_log_cursor(device_id, source_key, family_snapshot, updated_job_id) "
                + "values ('d2', 'cp_cpd_elg', '{}'::jsonb, 'j')");

        // Chunk: unique (device, source, inode, start_offset); one PAN chunk per run; flags and prune state are closed.
        String chunk = "insert into system_log_chunk(chunk_id, device_id, source_key, job_id, log_day, file_name_hmac, inode, "
                + "start_offset, end_offset, bytes, lines, flags, artefact_ref, wrapped_data_key, plaintext_hmac, ciphertext_bytes";
        audited(chunk + ") values ('c1', '" + device + "', 'cp_messages', 'job-1', date '2026-10-17', '" + HEX
                + "', '77', 0, 100, 100, 3, array['LINE_SPLIT'], 'pb-artefact-1', convert_to('synthetic-wrapped-key-marker', 'UTF8'), '"
                + HEX + "', 10)");
        refused(chunk + ") values ('c2', '" + device + "', 'cp_messages', 'job-2', date '2026-10-17', '" + HEX
                + "', '77', 0, 50, 50, 1, '{}', 'pb-artefact-1', convert_to('x', 'UTF8'), '" + HEX + "', 1)");
        audited(chunk + ") values ('c3', '" + device + "', 'pan_system', 'job-1', date '2026-10-17', '" + HEX
                + "', null, 0, 10, 10, 1, '{}', 'pb-artefact-1', convert_to('x', 'UTF8'), '" + HEX + "', 1)");
        refused(chunk + ") values ('c4', '" + device + "', 'pan_system', 'job-1', date '2026-10-17', '" + HEX
                + "', null, 0, 10, 10, 1, '{}', 'pb-artefact-1', convert_to('x', 'UTF8'), '" + HEX + "', 1)");
        audited(chunk + ") values ('c5', '" + device + "', 'pan_system', 'job-2', date '2026-10-17', '" + HEX
                + "', null, 0, 10, 10, 1, '{}', 'pb-artefact-1', convert_to('x', 'UTF8'), '" + HEX + "', 1)");
        refused(chunk + ") values ('c6', '" + device + "', 'cp_fwd_elg', 'job-1', date '2026-10-17', '" + HEX
                + "', '5', 10, 5, 0, 0, '{}', 'a', convert_to('x', 'UTF8'), '" + HEX + "', 1)");
        refused(chunk + ") values ('c7', '" + device + "', 'cp_fwd_elg', 'job-1', date '2026-10-17', '" + HEX
                + "', '5', 0, 5, 5, 1, array['BOGUS'], 'a', convert_to('x', 'UTF8'), '" + HEX + "', 1)");
        refused(chunk + ") values ('c8', '" + device + "', 'cp_sar_daily', 'job-1', date '2026-10-17', '" + HEX
                + "', '5', 0, 5, 5, 1, '{}', 'a', convert_to('x', 'UTF8'), '" + HEX + "', 1)");
        refused(chunk + ") values ('c9', '" + device + "', 'cp_fwd_elg', 'job-1', date '2026-10-17', 'not-a-digest', "
                + "'5', 0, 5, 5, 1, '{}', 'a', convert_to('x', 'UTF8'), '" + HEX + "', 1)");
        refused("update system_log_chunk set pruned_at = now() where chunk_id = 'c1'");
        audited("update system_log_chunk set pruned_at = now(), prune_reason = 'RETENTION_SHORTENED_BY_CAP' where chunk_id = 'c1'");
        refused("update system_log_chunk set prune_reason = 'OTHER', pruned_at = now() where chunk_id = 'c1'");

        // Gap: closed kinds, no file names or paths in the detail, non-negative counts.
        audited("insert into system_log_gap(gap_id, device_id, source_key, job_id, kind, known_lost_bytes, pending_bytes, "
                + "extent_unknown, window_start_epoch, window_end_epoch, detail) values ('g1', '" + device + "', 'cp_messages', 'job-1', "
                + "'ROTATED_AWAY', 100, 0, true, 1790000000, 1790021600, '{\"suffixes\":[\".1\"],\"inodes\":[\"77\"]}'::jsonb)");
        refused("insert into system_log_gap(gap_id, device_id, source_key, job_id, kind) values ('g2', 'd', 'cp_messages', 'j', 'MADE_UP')");
        refused("insert into system_log_gap(gap_id, device_id, source_key, job_id, kind, detail) values ('g3', 'd', 'cp_messages', 'j', "
                + "'BACKLOG', '{\"path\":\"/var/log/messages\"}'::jsonb)");
        refused("insert into system_log_gap(gap_id, device_id, source_key, job_id, kind, detail) values ('g4', 'd', 'cp_messages', 'j', "
                + "'BACKLOG', '[]'::jsonb)");
        refused("insert into system_log_gap(gap_id, device_id, source_key, job_id, kind, known_lost_bytes) values ('g5', 'd', "
                + "'cp_messages', 'j', 'ROTATED_AWAY', -1)");
        refused("insert into system_log_gap(gap_id, device_id, source_key, job_id, kind, window_start_epoch, window_end_epoch) "
                + "values ('g6', 'd', 'cp_messages', 'j', 'BACKLOG', 20, 10)");

        // Whole-file fetches: three kinds, unique by remote name digest and mtime.
        String file = "insert into system_log_file(file_id, device_id, kind, job_id, remote_name_hmac, name_time, size, mtime, flags, "
                + "artefact_ref, wrapped_data_key, content_hmac, ciphertext_bytes, expires_at";
        audited(file + ") values ('f1', '" + device + "', 'cp_hcp_report', 'job-1', '" + HEX + "', timestamp '2026-10-17 03:00:00', "
                + "4000000, 1790000000, array['NAME_SHAPE_UNEXPECTED'], 'pb-artefact-1', convert_to('x', 'UTF8'), '" + HEX
                + "', 3900000, now() + interval '7 days')");
        refused(file + ") values ('f2', '" + device + "', 'cp_hcp_report', 'job-2', '" + HEX + "', null, 4000000, 1790000000, '{}', "
                + "'a', convert_to('x', 'UTF8'), '" + HEX + "', 1, now())");
        refused(file + ") values ('f3', '" + device + "', 'cp_messages', 'job-1', '" + HEX + "', null, 1, 1, '{}', "
                + "'a', convert_to('x', 'UTF8'), '" + HEX + "', 1, now())");
        refused("update system_log_file set status = 'GONE' where file_id = 'f1'");
        audited("update system_log_file set status = 'CORRUPT' where file_id = 'f1'");

        // Crash and dump listing state and alerts.
        String entry = "insert into device_crash_entry(entry_id, device_id, directory, name_hmac, process, size, mtime, baseline, "
                + "first_seen_job, last_seen_job";
        audited(entry + ") values ('e1', '" + device + "', 'dump_usermode', '" + HEX + "', 'fwk', 4096, 1790000000, true, 'job-1', 'job-1')");
        refused(entry + ") values ('e2', '" + device + "', 'dump_usermode', '" + HEX + "', 'fwk', 4096, 1790000000, false, 'j', 'j')");
        refused(entry + ") values ('e3', '" + device + "', 'elsewhere', '" + HEX + "', 'fwk', 1, 1, false, 'j', 'j')");
        refused(entry + ") values ('e4', '" + device + "', 'crash', '" + HEX + "', 'not a process', 1, 1, false, 'j', 'j')");
        audited(entry + ") values ('e5', '" + device + "', 'crash', '" + HEX + "', 'kernel', 0, 1790000000, false, 'job-1', 'job-1')");
        String alert = "insert into device_crash_alert(alert_id, device_id, process, file_count, newest_mtime, job_id";
        audited(alert + ") values ('a1', '" + device + "', 'fwk', 2, 1790000000, 'job-1')");
        refused(alert + ") values ('a2', '" + device + "', 'fwk', 1, 1790000000, 'job-1')");
        refused(alert + ") values ('a3', '" + device + "', 'cpd', 0, 1790000000, 'job-1')");

        // Run outcomes: exactly one per job and source; the outcome vocabulary is closed (contract §9).
        String run = "insert into system_log_run(run_id, device_id, job_id, source_key, outcome, bytes_read, bytes_stored, reads_issued, "
                + "duration_ms";
        audited(run + ") values ('r1', '" + device + "', 'job-1', 'cp_messages', 'COLLECTED', 100, 100, 2, 1500)");
        audited(run + ") values ('r2', '" + device + "', 'job-1', 'cp_crash_listing', 'NO_NEW_DATA', 0, 0, 0, 100)");
        refused(run + ") values ('r3', '" + device + "', 'job-1', 'cp_messages', 'UNKNOWN', 0, 0, 0, 0)");
        refused(run + ") values ('r4', '" + device + "', 'job-2', 'cp_messages', 'DONE', 0, 0, 0, 0)");
        refused(run + ") values ('r5', '" + device + "', 'job-2', 'cp_everything', 'UNKNOWN', 0, 0, 0, 0)");
        for (String outcome : List.of("COLLECTED", "NO_NEW_DATA", "BACKLOG", "GAP", "ABSENT", "UNSUPPORTED", "TOO_LARGE",
                "STORAGE_REFUSED", "UNKNOWN"))
            audited(run + ") values ('ro-" + outcome + "', '" + device + "', 'job-outcomes', 'pan_config', '" + outcome
                    + "', 0, 0, 0, 0)", "delete from system_log_run where run_id = 'ro-" + outcome + "'");
    }

    @Test
    void auditKeepsKeysDigestsAndNamesOutOfTheAuditLog() throws Exception {
        String marker = "synthetic-wrapped-key-marker-v160";
        audited("insert into system_log_cursor(device_id, source_key, anchor_hmac, family_snapshot, updated_job_id) values "
                + "('dev-pb-audit', 'cp_cpwd_elg', '" + HEX + "', '[{\"name\":\"synthetic-file-marker\"}]'::jsonb, 'job-9')");
        audited("insert into system_log_chunk(chunk_id, device_id, source_key, job_id, log_day, file_name_hmac, inode, start_offset, "
                + "end_offset, bytes, lines, artefact_ref, wrapped_data_key, plaintext_hmac, ciphertext_bytes) values "
                + "('c-audit', 'dev-pb-audit', 'cp_cpwd_elg', 'job-9', date '2026-10-17', '" + HEX + "', '9', 0, 5, 5, 1, "
                + "'synthetic-artefact-location', convert_to('" + marker + "', 'UTF8'), '" + HEX2 + "', 5)");
        try (var db = fixture.migrateConnection()) {
            assertEquals("system_log_cursor", scalar(db, "select table_name from audit_log where row_pk='dev-pb-audit/cp_cpwd_elg'"));
            String after = scalar(db, "select after_state::text from audit_log where table_name='system_log_chunk' and row_pk='c-audit'");
            assertNotNull(after);
            // V161: the keyed content identity (plaintext_hmac) is redacted like the anchors and name digests.
            for (String leaked : List.of(marker, "synthetic-artefact-location", HEX, HEX2))
                assertFalse(after.contains(leaked), "a redacted value reached audit_log");
            assertTrue(after.contains("sha256:"));
            String cursor = scalar(db, "select after_state::text from audit_log where table_name='system_log_cursor' "
                    + "and row_pk='dev-pb-audit/cp_cpwd_elg'");
            assertFalse(cursor.contains("synthetic-file-marker"));
            assertFalse(cursor.contains(HEX));
        }
    }

    @Test
    void settingsDefaultsAndTheCrashNotificationTypeAreSeeded() throws Exception {
        try (var db = fixture.migrateConnection()) {
            assertEquals("1", scalar(db, "select count(*) from system_log_settings"));
            assertEquals("30|2147483648|7|30|536870912|180|false|false", scalar(db,
                    "select raw_retention_days || '|' || device_cap_bytes || '|' || hcp_retention_days || '|' "
                            + "|| spike_data_retention_days || '|' || spike_data_cap_bytes || '|' || ledger_retention_days || '|' "
                            + "|| include_management_servers || '|' || spike_data_enabled from system_log_settings where settings_id='default'"));
            assertEquals("1", scalar(db, "select count(*) from notification_routes where type='device_crash_file' and enabled=false"));
            assertEquals("9", scalar(db, "select count(*) from notification_routes"));
            assertEquals("1", scalar(db, "select count(*) from audit_log where table_name='system_log_settings' and row_pk='default' "
                    + "and operation='INSERT'"));
        }
        refused("insert into system_log_settings(settings_id) values ('other')");
        refused("update system_log_settings set raw_retention_days = 0");
        refused("update system_log_settings set device_cap_bytes = 1024");
        refused("update system_log_settings set hcp_retention_days = 100000");
        audited("update system_log_settings set include_management_servers = true, updated_by = 'harness-actor-fingerprint'",
                "update system_log_settings set include_management_servers = false");
        refused("insert into notification_routes(type, enabled, updated_by) values ('made_up_type', false, 'harness')");
    }

    private static String artefactInsert(String id, String artefactClass) {
        return "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, hostname_fingerprint, plaintext_sha256, "
                + "plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, wrapped_data_key, validation, retention_tier, "
                + "recovery_volume_path) values ('" + id + "', 'dev-pb-artefact', '" + artefactClass + "', 'check_point', '" + HEX
                + "', '" + HEX + "', 10, '" + HEX + "', 12, 'key-1', convert_to('x', 'UTF8'), '{}'::jsonb, 'standard', "
                + "'synthetic/location/" + id + "')";
    }

    private static String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    /** Runs statements as one audited transaction; an optional trailing cleanup statement runs in the same transaction. */
    private static void audited(String... statements) throws SQLException {
        try (var db = fixture.migrateConnection()) {
            db.setAutoCommit(false);
            try {
                Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
                try (var statement = db.createStatement()) {
                    for (String sql : statements) statement.execute(sql);
                }
                db.commit();
            } catch (SQLException failure) {
                db.rollback();
                throw failure;
            }
        }
    }

    private static void refused(String sql) {
        assertThrows(SQLException.class, () -> audited(sql), sql);
    }
}
