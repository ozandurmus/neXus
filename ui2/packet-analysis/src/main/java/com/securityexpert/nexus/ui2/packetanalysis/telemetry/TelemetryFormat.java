package com.securityexpert.nexus.ui2.packetanalysis.telemetry;

/** The host-telemetry text formats the parsers understand. The format is decided by content, never by file name. */
public enum TelemetryFormat {
    SAR("sar", "sar raporu"),
    VMSTAT("vmstat", "vmstat"),
    PIDSTAT("pidstat", "pidstat"),
    SNAPSHOTS("snapshots", "tc -s qdisc / softnet_stat anlık görüntüleri"),
    SYSLOG("syslog", "syslog / messages"),
    UNKNOWN("unknown", "tanınmadı");

    private final String id;
    private final String label;

    TelemetryFormat(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** Stable identifier stored with the attachment (closed set). */
    public String id() {
        return id;
    }

    /** Turkish name used in the expert report. */
    public String label() {
        return label;
    }

    public static TelemetryFormat fromId(String id) {
        for (TelemetryFormat f : values()) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        return UNKNOWN;
    }
}
