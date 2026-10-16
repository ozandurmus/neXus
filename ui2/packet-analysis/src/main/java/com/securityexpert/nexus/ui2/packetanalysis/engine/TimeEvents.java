package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Time and event detector lane: the topology settings these detectors read (reference
 * {@code topology.models.Topology} fields with their defaults) and the state the reference keeps in
 * {@code _run_secondary_detectors} locals. Values keep the reference int/float type: an absent field is the
 * pydantic default literal (an int where the default is written as an int), a given value of a {@code float}
 * field is a float, of an {@code int} field an int.
 */
public final class TimeEvents {
    // ------------------------------------------------------------------ settings
    public final Object bucketSeconds;
    public final Object microburstWindowMs;
    public final Object cycleStallMs;
    public final Object cycleTopN;
    public final Object sessionTimeoutSeconds;
    public final Object securityHalfOpenFloor;
    public final Object securityScanWindowSeconds;
    public final Object securityScanTargets;
    public final Object securityScanFailedRatio;
    public final Object securitySynRate;
    public final Object securityCompletionRatio;
    public final Object securityMinSources;
    public final Object securitySustainedBuckets;
    public final Object minEligibleRatio;
    public final Object duplicateUs;
    public final Object stallMs;
    public final Object matchWindowMs;

    // ------------------------------------------------------------------ detector outputs
    public Map<String, Object> timeseries = new LinkedHashMap<>();
    public Map<String, Object> sessions = new LinkedHashMap<>();
    public Map<String, Object> onsets = new LinkedHashMap<>();
    public Map<String, Object> microbursts = new LinkedHashMap<>();
    public List<Map<String, Object>> securityFindings = new ArrayList<>();
    public Map<String, Object> securityReport = new LinkedHashMap<>();
    public Map<String, Object> hostReport = new LinkedHashMap<>();
    public List<Map<String, Object>> hostFindings = new ArrayList<>();
    public Map<String, Object> cycleReport = new LinkedHashMap<>();
    public Map<String, Object> primaryHost;
    public Map<String, Object> delivery = new LinkedHashMap<>();
    public Map<String, Object> incident = new LinkedHashMap<>();
    public Map<String, Object> sessionReport = new LinkedHashMap<>();
    public Map<String, Object> deviceReport = new LinkedHashMap<>();
    /** Reference {@code models}' {@code json()} per capture id (the {@code clock_json} argument). */
    public Map<String, Map<String, Object>> clockJson = new LinkedHashMap<>();

    TimeEvents(JsonNode raw) {
        bucketSeconds = flt(raw, "bucket_seconds", 1L);
        microburstWindowMs = flt(raw, "microburst_window_ms", 1L);
        cycleStallMs = flt(raw, "cycle_stall_ms", 100L);
        cycleTopN = integer(raw, "cycle_top_n", 20L);
        sessionTimeoutSeconds = flt(raw, "session_timeout_seconds", 10L);
        securityHalfOpenFloor = integer(raw, "security_half_open_floor", 20L);
        securityScanWindowSeconds = flt(raw, "security_scan_window_seconds", 5L);
        securityScanTargets = integer(raw, "security_scan_targets", 20L);
        securityScanFailedRatio = flt(raw, "security_scan_failed_ratio", 0.8);
        securitySynRate = flt(raw, "security_syn_rate", 50L);
        securityCompletionRatio = flt(raw, "security_completion_ratio", 0.2);
        securityMinSources = integer(raw, "security_min_sources", 20L);
        securitySustainedBuckets = integer(raw, "security_sustained_buckets", 3L);
        minEligibleRatio = flt(raw, "min_eligible_ratio", 0.9);
        duplicateUs = flt(raw, "duplicate_us", 20L);
        stallMs = flt(raw, "stall_ms", 200L);
        matchWindowMs = flt(raw, "match_window_ms", 2000L);
    }

    /** The lane state of one engine run (created on first use; one per {@link EngineContext}). */
    static TimeEvents of(EngineContext ctx) {
        if (ctx.timeEvents == null) {
            ctx.timeEvents = new TimeEvents(ctx.topology.raw);
        }
        return ctx.timeEvents;
    }

    private static Object flt(JsonNode raw, String key, Object fallback) {
        if (raw == null || !raw.hasNonNull(key)) {
            return fallback;
        }
        return raw.get(key).asDouble();
    }

    private static Object integer(JsonNode raw, String key, Object fallback) {
        if (raw == null || !raw.hasNonNull(key)) {
            return fallback;
        }
        return raw.get(key).asLong();
    }
}
