package com.securityexpert.nexus.ui2.persistence.snmp;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Configuration;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Device;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.HealthFigures;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Override;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.PollResult;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Settings;

/** The SNMP device metrics store (V268); {@link JooqSnmpPollingRepository} is the database implementation. */
public interface SnmpPollingRepository {
    /** The audit action id of every settings write ({@code app.action_id}). */
    String WRITE_ACTION = "snmp_polling_settings_write";

    Configuration read();

    Configuration write(String actorFingerprint, Settings settings, List<Override> overrides);

    List<Device> eligibleDevices();

    void schedule(String deviceId, Instant firstPollAt);

    boolean claim(String deviceId, Instant expectedNextPollAt, Instant leaseUntil);

    void record(PollResult result);

    int rollup(Instant fromHour, Instant toHour);

    int[] prune(Instant rawCutoff, Instant hourlyCutoff);

    Map<String, HealthFigures> health(Instant since);

    /** Per device, per calendar day in {@code zoneId}, since {@code since}; days oldest first. */
    Map<String, List<SnmpPolling.DailyFigures>> daily(Instant since, String zoneId);

    /** Devices by the outcome of their last poll, counting only polls at or after {@code since}. */
    Map<String, Integer> lastOutcomeCounts(Instant since);

    /** The last poll outcome of every device that has poll state. */
    Map<String, String> lastOutcomes();
}
