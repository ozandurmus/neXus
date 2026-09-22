package com.securityexpert.nexus.ui2.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobEvent;
import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobQuery;
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
}
