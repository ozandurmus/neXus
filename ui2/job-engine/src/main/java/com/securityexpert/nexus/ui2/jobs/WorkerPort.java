package com.securityexpert.nexus.ui2.jobs;

/**
 * The port through which the job engine hands a claimed job to a worker
 * implementation (C2 §5). job-engine depends only on this interface; the
 * worker module's concrete implementation depends inward on job-engine,
 * never the reverse (DIR-3).
 */
public interface WorkerPort {

    void execute(Job job);
}
