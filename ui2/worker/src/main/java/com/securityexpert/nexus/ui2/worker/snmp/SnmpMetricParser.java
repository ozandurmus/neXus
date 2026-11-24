package com.securityexpert.nexus.ui2.worker.snmp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Metrics;
import com.securityexpert.nexus.ui2.worker.snmp.SnmpClient.SnmpAnswer;
import com.securityexpert.nexus.ui2.worker.snmp.SnmpClient.SnmpValue;

/**
 * Turns one in-memory SNMP answer into the four facts of design section 2. A value that is missing, not a number, or out
 * of its range is UNKNOWN (null), never 0. Pure: no I/O, no logging of values.
 */
public final class SnmpMetricParser {
    private SnmpMetricParser() {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static Metrics parse(SnmpVendorProfile profile, SnmpAnswer.Answered answer) {
        Long uptime = nonNegative(answer.get(SnmpOids.SYS_UPTIME));
        return switch (profile) {
            case CHECK_POINT -> new Metrics(uptime, percent(answer.get(SnmpOids.CP_PROC_USAGE)), null,
                    ratio(nonNegative(answer.get(SnmpOids.CP_MEM_ACTIVE_REAL64)),
                            nonNegative(answer.get(SnmpOids.CP_MEM_TOTAL_REAL64))),
                    nonNegative(answer.get(SnmpOids.CP_FW_NUM_CONN)));
            case PALO_ALTO -> {
                List<BigDecimal> loads = column(answer, SnmpOids.HR_PROCESSOR_LOAD).values().stream()
                        .map(SnmpMetricParser::percent).toList();
                BigDecimal management = loads.isEmpty() ? null : loads.get(0);
                BigDecimal dataplane = loads.size() < 2 || loads.subList(1, loads.size()).contains(null) ? null
                        : loads.subList(1, loads.size()).stream().max(Comparator.naturalOrder()).orElse(null);
                yield new Metrics(uptime, management, dataplane, panMemory(answer),
                        nonNegative(answer.get(SnmpOids.PAN_SESSION_ACTIVE)));
            }
        };
    }

    /**
     * hrStorageUsed / hrStorageSize of the lowest-index hrStorageRam row. Allocation units cancel. No RAM row, or a size
     * of 0, leaves memory UNKNOWN.
     */
    static BigDecimal panMemory(SnmpAnswer.Answered answer) {
        Map<List<Integer>, SnmpValue> types = column(answer, SnmpOids.HR_STORAGE_TYPE);
        for (var e : types.entrySet()) {
            SnmpValue type = e.getValue();
            if (type.kind() != SnmpValue.Kind.OID || !SnmpOids.HR_STORAGE_RAM.equals(type.text())) continue;
            String suffix = suffix(e.getKey());
            return ratio(nonNegative(answer.get(SnmpOids.HR_STORAGE_USED + suffix)),
                    nonNegative(answer.get(SnmpOids.HR_STORAGE_SIZE + suffix)));
        }
        return null;
    }

    /**
     * The rows of one table column, ordered by their instance suffix (compared component by component). The suffix is
     * used for ordering and row matching only.
     */
    static Map<List<Integer>, SnmpValue> column(SnmpAnswer.Answered answer, String column) {
        Map<List<Integer>, SnmpValue> rows = new TreeMap<>(SnmpMetricParser::compareSuffix);
        for (var e : answer.values().entrySet()) {
            if (!e.getKey().startsWith(column + ".")) continue;
            List<Integer> suffix = new ArrayList<>();
            try {
                for (String part : e.getKey().substring(column.length() + 1).split("\\.")) suffix.add(Integer.parseInt(part));
            } catch (NumberFormatException notAnInstance) {
                continue;
            }
            rows.put(List.copyOf(suffix), e.getValue());
        }
        return rows;
    }

    private static int compareSuffix(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = Integer.compare(a.get(i), b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }

    private static String suffix(List<Integer> parts) {
        StringBuilder s = new StringBuilder();
        for (int p : parts) s.append('.').append(p);
        return s.toString();
    }

    /** A whole number >= 0: an integer syntax, or a digits-only string (some CHECKPOINT-MIB copies use DisplayString). */
    static Long nonNegative(SnmpValue v) {
        if (v == null) return null;
        if (v.kind() == SnmpValue.Kind.NUMBER) return v.number() != null && v.number() >= 0 ? v.number() : null;
        if (v.kind() == SnmpValue.Kind.TEXT && v.text() != null) {
            String t = v.text().strip();
            if (t.isEmpty() || t.length() > 19 || !t.chars().allMatch(Character::isDigit)) return null;
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException tooLarge) {
                return null;
            }
        }
        return null;
    }

    /** A percent in [0, 100], two decimals; anything else is UNKNOWN. */
    static BigDecimal percent(SnmpValue v) {
        Long n = nonNegative(v);
        return n == null || n > 100 ? null : BigDecimal.valueOf(n).setScale(2, RoundingMode.HALF_UP);
    }

    /** part / whole as a percent; UNKNOWN when either is unknown, whole is 0, or part exceeds whole. */
    static BigDecimal ratio(Long part, Long whole) {
        if (part == null || whole == null || whole <= 0 || part > whole) return null;
        return BigDecimal.valueOf(part).multiply(HUNDRED).divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }
}
