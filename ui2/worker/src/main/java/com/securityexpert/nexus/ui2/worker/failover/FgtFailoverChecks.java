package com.securityexpert.nexus.ui2.worker.failover;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FortiGate (FGCP) failover readiness: pure parsers of the six reads and the nine checks of
 * docs/design/PO_DECISION_REQUEST_2026_11_17_FGT_FAILOVER_READINESS.md. Every read is taken from the ENROLLED primary
 * address only (the secondary has no address of its own in neXus yet, design question Q2): what is said about the
 * secondary is the primary's report, one-sided evidence, and every result says so ({@code basis}).
 *
 * <p>The {@code get system ha status} shapes follow Fortinet's documented FortiOS 7.x output and the one shape measured
 * on the estate (2026-09-26: "Primary: serial, HA operating index = 0"); both the Master/Slave and the
 * Primary/Secondary wording are accepted. The three new reads' shapes are NOT measured yet: their parsers accept only the
 * documented forms and everything else is UNKNOWN, never PASS.
 *
 * <p>Raw-evidence law: a parser returns structured values to its caller in memory; a check result carries only derived
 * evidence (booleans, counts, enums, MATCH / MISMATCH / MISSING) and a reason code, never a serial, hostname, address,
 * interface name or version value. Serials are compared with exact string equality only (AGENTS.md identity law).
 */
final class FgtFailoverChecks {
    private FgtFailoverChecks() {}

    static final String PASS = "PASS", FAIL = "FAIL", UNKNOWN = "UNKNOWN", WARN = "WARN";
    static final String UNSUPPORTED_MODE = "UNSUPPORTED_MODE";
    static final String BASIS = "PRIMARY_REPORT";

    /** A check's result: status, one reason code, and derived evidence of booleans, counts and enums only. */
    record Result(int check, String status, String reason, Map<String, Object> evidence) {
        Result {
            evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        }
        /** Check 6 (not evaluated in this phase) and check 9 (information) never decide the outcome. */
        boolean blocking() { return check != 6 && check != 9; }
    }

    // ---- get system ha status -----------------------------------------------------------------------------------

    /** One member line: the role word ("PRIMARY" / "SECONDARY", Master and Slave folded) and the exact serial. */
    record Member(String role, String serial) {}
    /** One interface line of an HBDEV or MONDEV block. */
    record Dev(boolean up) {}

    record HaStatus(boolean recognised, String health, String mode, List<Member> members, boolean memberConflict,
            Integer vclusters, Map<String, Boolean> inSync, Map<String, List<Dev>> hbdev, Map<String, List<Dev>> mondev,
            boolean hbdevSection, boolean mondevSection, Optional<LocalDateTime> lastChange) {
        static final HaStatus NOT_RECOGNISED = new HaStatus(false, null, null, List.of(), false, null, Map.of(), Map.of(),
            Map.of(), false, false, Optional.empty());
    }

    private static final Pattern HEALTH = Pattern.compile("(?m)^\\s*HA Health Status:\\s*(.+?)\\s*$");
    private static final Pattern MODE = Pattern.compile("(?m)^\\s*Mode:\\s*(.+?)\\s*$");
    private static final Pattern MEMBER = Pattern.compile(
        "^\\s*(Primary|Master|Secondary|Slave)\\s*:\\s*(?:[^,]*,\\s*)?([A-Za-z0-9]+)\\s*,\\s*HA (?:cluster|operating) index\\s*=\\s*\\d{1,3}\\s*$");
    private static final Pattern VCLUSTERS = Pattern.compile("(?m)^\\s*number of vcluster:\\s*(\\d{1,3})\\s*$");
    private static final Pattern SECTION = Pattern.compile("^(Configuration Status|HBDEV stats|MONDEV stats|System Usage stats):\\s*$");
    private static final Pattern MEMBER_HEADER = Pattern.compile("^\\s+([A-Za-z0-9]+)\\(updated[^)]*\\):\\s*(.*?)\\s*$");
    private static final Pattern DEV_LINE = Pattern.compile("^\\s+([^\\s:]+):\\s*[^,]*,\\s*(up|down)\\b.*$");
    private static final Pattern HISTORY_STAMP = Pattern.compile("^\\s*<(\\d{4})/(\\d{2})/(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})>");
    private static final Pattern CHANGE_STAMP = Pattern.compile(
        "(?m)^\\s*Cluster state change time:\\s*(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\s*$");

    static HaStatus parseHaStatus(String output) {
        if (output == null || output.isBlank()) return HaStatus.NOT_RECOGNISED;
        Matcher modeMatch = MODE.matcher(output);
        Matcher healthMatch = HEALTH.matcher(output);
        String health = healthMatch.find() ? healthMatch.group(1) : null;
        String mode = null;
        if (modeMatch.find()) {
            String raw = modeMatch.group(1).toUpperCase(Locale.ROOT).replaceFirst("^HA\\s+", "");
            mode = raw.equals("A-P") || raw.equals("A-A") ? raw : raw.startsWith("STANDALONE") ? "STANDALONE" : "OTHER";
        } else if (health != null && health.equalsIgnoreCase("standalone")) {
            mode = "STANDALONE";
        }
        if (mode == null) return HaStatus.NOT_RECOGNISED;
        List<Member> members = new ArrayList<>();
        boolean conflict = false;
        Map<String, String> roleBySerial = new LinkedHashMap<>();
        Map<String, Boolean> inSync = new LinkedHashMap<>();
        Map<String, List<Dev>> hb = new LinkedHashMap<>(), mon = new LinkedHashMap<>();
        boolean hbSection = false, monSection = false;
        String section = null, current = null;
        LocalDateTime newest = null;
        for (String line : output.split("\\R")) {
            Matcher member = MEMBER.matcher(line);
            if (member.matches()) {
                String word = member.group(1).toLowerCase(Locale.ROOT);
                String role = word.equals("primary") || word.equals("master") ? "PRIMARY" : "SECONDARY";
                String serial = member.group(2);
                String earlier = roleBySerial.putIfAbsent(serial, role);
                if (earlier != null && !earlier.equals(role)) conflict = true;
                if (earlier == null) members.add(new Member(role, serial));
                section = null;
                continue;
            }
            Matcher stamp = HISTORY_STAMP.matcher(line);
            if (stamp.find()) newest = later(newest, localTime(stamp));
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                Matcher header = SECTION.matcher(line.stripTrailing());
                section = header.matches() ? header.group(1) : null;
                current = null;
                if ("HBDEV stats".equals(section)) hbSection = true;
                if ("MONDEV stats".equals(section)) monSection = true;
                continue;
            }
            if (section == null) continue;
            Matcher head = MEMBER_HEADER.matcher(line);
            if ("Configuration Status".equals(section) && head.matches()) {
                String state = head.group(2);
                if (state.equals("in-sync")) inSync.put(head.group(1), true);
                else if (state.equals("out-of-sync")) inSync.put(head.group(1), false);
                continue;
            }
            if ("HBDEV stats".equals(section) || "MONDEV stats".equals(section)) {
                if (head.matches() && head.group(2).isEmpty()) {
                    current = head.group(1);
                    (section.startsWith("HBDEV") ? hb : mon).computeIfAbsent(current, k -> new ArrayList<>());
                    continue;
                }
                Matcher dev = DEV_LINE.matcher(line);
                if (current != null && dev.matches()) {
                    (section.startsWith("HBDEV") ? hb : mon).get(current).add(new Dev("up".equals(dev.group(2))));
                }
            }
        }
        Matcher change = CHANGE_STAMP.matcher(output);
        if (change.find()) newest = later(newest, localTime(change));
        Matcher vc = VCLUSTERS.matcher(output);
        Integer vclusters = vc.find() ? Integer.valueOf(vc.group(1)) : null;
        return new HaStatus(true, health, mode, List.copyOf(members), conflict, vclusters, inSync, hb, mon, hbSection,
            monSection, Optional.ofNullable(newest));
    }

    private static LocalDateTime localTime(Matcher m) {
        try {
            return LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
        } catch (java.time.DateTimeException invalid) {
            return null;
        }
    }
    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return b == null ? a : a == null || b.isAfter(a) ? b : a;
    }

    // ---- get system status (system time, for check 9) -----------------------------------------------------------

    private static final Pattern SYSTEM_TIME = Pattern.compile(
        "(?m)^System time:\\s*(?:[A-Za-z]{3}\\s+)?([A-Za-z]{3})\\s+(\\d{1,2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\s+(\\d{4})\\s*$");
    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    /** The device's own clock from {@code get system status}; the history stamps are on the same clock, so no zone is needed. */
    static Optional<LocalDateTime> systemTime(String output) {
        if (output == null) return Optional.empty();
        Matcher m = SYSTEM_TIME.matcher(output);
        if (!m.find()) return Optional.empty();
        int month = MONTHS.indexOf(m.group(1).toLowerCase(Locale.ROOT)) + 1;
        if (month == 0) return Optional.empty();
        try {
            return Optional.of(LocalDateTime.of(Integer.parseInt(m.group(6)), month, Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))));
        } catch (java.time.DateTimeException invalid) {
            return Optional.empty();
        }
    }

    // ---- show full-configuration system ha ----------------------------------------------------------------------

    /** The first-level {@code set} lines of the {@code config system ha} block; empty map is not the same as absent. */
    record HaConfig(boolean recognised, Map<String, String> settings) {}

    private static final Pattern SET_LINE = Pattern.compile("^\\s*set\\s+(\\S+)\\s+(.*?)\\s*$");

    static HaConfig parseHaConfig(String output) {
        if (output == null) return new HaConfig(false, Map.of());
        Map<String, String> settings = new LinkedHashMap<>();
        boolean inBlock = false;
        int depth = 0;
        for (String line : output.split("\\R")) {
            String t = line.strip();
            if (!inBlock) {
                if (t.equals("config system ha")) { inBlock = true; depth = 1; }
                continue;
            }
            if (t.startsWith("config ")) { depth++; continue; }
            if (t.equals("end")) { if (--depth == 0) break; continue; }
            Matcher set = SET_LINE.matcher(line);
            if (depth == 1 && set.matches()) settings.put(set.group(1), set.group(2));
        }
        return new HaConfig(inBlock, Map.copyOf(settings));
    }

    // ---- diagnose sys ha checksum cluster -----------------------------------------------------------------------

    /** Per member serial: the {@code checksum} block's lines (name to hex with whitespace removed). */
    record Checksums(boolean recognised, Map<String, Map<String, String>> byMember) {}

    private static final Pattern SUM_HEADER = Pattern.compile("^=+\\s*([A-Za-z0-9]+)\\s*=+\\s*$");
    private static final Pattern SUM_LINE = Pattern.compile("^\\s*([A-Za-z0-9_.-]{1,31}):\\s*((?:[0-9a-fA-F]{2}\\s*){4,})$");

    static Checksums parseChecksums(String output) {
        if (output == null) return new Checksums(false, Map.of());
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        String member = null;
        boolean inChecksum = false;
        for (String line : output.split("\\R")) {
            Matcher header = SUM_HEADER.matcher(line.strip());
            if (header.matches()) { member = header.group(1); inChecksum = false; out.putIfAbsent(member, new LinkedHashMap<>()); continue; }
            if (member == null) continue;
            if (line.strip().equals("checksum")) { inChecksum = true; continue; }
            if (line.strip().equals("debugzone")) { inChecksum = false; continue; }
            Matcher sum = SUM_LINE.matcher(line);
            if (inChecksum && sum.matches()) out.get(member).put(sum.group(1), sum.group(2).replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
            else if (inChecksum && !line.isBlank()) inChecksum = false;
        }
        boolean any = out.values().stream().anyMatch(m -> !m.isEmpty());
        return new Checksums(any, out);
    }

    // ---- diagnose sys ha dump-by group --------------------------------------------------------------------------

    /** Per serial: the HA uptime in seconds the group dump reports. */
    record Uptimes(boolean recognised, Map<String, Long> bySerial) {}

    private static final Pattern UPTIME_LINE = Pattern.compile(
        "'?([A-Za-z0-9]{6,})'?[^\\n]*?\\buptime/reset_cnt\\s*=\\s*(\\d{1,12})\\s*/\\s*\\d{1,9}");

    static Uptimes parseUptimes(String output) {
        if (output == null) return new Uptimes(false, Map.of());
        Map<String, Long> out = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            Matcher m = UPTIME_LINE.matcher(line);
            if (m.find()) out.putIfAbsent(m.group(1), Long.parseLong(m.group(2)));
        }
        return new Uptimes(!out.isEmpty(), out);
    }

    // ---- execute ha failover status -----------------------------------------------------------------------------

    enum Flag { NOT_SET, SET, UNRECOGNISED }

    private static final Pattern FLAG_CLEAR = Pattern.compile("(?i)\\bfailover\\b[^\\n]*\\b(?:not set|unset|cleared|no failover)\\b");
    private static final Pattern FLAG_SET = Pattern.compile("(?i)\\bfailover\\b[^\\n]*\\b(?:is set|has been set|set)\\b");

    /** Conservative: only the two documented words are accepted; anything else, or both at once, is unrecognised. */
    static Flag parseFlag(String output) {
        if (output == null || output.isBlank()) return Flag.UNRECOGNISED;
        boolean clear = FLAG_CLEAR.matcher(output).find();
        boolean set = !clear && FLAG_SET.matcher(output).find();
        return clear ? Flag.NOT_SET : set ? Flag.SET : Flag.UNRECOGNISED;
    }

    // ---- one observation ------------------------------------------------------------------------------------------

    /**
     * Everything the primary answered. A {@code null} component means the read was not sent (its gate did not resolve)
     * or answered nothing usable; the checks it feeds are UNKNOWN. {@code answeringSerial} is the serial of the unit that
     * answered {@code get system status}, held in memory for the identity comparison only.
     */
    record Observation(HaStatus ha, HaConfig config, Checksums checksums, Uptimes uptimes, Flag flag,
            Optional<LocalDateTime> systemTime, Optional<String> answeringSerial, boolean versionParsed) {}

    // ---- the nine checks --------------------------------------------------------------------------------------------

    static List<Result> evaluate(Observation o, Optional<String> recordedSerial) {
        List<Result> out = new ArrayList<>();
        out.add(roles(o, recordedSerial));
        out.add(heartbeat(o.ha()));
        out.add(monitored(o.ha()));
        out.add(configurationSync(o));
        out.add(sessionPickup(o.config()));
        out.add(result(6, UNKNOWN, "NOT_EVALUATED", "basis", "NOT_READ"));
        out.add(versions(o));
        out.add(switchPreconditions(o));
        out.add(lastFailover(o));
        return out;
    }

    private static Result result(int check, String status, String reason, Object... pairs) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) evidence.put((String) pairs[i], pairs[i + 1]);
        return new Result(check, status, reason, evidence);
    }

    private static boolean ok(HaStatus ha) { return ha != null && ha.recognised(); }

    /** The one primary and one secondary, when the status lists exactly that (distinct serials, no conflicting role). */
    private static Optional<List<Member>> pair(HaStatus ha) {
        if (!ok(ha) || ha.memberConflict() || ha.members().size() != 2) return Optional.empty();
        long primaries = ha.members().stream().filter(m -> m.role().equals("PRIMARY")).count();
        long secondaries = ha.members().stream().filter(m -> m.role().equals("SECONDARY")).count();
        return primaries == 1 && secondaries == 1 ? Optional.of(ha.members()) : Optional.empty();
    }

    /** Check 1: mode, members, health, virtual clustering and the enrolled unit's identity. */
    static Result roles(Observation o, Optional<String> recordedSerial) {
        HaStatus ha = o.ha();
        if (!ok(ha)) return result(1, UNKNOWN, "HA_STATUS_NOT_RECOGNISED", "basis", BASIS);
        if (ha.mode().equals("STANDALONE")) return result(1, FAIL, "HA_OFF", "mode", "STANDALONE", "basis", BASIS);
        if (ha.mode().equals("A-A")) return result(1, UNKNOWN, UNSUPPORTED_MODE, "mode", "A-A", "basis", BASIS);
        if (!ha.mode().equals("A-P")) return result(1, UNKNOWN, "HA_MODE_NOT_RECOGNISED", "basis", BASIS);
        if (ha.vclusters() == null) return result(1, UNKNOWN, "VCLUSTER_NOT_REPORTED", "mode", "A-P", "basis", BASIS);
        if (ha.vclusters() != 1) return result(1, UNKNOWN, UNSUPPORTED_MODE, "mode", "A-P", "virtualClustering", true, "basis", BASIS);
        if (ha.memberConflict() || ha.members().isEmpty()) return result(1, UNKNOWN, "MEMBER_LINES_INCONSISTENT", "basis", BASIS);
        if (ha.members().size() < 2) return result(1, FAIL, "NOT_TWO_MEMBERS", "mode", "A-P", "memberCount", ha.members().size(), "basis", BASIS);
        Optional<List<Member>> members = pair(ha);
        if (members.isEmpty()) return result(1, FAIL, "ROLES_NOT_PRIMARY_SECONDARY", "mode", "A-P", "memberCount", ha.members().size(), "basis", BASIS);
        Set<String> serials = new LinkedHashSet<>();
        members.get().forEach(m -> serials.add(m.serial()));
        // Identity: compared locally, reported as MATCH / MISMATCH / MISSING / AMBIGUOUS only.
        if (recordedSerial.isEmpty() || recordedSerial.get().isBlank())
            return result(1, UNKNOWN, "RECORDED_SERIAL_MISSING", "identity", "MISSING", "basis", BASIS);
        if (!serials.contains(recordedSerial.get()))
            return result(1, FAIL, "IDENTITY_MISMATCH", "identity", "MISMATCH", "basis", BASIS);
        if (o.answeringSerial().isPresent() && !serials.contains(o.answeringSerial().get()))
            return result(1, UNKNOWN, "ANSWERING_UNIT_NOT_IN_CLUSTER", "identity", "AMBIGUOUS", "basis", BASIS);
        boolean answeredBySecondary = o.answeringSerial().isPresent() && members.get().stream()
            .anyMatch(m -> m.role().equals("SECONDARY") && m.serial().equals(o.answeringSerial().get()));
        if (answeredBySecondary)
            return result(1, UNKNOWN, "ANSWERED_BY_SECONDARY", "identity", "MATCH", "answeredByPrimary", false, "basis", BASIS);
        if (ha.health() == null) return result(1, UNKNOWN, "HA_HEALTH_NOT_REPORTED", "identity", "MATCH", "basis", BASIS);
        if (!ha.health().equalsIgnoreCase("OK"))
            return result(1, FAIL, "HA_HEALTH_NOT_OK", "identity", "MATCH", "healthOk", false, "basis", BASIS);
        return result(1, PASS, "HA_OK", "mode", "A-P", "virtualClustering", false, "memberCount", 2, "identity", "MATCH",
            "healthOk", true, "answeredByPrimary", o.answeringSerial().isPresent() ? true : "UNKNOWN", "basis", BASIS);
    }

    /** Check 2: every heartbeat device up, for both members. */
    static Result heartbeat(HaStatus ha) {
        return devices(2, ha, true);
    }

    /** Check 3: every monitored interface up for both members, and the same count on each. */
    static Result monitored(HaStatus ha) {
        return devices(3, ha, false);
    }

    private static Result devices(int check, HaStatus ha, boolean heartbeat) {
        if (!ok(ha)) return result(check, UNKNOWN, "HA_STATUS_NOT_RECOGNISED", "basis", BASIS);
        Optional<List<Member>> members = pair(ha);
        if (members.isEmpty()) return result(check, UNKNOWN, "MEMBERS_NOT_RESOLVED", "basis", BASIS);
        boolean section = heartbeat ? ha.hbdevSection() : ha.mondevSection();
        Map<String, List<Dev>> byMember = heartbeat ? ha.hbdev() : ha.mondev();
        String missing = heartbeat ? "HBDEV_LINES_MISSING" : "MONDEV_LINES_MISSING";
        if (!section) return result(check, UNKNOWN, missing, "basis", BASIS);
        Set<String> serials = new LinkedHashSet<>();
        members.get().forEach(m -> serials.add(m.serial()));
        if (!serials.containsAll(byMember.keySet())) return result(check, UNKNOWN, "DEVICE_BLOCK_FOR_UNKNOWN_MEMBER", "basis", BASIS);
        List<Integer> counts = new ArrayList<>();
        int up = 0, down = 0;
        for (String serial : serials) {
            List<Dev> devs = byMember.get(serial);
            counts.add(devs == null ? 0 : devs.size());
            if (devs == null) continue;
            for (Dev d : devs) if (d.up()) up++; else down++;
        }
        if (down > 0) return result(check, FAIL, heartbeat ? "HEARTBEAT_NOT_UP" : "MONITORED_INTERFACE_DOWN",
            heartbeat ? "heartbeatDown" : "monitoredDown", down, heartbeat ? "heartbeatUp" : "monitoredUp", up, "basis", BASIS);
        if (counts.contains(0)) {
            // A member with no line at all proves nothing; for monitored interfaces none anywhere is not a health statement either.
            return result(check, UNKNOWN, heartbeat ? missing : counts.stream().allMatch(c -> c == 0) ? "NO_MONITORED_INTERFACE" : missing,
                "basis", BASIS);
        }
        if (!heartbeat && !counts.get(0).equals(counts.get(1)))
            return result(check, FAIL, "MONITORED_COUNT_DIFFERS", "monitoredUp", up, "basis", BASIS);
        return result(check, PASS, heartbeat ? "HEARTBEAT_UP" : "MONITORED_UP", heartbeat ? "heartbeatUp" : "monitoredUp", up, "basis", BASIS);
    }

    /** Check 4: both members in-sync in the HA status, and the cluster checksums equal. */
    static Result configurationSync(Observation o) {
        HaStatus ha = o.ha();
        if (!ok(ha)) return result(4, UNKNOWN, "HA_STATUS_NOT_RECOGNISED", "basis", BASIS);
        Optional<List<Member>> members = pair(ha);
        if (members.isEmpty()) return result(4, UNKNOWN, "MEMBERS_NOT_RESOLVED", "basis", BASIS);
        int outOfSync = 0, inSync = 0, missing = 0;
        for (Member m : members.get()) {
            Boolean state = ha.inSync().get(m.serial());
            if (state == null) missing++; else if (state) inSync++; else outOfSync++;
        }
        if (outOfSync > 0) return result(4, FAIL, "SYNC_NOT_DONE", "outOfSync", outOfSync, "inSync", inSync, "basis", BASIS);
        if (missing > 0) return result(4, UNKNOWN, "CONFIGURATION_STATUS_MISSING", "inSync", inSync, "basis", BASIS);
        if (o.checksums() == null) return result(4, UNKNOWN, "CHECKSUM_NOT_READ", "inSync", 2, "checksums", "NOT_READ", "basis", BASIS);
        Checksums sums = o.checksums();
        Set<String> serials = new LinkedHashSet<>();
        members.get().forEach(m -> serials.add(m.serial()));
        if (!sums.recognised() || !sums.byMember().keySet().equals(serials))
            return result(4, UNKNOWN, "CHECKSUM_NOT_RECOGNISED", "inSync", 2, "checksums", "UNRECOGNISED", "basis", BASIS);
        List<Map<String, String>> two = new ArrayList<>(serials.stream().map(s -> sums.byMember().get(s)).toList());
        if (two.stream().anyMatch(m -> !m.containsKey("all")))
            return result(4, UNKNOWN, "CHECKSUM_NOT_RECOGNISED", "inSync", 2, "checksums", "UNRECOGNISED", "basis", BASIS);
        boolean equal = two.get(0).equals(two.get(1));
        if (!equal) {
            int differing = (int) two.get(0).entrySet().stream().filter(e -> !e.getValue().equals(two.get(1).get(e.getKey()))).count()
                + (int) two.get(1).keySet().stream().filter(k -> !two.get(0).containsKey(k)).count();
            return result(4, FAIL, "CHECKSUM_DIFFERS", "inSync", 2, "checksums", "MISMATCH", "differingEntries", differing, "basis", BASIS);
        }
        return result(4, PASS, "SYNC_DONE", "inSync", 2, "checksums", "MATCH", "basis", BASIS);
    }

    /** Check 5: session pickup enabled in the HA configuration; connectionless and NAT pickup are information. */
    static Result sessionPickup(HaConfig cfg) {
        if (cfg == null || !cfg.recognised()) return result(5, UNKNOWN, "HA_CONFIG_NOT_RECOGNISED");
        String pickup = cfg.settings().get("session-pickup");
        if (pickup == null) return result(5, UNKNOWN, "SESSION_PICKUP_NOT_REPORTED");
        String connectionless = word(cfg.settings().get("session-pickup-connectionless"));
        String nat = word(cfg.settings().get("session-pickup-nat"));
        if (pickup.equals("enable")) return result(5, PASS, "SESSION_PICKUP_ON", "sessionPickup", true,
            "connectionless", connectionless, "nat", nat);
        if (pickup.equals("disable")) return result(5, FAIL, "SESSION_PICKUP_OFF", "sessionPickup", false);
        return result(5, UNKNOWN, "SESSION_PICKUP_NOT_RECOGNISED");
    }
    private static String word(String value) {
        return "enable".equals(value) ? "enable" : "disable".equals(value) ? "disable" : "NOT_REPORTED";
    }

    /**
     * Check 7: version parity. The primary reports no firmware build for its secondary, so parity is evidenced indirectly
     * (PO decision 2026-11-17, docs/design/PO_DECISION_RECORD_2026_11_17_FGT_FAILOVER_READINESS_READS.md): an FGCP cluster
     * only forms and synchronises between members on the same firmware, so HA health OK plus both members reported
     * in-sync counts as parity, marked {@code DERIVED}. Out-of-sync is not proof of a version difference: UNKNOWN, and
     * check 4 already fails it. A health that is not OK, or missing sync states, stay UNKNOWN.
     */
    static Result versions(Observation o) {
        HaStatus ha = o.ha();
        boolean healthOk = ok(ha) && ha.health() != null && ha.health().equalsIgnoreCase("OK");
        String primary = o.versionParsed() ? "PARSED" : "MISSING";
        if (!healthOk) {
            return result(7, UNKNOWN, "SECONDARY_VERSION_NOT_OBSERVED", "primaryVersion", primary, "healthOk", false,
                "basis", BASIS);
        }
        long inSync = 0;
        boolean allKnown = ha.members().size() == 2;
        for (var m : ha.members()) {
            Boolean state = ha.inSync().get(m.serial());
            if (state == null) allKnown = false;
            else if (state) inSync++;
        }
        if (allKnown && inSync == 2) {
            return result(7, PASS, "PARITY_DERIVED_FROM_HA_SYNC", "primaryVersion", primary, "healthOk", true, "inSync", 2,
                "evidence", "DERIVED", "basis", BASIS);
        }
        return result(7, UNKNOWN, "SECONDARY_VERSION_NOT_OBSERVED", "primaryVersion", primary, "healthOk", true,
            "inSync", inSync, "basis", BASIS);
    }

    /** Check 8: override disabled, the secondary's HA uptime above the margin, no failover flag latched. */
    static Result switchPreconditions(Observation o) {
        HaConfig cfg = o.config();
        if (cfg == null || !cfg.recognised()) return result(8, UNKNOWN, "HA_CONFIG_NOT_RECOGNISED");
        String override = cfg.settings().get("override");
        if (override == null) return result(8, UNKNOWN, "OVERRIDE_NOT_REPORTED");
        if (override.equals("enable")) return result(8, FAIL, "OVERRIDE_ENABLED", "overrideDisabled", false);
        if (!override.equals("disable")) return result(8, UNKNOWN, "OVERRIDE_NOT_RECOGNISED");
        Long margin = cfg.settings().containsKey("ha-uptime-diff-margin") && cfg.settings().get("ha-uptime-diff-margin").matches("\\d{1,9}")
            ? Long.valueOf(cfg.settings().get("ha-uptime-diff-margin")) : null;
        Optional<Member> secondary = pair(o.ha()).flatMap(p -> p.stream().filter(m -> m.role().equals("SECONDARY")).findFirst());
        Long uptime = secondary.isPresent() && o.uptimes() != null && o.uptimes().recognised()
            ? o.uptimes().bySerial().get(secondary.get().serial()) : null;
        // The HA uptime against ha-uptime-diff-margin is information only (2026-11-17, first live run): its meaning in
        // `diagnose sys ha dump-by group` (absolute or relative uptime, unit) is not measured, and it only matters to the
        // reset-uptime switch, which is not built. Never FAIL or UNKNOWN on it until measured.
        Object uptimeAboveMargin = uptime == null || margin == null ? "UNKNOWN" : (Object) (uptime > margin);
        if (o.flag() == Flag.SET) return result(8, FAIL, "FAILOVER_FLAG_LATCHED", "overrideDisabled", true, "failoverFlagLatched", true);
        if (o.flag() == null)
            return result(8, UNKNOWN, "SWITCH_READS_NOT_AVAILABLE", "overrideDisabled", true, "failoverFlag", "NOT_READ");
        if (o.flag() == Flag.UNRECOGNISED)
            return result(8, UNKNOWN, "FAILOVER_FLAG_NOT_RECOGNISED", "overrideDisabled", true);
        return result(8, PASS, "SWITCH_PRECONDITIONS_OK", "overrideDisabled", true, "failoverFlagLatched", false,
            "uptimeAboveMargin", uptimeAboveMargin, "uptimeSemantics", "UNMEASURED_INFORMATION", "basis", BASIS);
    }

    /** Check 9 (information only): the newest role change in the HA status against the device's own clock. */
    static Result lastFailover(Observation o) {
        if (!ok(o.ha()) || o.ha().lastChange().isEmpty()) return result(9, UNKNOWN, "HISTORY_NOT_REPORTED", "basis", BASIS);
        if (o.systemTime().isEmpty()) return result(9, UNKNOWN, "DEVICE_CLOCK_NOT_READ", "basis", BASIS);
        Duration since = Duration.between(o.ha().lastChange().get(), o.systemTime().get());
        if (since.isNegative()) return result(9, UNKNOWN, "TIME_IN_THE_FUTURE", "basis", BASIS);
        boolean older = since.compareTo(Duration.ofHours(6)) > 0;
        return result(9, older ? PASS : WARN, older ? "OLDER_THAN_6H" : "WITHIN_6H", "olderThanSixHours", older, "basis", BASIS);
    }

    /** READY when every blocking check PASSes, NOT_READY when any FAILs, UNKNOWN otherwise. */
    static String outcome(List<Result> results) {
        List<Result> blocking = results.stream().filter(Result::blocking).toList();
        if (blocking.stream().anyMatch(r -> FAIL.equals(r.status()))) return "NOT_READY";
        if (blocking.size() == 7 && blocking.stream().allMatch(r -> PASS.equals(r.status()))) return "READY";
        return "UNKNOWN";
    }
}
