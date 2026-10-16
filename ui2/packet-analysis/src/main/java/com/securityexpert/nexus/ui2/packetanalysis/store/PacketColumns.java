package com.securityexpert.nexus.ui2.packetanalysis.store;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The {@code packets} table columns and DuckDB types, in table order (the reference {@code PACKET_COLUMNS}). */
public final class PacketColumns {

    private static final String[][] COLUMNS = {
        {"capture_id", "VARCHAR"}, {"frame", "BIGINT"}, {"ts", "DOUBLE"}, {"iface", "INTEGER"},
        {"src", "VARCHAR"}, {"dst", "VARCHAR"}, {"sport", "INTEGER"}, {"dport", "INTEGER"},
        {"proto", "VARCHAR"}, {"ipid", "BIGINT"}, {"seq", "BIGINT"}, {"ack", "BIGINT"},
        {"flags", "INTEGER"}, {"length", "INTEGER"}, {"wirelen", "INTEGER"}, {"caplen", "INTEGER"},
        {"prefix", "VARCHAR"}, {"payload_hash", "VARCHAR"}, {"signature", "VARCHAR"}, {"tuple_key", "VARCHAR"},
        {"reverse_tuple", "VARCHAR"}, {"stream", "BIGINT"}, {"ttl", "INTEGER"}, {"dscp", "INTEGER"},
        {"mss", "INTEGER"}, {"window", "BIGINT"}, {"retrans", "BOOLEAN"}, {"fast_retrans", "BOOLEAN"},
        {"spurious", "BOOLEAN"}, {"out_of_order", "BOOLEAN"}, {"acked_unseen", "BOOLEAN"},
        {"zero_window", "BOOLEAN"}, {"rtt", "DOUBLE"}, {"unsupported", "VARCHAR"}, {"frame_hash", "VARCHAR"},
        {"icmp_id", "INTEGER"}, {"icmp_seq", "INTEGER"}, {"icmp_type", "INTEGER"}, {"dns_id", "INTEGER"},
        {"dns_response", "BOOLEAN"}, {"vendor", "VARCHAR"}, {"path_fields", "VARCHAR"},
    };

    /** Column name to DuckDB type, in table order. */
    public static final Map<String, String> TYPES;

    /** Column names in table order. */
    public static final List<String> NAMES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] c : COLUMNS) {
            m.put(c[0], c[1]);
        }
        TYPES = java.util.Collections.unmodifiableMap(m);
        NAMES = List.copyOf(m.keySet());
    }

    private PacketColumns() {
    }

    /** Index of a column in a row. */
    public static int index(String name) {
        int i = NAMES.indexOf(name);
        if (i < 0) {
            throw new IllegalArgumentException(name);
        }
        return i;
    }

    /** The {@code read_csv} {@code columns=} struct literal, the SQL form of the reference's bound dict. */
    public static String columnsStructLiteral() {
        return TYPES.entrySet().stream().map(e -> "'" + e.getKey() + "': '" + e.getValue() + "'")
                .collect(Collectors.joining(", ", "{", "}"));
    }
}
