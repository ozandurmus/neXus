package com.securityexpert.nexus.ui2.persistence.jobrecords;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Contract §8 test 1 / AC-3: "fails if any code path issues a SELECT then
 * a separate UPDATE on jobs.state." Proved two ways, neither requiring a
 * database:
 *
 * <ol>
 *   <li>{@link JooqJobLeaseDao}'s claim SQL text is exactly one {@code
 *       UPDATE} statement whose only {@code SELECT} is a correlated
 *       subquery inside the same statement's {@code WHERE} clause (never a
 *       second, separate statement) -- and it is byte-for-byte identical
 *       to {@code job-engine}'s {@code ClaimStatementText#SQL}, since
 *       {@code persistence} cannot depend on {@code job-engine} to share
 *       the constant directly (DIR-3).</li>
 *   <li>No other method on {@link JooqJobLeaseDao} issues a {@code SELECT
 *       job_id FROM jobs WHERE state = 'REQUESTED'} followed anywhere by a
 *       separate {@code UPDATE jobs SET state} -- i.e. this class contains
 *       exactly one occurrence of an {@code UPDATE jobs SET state} literal,
 *       inside {@code CLAIM_SQL}.</li>
 * </ol>
 */
class ClaimIsAtomicNoReadThenWriteTest {

    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);

    @Test
    void claimSqlIsOneAtomicUpdateWithReturning() {
        String sql = JooqJobLeaseDao.CLAIM_SQL;
        // "UPDATE" alone would double-count "FOR UPDATE SKIP LOCKED" --
        // the statement-starting form is "UPDATE jobs", counted separately
        // from the row-locking clause below.
        long updateStatementCount = countOccurrences(sql, "UPDATE jobs");
        long forUpdateCount = countOccurrences(sql, "FOR UPDATE SKIP LOCKED");
        long returningCount = countOccurrences(sql, "RETURNING");
        long selectCount = countOccurrences(sql, "SELECT");

        assertEquals(1, updateStatementCount, "the claim statement must be exactly one UPDATE jobs statement");
        assertEquals(1, forUpdateCount, "exactly one FOR UPDATE SKIP LOCKED row-locking clause");
        assertEquals(1, returningCount, "the claim statement must carry exactly one RETURNING clause");
        assertEquals(1, selectCount, "the one SELECT must be the correlated subquery, not a separate statement");
        assertTrue(sql.contains("FOR UPDATE SKIP LOCKED"), "must use FOR UPDATE SKIP LOCKED (C2 §4.1)");
        assertTrue(sql.indexOf("SELECT") > sql.indexOf("WHERE job_id = ("),
                "the SELECT must be nested inside the UPDATE's WHERE clause, not precede it as a separate statement");
    }

    @Test
    void claimSqlMatchesJobEnginesSharedConstantByteForByte() throws IOException {
        String persistenceSql = extractTextBlock(readSource("persistence/src/main/java/com/securityexpert/"
                + "nexus/ui2/persistence/jobrecords/JooqJobLeaseDao.java"));
        String jobEngineSql = extractTextBlock(readSource("job-engine/src/main/java/com/securityexpert/"
                + "nexus/ui2/jobs/lease/ClaimStatementText.java"));

        assertFalse(persistenceSql.isBlank());
        assertEquals(jobEngineSql, persistenceSql,
                "JooqJobLeaseDao.CLAIM_SQL must stay byte-for-byte identical to job-engine's "
                        + "ClaimStatementText.SQL -- persistence cannot depend on job-engine to share the "
                        + "constant directly (DIR-3), so this test is what keeps them from drifting apart");
    }

    @Test
    void noOtherMethodOnTheDaoIssuesASecondClaimPath() throws IOException {
        String source = readSource("persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/jobrecords/"
                + "JooqJobLeaseDao.java");
        long updateJobsStateOccurrences = countOccurrences(source, "UPDATE jobs\n");
        assertEquals(1, updateJobsStateOccurrences,
                "exactly one UPDATE jobs statement literal (CLAIM_SQL) may claim a job; every other mutation "
                        + "in this DAO (heartbeat, transitionState) updates a specific, already-known job_id "
                        + "and never selects a candidate row first");
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String extractTextBlock(String source) {
        Matcher matcher = TEXT_BLOCK.matcher(source);
        assertTrue(matcher.find(), "expected a \"\"\" text block in the source file");
        return matcher.group(1).strip();
    }

    private static String readSource(String relativePath) throws IOException {
        Path ui2Root = Paths.get(System.getProperty("user.dir")).getParent();
        return Files.readString(ui2Root.resolve(relativePath));
    }
}
