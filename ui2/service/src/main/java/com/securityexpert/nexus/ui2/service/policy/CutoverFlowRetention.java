package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.CutoverRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Retention of cutover assessment flows (PO decision record 2026-11-14, item 5, Q7: "90 days for flows and watch
 * records; verdicts kept with their segment"). Once an hour it removes the flow and missing-rule rows of assessments
 * created more than {@value #RETENTION_DAYS} days ago. The assessment row, its verdict and its summary counts are never
 * touched; the flows read then answers {@code flowsExpired} so an expired assessment never reads as "no flows".
 *
 * <p>Bounded passes: at most {@value #MAX_ASSESSMENTS} assessments, one short transaction each (an assessment's rows go
 * together, never split), and no further assessment is started once {@value #MAX_ROWS} rows were removed in the pass.
 * A failed assessment is retried on the next pass. The log carries counts and exception class names only: never an
 * assessment id, a segment name or an address. Two replicas running a pass at once remove each row once (the delete is
 * idempotent). {@code ui2.cutover.flow-retention.enabled=false} switches the janitor off; the read side stays.</p>
 */
@Service
public class CutoverFlowRetention {
    public static final int RETENTION_DAYS = 90;
    static final int MAX_ASSESSMENTS = 50;
    static final long MAX_ROWS = 50_000;
    static final String ACTOR = "system:cutover-flow-retention";
    static final String TAG = "[CUTOVER_FLOW_RETENTION]";
    private static final System.Logger LOG = System.getLogger(CutoverFlowRetention.class.getName());

    /** Where a pass line goes; the service log in production, a list in tests. */
    interface Log { void line(System.Logger.Level level, String message); }

    /** One pass: assessments whose flows were removed, rows removed, assessments that failed (retried next pass). */
    record Pass(int assessments, long rows, int failures) {}

    private final CutoverRepository repository;
    private final boolean enabled;
    private Log log = (level, message) -> LOG.log(level, message);

    @Autowired
    public CutoverFlowRetention(TransactionBoundary transactions, @Value("${ui2.cutover.flow-retention.enabled:true}") boolean enabled) {
        this(new CutoverRepository(transactions), enabled);
    }
    public CutoverFlowRetention(CutoverRepository repository, boolean enabled) {
        this.repository = Objects.requireNonNull(repository, "repository"); this.enabled = enabled;
    }

    CutoverFlowRetention withLog(Log sink) { this.log = Objects.requireNonNull(sink, "sink"); return this; }

    public boolean enabled() { return enabled; }

    /** True when the assessment is past retention and its flow rows were removed (see {@link CutoverRepository#flowsExpired}). */
    public boolean flowsExpired(String assessmentId) { return repository.flowsExpired(assessmentId, RETENTION_DAYS); }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void tick() {
        if (enabled) pass();
    }

    Pass pass() {
        List<String> expired;
        try {
            expired = repository.expiredAssessmentsWithFlows(RETENTION_DAYS, MAX_ASSESSMENTS);
        } catch (RuntimeException failure) {
            log.line(System.Logger.Level.WARNING, TAG + " pass skipped: the expired-assessment read failed (" + failure.getClass().getSimpleName()
                + "); 0 assessments, 0 flow rows removed; retried next pass");
            return new Pass(0, 0, 1);
        }
        int assessments = 0, failures = 0;
        long rows = 0;
        var failureClasses = new TreeSet<String>();
        for (String id : expired) {
            if (rows >= MAX_ROWS) break;
            try {
                int removed = repository.deleteExpiredFlows(id, RETENTION_DAYS, ACTOR);
                if (removed > 0) { assessments++; rows += removed; }
            } catch (RuntimeException failure) {
                failures++; failureClasses.add(failure.getClass().getSimpleName());
            }
        }
        String line = TAG + " pass: " + assessments + " assessment(s) past " + RETENTION_DAYS + " days, " + rows + " flow row(s) removed";
        if (failures == 0) log.line(System.Logger.Level.INFO, line);
        else log.line(System.Logger.Level.WARNING, line + "; " + failures + " assessment(s) failed (" + String.join(", ", failureClasses)
            + "), retried next pass");
        return new Pass(assessments, rows, failures);
    }
}
