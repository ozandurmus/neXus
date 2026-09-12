package com.securityexpert.nexus.ui2.jobs;

import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * Job persistence port. job-engine is allowed to depend on persistence
 * (contract §2 row); persistence never depends back on job-engine.
 */
public interface JobRepository {

    Optional<Job> find(OpaqueId jobId);

    Job save(Job job, TransactionBoundary transactionBoundary);
}
