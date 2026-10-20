package com.securityexpert.nexus.ui2.service.changes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;

/** Synthetic inventory and projections for the change-feed tests: example names, RFC 5737 addresses, no real identity. */
final class FeedFixtures {
    static final byte[] KEY = ("0123456789abcdef".repeat(2) + "-feed-test").getBytes(StandardCharsets.UTF_8);
    static final String PAN_DEVICE = "dev-pan-1";
    static final String CP_DEVICE = "dev-cp-1";
    static final String PAN_HOST = "edge-fw-01.example.invalid";
    static final String CP_HOST = "gw-lab-07";

    private FeedFixtures() {}

    static List<DeviceSummaryRecord> inventory() {
        return List.of(device(PAN_DEVICE, "palo_alto", PAN_HOST, null), device(CP_DEVICE, "check_point", CP_HOST, "CLS-SYN-01"));
    }

    static DeviceSummaryRecord device(String id, String vendor, String host, String cluster) {
        return new DeviceSummaryRecord(id, "gateway", vendor, DeviceEnrollmentState.ENROLLED, Optional.ofNullable(host),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(cluster), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    static TopologyNamePseudonymizer names() {
        return new TopologyNamePseudonymizer(KEY);
    }

    static DefaultProjection plain() {
        return new DefaultProjection(false, inventory(), names(), null, null);
    }

    static DefaultProjection masked() {
        LogMasking masking = new LogMasking(KEY, () -> KnownNames.NONE);
        return new DefaultProjection(true, inventory(), names(), masking.session(), null);
    }

    /** The masked view when the estate dictionary could not be built: free text must not be shown. */
    static DefaultProjection maskedWithoutText() {
        return new DefaultProjection(true, inventory(), names(), null, new IllegalStateException("dictionary unavailable"));
    }

    /**
     * One {@code pan_config} entry as {@code PanSystemLogCollectJobExecutor} stores it (JSON Lines; the field names
     * measured on all 39 firewalls, findings 2026-10-17 section 5), with the admin, command, path and previews given.
     */
    static String panConfigLine(String receiveTime, String admin, String cmd, String path, String result, String before, String after) {
        StringBuilder sb = new StringBuilder("{\"@logid\":\"7001\",\"domain\":\"1\",\"receive_time\":\"").append(receiveTime)
                .append("\",\"serial\":\"001122334455\",\"seqno\":\"41\",\"actionflags\":\"0x0\",\"is-logging-service\":\"no\","
                        + "\"type\":\"CONFIG\",\"subtype\":\"0\",\"config_ver\":\"2561\",\"time_generated\":\"")
                .append(receiveTime).append("\",\"dg_hier_level_1\":\"0\",\"dg_hier_level_2\":\"0\",\"dg_hier_level_3\":\"0\","
                        + "\"dg_hier_level_4\":\"0\",\"vsys_id\":\"0\",\"device_name\":\"edge-fw-01\",\"tpl_id\":\"0\",\"dg_id\":\"0\","
                        + "\"host\":\"192.0.2.10\",\"cmd\":\"").append(cmd).append("\",\"admin\":\"").append(admin)
                .append("\",\"client\":\"Web\",\"result\":\"").append(result).append("\"");
        if (path != null) sb.append(",\"path\":\"").append(path).append("\"");
        if (before != null) sb.append(",\"before-change-preview\":\"").append(before).append("\"");
        if (after != null) sb.append(",\"after-change-preview\":\"").append(after).append("\"");
        return sb.append(",\"high_res_timestamp\":\"2026-10-17T10:00:00.000+00:00\"}").toString();
    }
}
