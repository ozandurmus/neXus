package com.securityexpert.nexus.ui2.persistence.device.configuration;

import java.util.List;

/**
 * {@code configuration_notification} persistence (migration V16, 14G
 * CG-7d): one notification per device per run that carried at least one
 * {@code src=local} override. No functional notification surface existed
 * in this repository before this movement (WORKER.md scope note) -- this
 * is the minimal table/repository this movement adds for it.
 */
public interface ConfigurationNotificationRepository {

    void record(String deviceId, String runId, String summary, List<String> overridePaths, String actorFingerprint,
            String actionId);

    /** Most recent notifications first, across every device -- the shell badge / {@code GET /notifications} read model. */
    List<ConfigurationNotification> listRecent(int limit);
}
