package com.securityexpert.nexus.ui2.jobs.stepattempt;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.jobrecords.JobStepAttemptDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.StepAttemptRow;

/** {@link JobStepAttemptRepository} adapter over {@code persistence}'s {@link JobStepAttemptDao}. */
public final class PersistenceJobStepAttemptRepository implements JobStepAttemptRepository {

    private final JobStepAttemptDao dao;

    public PersistenceJobStepAttemptRepository(JobStepAttemptDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    @Override
    public String insertPreContact(String jobId, long leaseEpoch, int stepIndex, String stepKind,
            String actionClass, int attemptNumber) {
        return dao.insertPreContact(jobId, leaseEpoch, stepIndex, stepKind, actionClass, attemptNumber);
    }

    @Override
    public boolean markBoundaryCrossed(String attemptId, long leaseEpoch) {
        return dao.markBoundaryCrossed(attemptId, leaseEpoch);
    }

    @Override
    public boolean writeOutcome(String attemptId, long leaseEpoch, String outcome, String errorClass,
            long outputBytes, long outputLines, String fingerprintSha256) {
        return dao.writeOutcome(attemptId, leaseEpoch, outcome, errorClass, outputBytes, outputLines,
                fingerprintSha256);
    }

    @Override
    public Optional<StepAttempt> find(String attemptId) {
        return dao.find(attemptId).map(PersistenceJobStepAttemptRepository::toStepAttempt);
    }

    @Override
    public List<StepAttempt> findByJobAndStep(String jobId, int stepIndex) {
        return dao.findByJobAndStep(jobId, stepIndex).stream()
                .map(PersistenceJobStepAttemptRepository::toStepAttempt).toList();
    }

    private static StepAttempt toStepAttempt(StepAttemptRow row) {
        return new StepAttempt(row.attemptId(), row.jobId(), row.leaseEpoch(), row.stepIndex(), row.attemptNumber(),
                row.stepKind(), row.actionClass(), row.mutationBoundaryCrossed(),
                Optional.ofNullable(row.outcome()), Optional.ofNullable(row.errorClass()));
    }
}
