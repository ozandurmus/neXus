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

    @Test
    void toEventResolvesDeviceClusterFailoverUnitPolicySourceContainerFleetAndNoTarget() {
        var ctx = org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES);
        var fJobId = org.jooq.impl.DSL.field("job_id", String.class);
        var fType = org.jooq.impl.DSL.field("job_type", String.class);
        var fDevice = org.jooq.impl.DSL.field("target_device_id", String.class);
        var fName = org.jooq.impl.DSL.field("device_name", String.class);
        var fCluster = org.jooq.impl.DSL.field("cluster_ref", String.class);
        var fVsId = org.jooq.impl.DSL.field("failover_vs_id", String.class);
        var fDomain = org.jooq.impl.DSL.field("policy_domain_ref", String.class);
        var fContainer = org.jooq.impl.DSL.field("policy_container_name", String.class);
        var fSource = org.jooq.impl.DSL.field("policy_source_name", String.class);
        var fPacket = org.jooq.impl.DSL.field("packet_case_title", String.class);
        var fTargetKind = org.jooq.impl.DSL.field("job_target_kind", String.class);
        var fTargetRef = org.jooq.impl.DSL.field("job_target_ref", String.class);
        var fFleetCount = org.jooq.impl.DSL.field("target_device_count", Integer.class);
        var fState = org.jooq.impl.DSL.field("state", String.class);
        var fOutcome = org.jooq.impl.DSL.field("outcome", String.class);
        var fReason = org.jooq.impl.DSL.field("terminal_reason", String.class);
        var fSubmitted = org.jooq.impl.DSL.field("submitted_at", Timestamp.class);
        var fFinished = org.jooq.impl.DSL.field("finished_at", Timestamp.class);
        var fTranscript = org.jooq.impl.DSL.field("has_transcript", Boolean.class);
        var fDuration = org.jooq.impl.DSL.field("duration_ms", Double.class);

        java.util.function.Consumer<org.jooq.Record> defaults = r -> {
            r.set(fJobId, "job-x");
            r.set(fState, "COMPLETED");
            r.set(fOutcome, "SUCCESS");
            r.set(fSubmitted, Timestamp.from(Instant.parse("2026-10-18T03:05:00Z")));
            r.set(fTranscript, false);
        };

        var clusterRow = ctx.newRecord(fJobId, fType, fDevice, fName, fCluster, fVsId, fState, fOutcome, fReason, fSubmitted, fFinished, fTranscript, fDuration);
        defaults.accept(clusterRow);
        clusterRow.set(fType, "pan_failover_readiness");
        clusterRow.set(fDevice, "dev-m1");
        clusterRow.set(fName, "FW-ROMEO-M1");
        clusterRow.set(fCluster, "CLS-ROMEO-01");
        JobEvent clusterEvent = JobLogQueryService.toEvent(clusterRow);
        assertEquals("CLS-ROMEO-01", clusterEvent.deviceName());
        assertEquals("cluster", clusterEvent.targetKind());

        var unitRow = ctx.newRecord(fJobId, fType, fDevice, fName, fCluster, fVsId, fState, fOutcome, fReason, fSubmitted, fFinished, fTranscript, fDuration);
        defaults.accept(unitRow);
        unitRow.set(fType, "cp_cluster_failover");
        unitRow.set(fDevice, "dev-m1");
        unitRow.set(fCluster, "CLS-ROMEO-01");
        unitRow.set(fVsId, "2");
        JobEvent unitEvent = JobLogQueryService.toEvent(unitRow);
        assertEquals("VSID 2", unitEvent.deviceName());
        assertEquals("failover_unit", unitEvent.targetKind());
        assertEquals("CLS-ROMEO-01", unitEvent.clusterRef());

        var containerRow = ctx.newRecord(fJobId, fType, fDevice, fName, fDomain, fContainer, fSource, fState, fOutcome, fReason, fSubmitted, fFinished, fTranscript, fDuration);
        defaults.accept(containerRow);
        containerRow.set(fType, "cp_policy_collect");
        containerRow.set(fDevice, "mgr-1");
        containerRow.set(fDomain, "dom-uid-1");
        containerRow.set(fContainer, "DOM-ALPHA-01");
        JobEvent containerEvent = JobLogQueryService.toEvent(containerRow);
        assertEquals("DOM-ALPHA-01", containerEvent.deviceName());
        assertEquals("policy_container", containerEvent.targetKind());

        var sourceRow = ctx.newRecord(fJobId, fType, fDevice, fName, fDomain, fContainer, fSource, fTargetKind, fTargetRef, fState, fOutcome, fReason, fSubmitted, fFinished, fTranscript, fDuration);
        defaults.accept(sourceRow);
        sourceRow.set(fType, "pan_policy_collect");
        sourceRow.set(fDevice, "mgr-2");
        sourceRow.set(fSource, "FW-BRAVO-02");
        sourceRow.set(fTargetKind, "discovery_run");
        sourceRow.set(fTargetRef, "run-1");
        JobEvent sourceEvent = JobLogQueryService.toEvent(sourceRow);
        assertEquals("FW-BRAVO-02", sourceEvent.deviceName());
        assertEquals("policy_source", sourceEvent.targetKind());

        var packetRow = ctx.newRecord(fJobId, fType, fDevice, fName, fPacket, fTargetKind, fTargetRef, fState, fOutcome, fReason, fSubmitted, fFinished, fTranscript, fDuration);
        defaults.accept(packetRow);
        packetRow.set(fType, "packet_analysis_run");
        packetRow.set(fPacket, "OBJ-CASE-01");
        packetRow.set(fTargetKind, "packet_analysis_case");
        packetRow.set(fTargetRef, "case-1");
        JobEvent packetEvent = JobLogQueryService.toEvent(packetRow);
        assertEquals("OBJ-CASE-01", packetEvent.deviceName());
        assertEquals("packet_analysis_case", packetEvent.targetKind());

        var fleetRow = ctx.newRecord(fJobId, fType, fDevice, fName, fFleetCount, fState, fOutcome, fReason, fSubmitted, fFinished, fTranscript, fDuration);
        defaults.accept(fleetRow);
        fleetRow.set(fType, "cp_system_log_collect");
        fleetRow.set(fFleetCount, 18);
        JobEvent fleetEvent = JobLogQueryService.toEvent(fleetRow);
        assertEquals("Fleet (18 devices)", fleetEvent.deviceName());
        assertEquals("fleet", fleetEvent.targetKind());

        var emptyRow = ctx.newRecord(fJobId, fType, fDevice, fName, fState, fOutcome, fReason, fSubmitted, fFinished, fTranscript, fDuration);
        defaults.accept(emptyRow);
        emptyRow.set(fType, "cp_policy_collect");
        JobEvent emptyEvent = JobLogQueryService.toEvent(emptyRow);
        assertEquals(null, emptyEvent.deviceName());
        assertEquals(null, emptyEvent.targetKind());
    }

    @Test
    void orderByMapsAllowListedColumnsWithNullsLastAndPrimaryKeyTiebreakAndRejectsUnknowns() {
        assertEquals(" order by j.submitted_at desc nulls last, j.job_id desc",
                JobLogQueryService.orderByOf(null, null));
        assertEquals(" order by j.submitted_at desc nulls last, j.job_id desc",
                JobLogQueryService.orderByOf("", ""));
        assertEquals(" order by lower(nullif(d.observed_hostname, '')) asc nulls last, j.job_id asc",
                JobLogQueryService.orderByOf("device", "asc"));
        assertEquals(" order by lower(nullif(d.observed_hostname, '')) desc nulls last, j.job_id asc",
                JobLogQueryService.orderByOf("device_name", "desc"));
        assertEquals(" order by case when j.finished_at is not null then extract(epoch from (j.finished_at - j.submitted_at)) * 1000 else extract(epoch from (now() - j.submitted_at)) * 1000 end asc nulls last, j.job_id asc",
                JobLogQueryService.orderByOf("duration", "asc"));
        assertEquals(" order by case when j.finished_at is not null then extract(epoch from (j.finished_at - j.submitted_at)) * 1000 else extract(epoch from (now() - j.submitted_at)) * 1000 end desc nulls last, j.job_id asc",
                JobLogQueryService.orderByOf("duration_ms", "desc"));
        assertEquals(" order by j.submitted_at asc nulls last, j.job_id asc",
                JobLogQueryService.orderByOf("submitted_at", ""));

        for (String badSort : List.of("job_id; drop table job_records", "unknown", "1")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> JobLogQueryService.orderByOf(badSort, "asc"));
        }
        for (String badOrder : List.of("sideways", "asc; --", "random")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> JobLogQueryService.orderByOf("state", badOrder));
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> JobLogQueryService.orderByOf("", "asc"));
    }

    private static JobQuery masked(Optional<String> text, Optional<String> sort) {
        return new JobQuery(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                text, 1, 50, sort, Optional.empty(), true);
    }

    @Test
    void theMaskedSearchMatchesTheOpaqueIdsOnlyNeverAHostnameOrReason() {
        // Security review 2026-11-05 M-2: q on d.observed_hostname (or on a reason text that carries raw names and
        // addresses) answers "does a real name contain X" for aiview, which is shown pseudonyms only.
        Where where = JobLogQueryService.whereOf(masked(Optional.of("Edge_1"), Optional.empty()));

        assertEquals(" where (lower(j.job_id) like ? or lower(j.target_device_id) like ?)", where.sql());
        assertEquals(List.of("%edge\\_1%", "%edge\\_1%"), where.bindings());
        assertTrue(!where.sql().contains("observed_hostname") && !where.sql().contains("terminal_reason"), where.sql());
        // The unmasked search is unchanged.
        Where full = JobLogQueryService.whereOf(new JobQuery(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of("edge"), 1, 50));
        assertTrue(full.sql().contains("d.observed_hostname") && full.sql().contains("j.terminal_reason"), full.sql());
    }

    @Test
    void theMaskedViewCannotSortByTargetEveryOtherSortStays() {
        for (String sort : List.of("device", "device_name")) {
            var refused = org.junit.jupiter.api.Assertions.assertThrows(JobLogQueryService.MaskedSortRefused.class,
                    () -> masked(Optional.empty(), Optional.of(sort)));
            assertTrue(refused.getMessage().startsWith("Sorting by target is not available in the masked (aiview) view"),
                    refused.getMessage());
            org.junit.jupiter.api.Assertions.assertThrows(JobLogQueryService.MaskedSortRefused.class,
                    () -> JobLogQueryService.orderByOf(sort, "desc", true));
            // Not masked: allowed, as before.
            assertEquals(" order by lower(nullif(d.observed_hostname, '')) asc nulls last, j.job_id asc",
                    JobLogQueryService.orderByOf(sort, "asc", false));
        }
        for (String sort : JobLogQueryService.SORT_COLUMNS.keySet()) {
            if (!JobLogQueryService.RAW_NAME_SORTS.contains(sort)) {
                assertEquals(JobLogQueryService.orderByOf(sort, "asc", false), JobLogQueryService.orderByOf(sort, "asc", true), sort);
            }
        }
        // Every sort expression that reads a name is in the refused set: only d.observed_hostname is a raw name here.
        JobLogQueryService.SORT_COLUMNS.forEach((key, expression) -> assertEquals(expression.contains("observed_hostname"),
                JobLogQueryService.RAW_NAME_SORTS.contains(key), key));
        // An unknown sort is still an unknown sort for the masked view, not the masked-view message.
        var unknown = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> masked(Optional.empty(), Optional.of("nope")));
        assertTrue(!(unknown instanceof JobLogQueryService.MaskedSortRefused));
    }

    @Test
    void thePageIsChosenBeforeAnyTargetJoinRuns() {
        // Security review 2026-11-05 M-5: the five target laterals sat beside the sort, so every non-indexed sort and the
        // export evaluated them for every filtered job before the limit. The inner query now picks the page from
        // jobs/devices alone; the outer query resolves the target labels for those job ids only.
        JobQuery query = new JobQuery(Optional.of("failed"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of("x"), 2, 25, Optional.of("result"), Optional.of("desc"));
        Where where = JobLogQueryService.whereOf(query);
        String orderBy = JobLogQueryService.orderByOf(query);
        String sql = JobLogQueryService.pagedSelect(where, orderBy, " limit ? offset ?");

        int innerStart = sql.indexOf(" from (select j.job_id, row_number() over (");
        int innerEnd = sql.indexOf(") page_ids");
        assertTrue(innerStart > 0 && innerEnd > innerStart, sql);
        String inner = sql.substring(innerStart, innerEnd);
        String outer = sql.substring(innerEnd);
        assertTrue(!inner.contains("lateral") && !inner.contains("discovery_candidate") && !inner.contains("policy_snapshot")
                && !inner.contains("system_log_chunk"), "no target resolution before the limit: " + inner);
        assertTrue(inner.contains(where.sql() + orderBy + " limit ? offset ?"), inner);
        assertTrue(inner.contains("row_number() over (" + orderBy.strip() + ") as ord"), inner);
        assertTrue(outer.contains(" join jobs j on j.job_id = page_ids.job_id") && outer.contains("left join lateral"), outer);
        assertTrue(outer.endsWith(" order by page_ids.ord"), outer);
        assertEquals(sql.indexOf("?"), sql.indexOf("?", innerStart), "every binding is inside the inner query");
        assertTrue(sql.lastIndexOf("?") < innerEnd, "every binding is inside the inner query");

        // The inner query may read only j and d: every filter clause and every sort expression must keep to them, or the
        // page could not be chosen before the joins.
        java.util.regex.Pattern alias = java.util.regex.Pattern.compile("\\b([a-z_][a-z0-9_]*)\\.[a-z_]");
        List<String> expressions = new java.util.ArrayList<>(JobLogQueryService.SORT_COLUMNS.values());
        expressions.add(JobLogQueryService.whereOf(new JobQuery(Optional.of("a,b"), Optional.of("t"), Optional.of("d"),
                Optional.of(Instant.EPOCH), Optional.of(Instant.EPOCH), Optional.of("x"), 1, 1)).sql());
        expressions.add(JobLogQueryService.whereOf(masked(Optional.of("x"), Optional.empty())).sql());
        for (String expression : expressions) {
            var m = alias.matcher(expression);
            while (m.find()) {
                assertTrue(m.group(1).equals("j") || m.group(1).equals("d"), m.group(1) + " in " + expression);
            }
        }
    }

    @Test
    void thePageSaysWhatTheMaskedViewMaySortAndSearchAndEveryCopyKeepsIt() throws Exception {
        var event = new JobEvent("job-1", "cp_gateway_backup", "dev-1", "FW-TANGO-04", "COMPLETED", "SUCCESS", null,
                Instant.parse("2026-11-05T10:00:00Z"), null, null, true);
        var maskedPage = new JobLogQueryService.JobPage(List.of(event), 1, 50, 1, false, false, JobLogQueryService.SEARCH_SCOPE_IDS);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(maskedPage);
        assertTrue(json.contains("\"target_sortable\":false") && json.contains("\"search_scope\":\"ids\""), json);
        var copy = maskedPage.withTranscriptAccess(false).withCancellationAccess(true).withItems(List.of(event));
        assertEquals(false, copy.targetSortable());
        assertEquals("ids", copy.searchScope());
        assertEquals(true, copy.canCancel());
        // The default page (every unmasked caller) offers both.
        var plain = new JobLogQueryService.JobPage(List.of(), 1, 50, 0);
        assertEquals(true, plain.targetSortable());
        assertEquals("full", plain.searchScope());
    }
}
