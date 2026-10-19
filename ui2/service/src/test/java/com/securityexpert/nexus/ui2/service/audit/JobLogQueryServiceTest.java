package com.securityexpert.nexus.ui2.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobEvent;
import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobQuery;
import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.Stats;
import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.Where;

class JobLogQueryServiceTest {

    @Test
    void anEmptyQueryHasNoWhereClause() {
        Where where = JobLogQueryService.whereOf(new JobQuery(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 1, 50));

        assertEquals("", where.sql());
        assertTrue(where.bindings().isEmpty());
    }

    @Test
    void everyFilterBindsAParameterNeverInlinesIt() {
        Instant since = Instant.parse("2026-09-22T11:00:00Z");
        Where where = JobLogQueryService.whereOf(new JobQuery(Optional.of("failed"), Optional.of("cp_gateway_backup"),
                Optional.of("dev-1"), Optional.of(since), Optional.empty(), Optional.of("SFTP 100%"), 3, 25));

        assertEquals(" where j.state = ? and (j.job_type = ? or j.job_type like ?) and j.target_device_id = ? and j.submitted_at >= ? and "
                + "(lower(j.job_id) like ? or lower(j.target_device_id) like ? or lower(coalesce(d.observed_hostname, '')) like ? "
                + "or lower(coalesce(j.terminal_reason, '')) like ?)",
                where.sql());
        assertEquals(List.of("FAILED", "cp_gateway_backup", "%\\_cp\\_gateway\\_backup", "dev-1", Timestamp.from(since), "%sftp 100\\%%", "%sftp 100\\%%",
                "%sftp 100\\%%", "%sftp 100\\%%"), where.bindings());
    }

    @Test
    void aCommaSeparatedStateListBecomesAnInClause() {
        Where where = JobLogQueryService.whereOf(new JobQuery(Optional.of("requested, claimed"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 1, 50));

        assertEquals(" where j.state in (?, ?)", where.sql());
        assertEquals(List.of("REQUESTED", "CLAIMED"), where.bindings());
    }

    @Test
    void pageBoundsAreClamped() {
        JobQuery query = new JobQuery(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), 0, 10_000);

        assertEquals(1, query.page());
        assertEquals(JobLogQueryService.MAX_PAGE_SIZE, query.pageSize());
    }

    @Test
    void csvQuotesCommasAndNeutralisesSpreadsheetFormulas() {
        String csv = JobLogQueryService.toCsv(List.of(
                new JobEvent("job-1", "cp_gateway_backup", "dev-1", "FW-TANGO-01", "FAILED", "FAILURE",
                        "sftp fetch failed: SftpException id=4, \"no message\"",
                        Instant.parse("2026-09-22T11:43:23Z"), Instant.parse("2026-09-22T11:44:46Z"), 83_000L),
                new JobEvent("job-2", "pan_device_state_backup", "dev-2", "COMPLETED", "=HYPERLINK(\"x\")",
                        Instant.parse("2026-09-22T11:43:23Z"))));

        String[] lines = csv.split("\r\n");
        assertEquals("job_id,job_type,device_name,target_device_id,state,outcome,submitted_at,finished_at,duration_ms,terminal_reason", lines[0]);
        assertEquals("job-1,cp_gateway_backup,FW-TANGO-01,dev-1,FAILED,FAILURE,2026-09-22T11:43:23Z,2026-09-22T11:44:46Z,83000,"
                + "\"sftp fetch failed: SftpException id=4, \"\"no message\"\"\"", lines[1]);
        assertEquals("job-2,pan_device_state_backup,,dev-2,COMPLETED,,2026-09-22T11:43:23Z,,,\"'=HYPERLINK(\"\"x\"\")\"", lines[2]);
    }

    @Test
    void perStateCountsAreUpperCasedSummedAndKeepTheirOrder() {
        Map<String, Long> byState = JobLogQueryService.byStateOf(List.of(
                Map.entry("COMPLETED", 870L), Map.entry(" failed ", 20L), Map.entry("FAILED", 7L),
                Map.entry("", 3L), Map.entry("EXECUTING", 1L)));

        assertEquals(List.of("COMPLETED", "FAILED", "EXECUTING"), List.copyOf(byState.keySet()));
        assertEquals(27L, byState.get("FAILED"));
        assertEquals(870L, byState.get("COMPLETED"));
    }

    private static org.jooq.Record jobRow(String jobType, Long linesStored, boolean withLinesColumn) {
        var ctx = org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES);
        var jobId = org.jooq.impl.DSL.field("job_id", String.class);
        var type = org.jooq.impl.DSL.field("job_type", String.class);
        var device = org.jooq.impl.DSL.field("target_device_id", String.class);
        var name = org.jooq.impl.DSL.field("device_name", String.class);
        var state = org.jooq.impl.DSL.field("state", String.class);
        var outcome = org.jooq.impl.DSL.field("outcome", String.class);
        var reason = org.jooq.impl.DSL.field("terminal_reason", String.class);
        var submitted = org.jooq.impl.DSL.field("submitted_at", Timestamp.class);
        var finished = org.jooq.impl.DSL.field("finished_at", Timestamp.class);
        var transcript = org.jooq.impl.DSL.field("has_transcript", Boolean.class);
        var duration = org.jooq.impl.DSL.field("duration_ms", Double.class);
        var lines = org.jooq.impl.DSL.field("lines_stored", java.math.BigDecimal.class);
        org.jooq.Record row = withLinesColumn
                ? ctx.newRecord(jobId, type, device, name, state, outcome, reason, submitted, finished, transcript, duration, lines)
                : ctx.newRecord(jobId, type, device, name, state, outcome, reason, submitted, finished, transcript, duration);
        row.set(jobId, "job-1");
        row.set(type, jobType);
        row.set(device, "dev-1");
        row.set(name, "FW-TANGO-04");
        row.set(state, "COMPLETED");
        row.set(outcome, "SUCCESS");
        row.set(reason, "COLLECTED");
        row.set(submitted, Timestamp.from(Instant.parse("2026-10-18T03:05:00Z")));
        row.set(finished, Timestamp.from(Instant.parse("2026-10-18T03:06:00Z")));
        row.set(transcript, false);
        row.set(duration, 60_000.0);
        if (withLinesColumn) row.set(lines, linesStored == null ? null : java.math.BigDecimal.valueOf(linesStored));
        return row;
    }

    @Test
    void linesStoredIsReadFromTheRowAndSerialisedAsLinesStored() throws Exception {
        JobEvent event = JobLogQueryService.toEvent(jobRow("cp_system_log_collect", 1234L, true));

        assertEquals(1234L, event.linesStored());
        assertEquals(60_000L, event.durationMs());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(event);
        assertTrue(json.contains("\"lines_stored\":1234"), json);
    }

    @Test
    void linesStoredIsNullNotZeroWhenTheRunHasNoChunkRowOrTheColumnIsAbsent() {
        assertEquals(null, JobLogQueryService.toEvent(jobRow("cp_system_log_collect", null, true)).linesStored());
        assertEquals(null, JobLogQueryService.toEvent(jobRow("cp_inventory_collect", null, true)).linesStored());
        assertEquals(null, JobLogQueryService.toEvent(jobRow("cp_system_log_collect", 9L, false)).linesStored());
    }

    @Test
    void linesStoredIsSummedOnlyForTheLogCollectJobTypesAndSurvivesTheTranscriptStrip() {
        // The CASE keeps the chunk subquery off every other row; the subquery is keyed by the device index the table has.
        assertTrue(JobLogQueryService.LINES_STORED_SQL.startsWith("case when j.job_type in ('cp_system_log_collect', 'pan_system_log_collect') then (select sum(c.lines) from system_log_chunk c "), JobLogQueryService.LINES_STORED_SQL);
        assertTrue(JobLogQueryService.LINES_STORED_SQL.contains("c.device_id = j.target_device_id and c.job_id = j.job_id"), JobLogQueryService.LINES_STORED_SQL);
        assertEquals(List.of("cp_system_log_collect", "pan_system_log_collect"), JobLogQueryService.LOG_COLLECT_JOB_TYPES);

        var withLines = new JobEvent("job-1", "cp_system_log_collect", "dev-1", "FW-TANGO-04", "COMPLETED", "PARTIAL", "COLLECTED",
                Instant.parse("2026-10-18T03:05:00Z"), Instant.parse("2026-10-18T03:06:00Z"), 60_000L, true, 1234L);
        var stripped = new JobLogQueryService.JobPage(List.of(withLines), 1, 50, 1).withTranscriptAccess(false).items().get(0);
        assertEquals(false, stripped.hasTranscript());
        assertEquals(1234L, stripped.linesStored());
    }

    @Test
    void statsSerialiseThePerStateCountsUnderByState() throws Exception {
        Stats stats = new Stats(898, 879, 852, 27, 0, 0, 0, 879, 0,
                JobLogQueryService.byStateOf(List.of(Map.entry("COMPLETED", 870L), Map.entry("FAILED", 28L))));

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(stats);
        assertTrue(json.contains("\"by_state\":{\"COMPLETED\":870,\"FAILED\":28}"), json);
        assertTrue(json.contains("\"total_24h\":879"), json);
    }
}
