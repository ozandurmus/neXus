package com.securityexpert.nexus.ui2.worker.failover;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.HaPairIdentity;

/**
 * Cisco ASA failover readiness: pure parsers of the four approved reads and the nine checks of
 * docs/design/PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md. Output shapes are those measured on a live pair
 * (docs/design/ASA_FAILOVER_MEASUREMENT_2026_11_13.md).
 *
 * <p>Raw-evidence law: a parser returns structured values to its caller in memory; a check result carries only derived
 * evidence (booleans, counts, enums, MATCH / MISMATCH / MISSING) and a reason code, never a serial, address, interface
 * name, host name or version value. Serials and versions are compared with exact string equality only.
 */
final class AsaFailoverChecks {
    private AsaFailoverChecks() {}

    static final String PASS = "PASS", FAIL = "FAIL", UNKNOWN = "UNKNOWN", WARN = "WARN";
    /** A check's result: status, one reason code, and derived evidence of booleans, counts and enums only. */
    record Result(int check, String status, String reason, Map<String, Object> evidence) {
        Result {
            evidence = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        }
        boolean blocking() { return check != 9; }
    }

    // ---- show failover ------------------------------------------------------------------------------------------

    /** One host's line ("This host: Primary - Active"): the unit label and the state words. */
    record Host(String label, String state) {}
    /** The interface status lines under one host, classified. */
    record Interfaces(int monitoredNormal, int notMonitored, int bad, int unrecognised) {
        int total() { return monitoredNormal + notMonitored + bad + unrecognised; }
    }
    /** One row of the stateful table (cumulative counters). */
    record Row(String name, long xmit, long xerr, long rcv, long rerr) {}
    /** The parsed {@code show failover}; absent parts are null / empty and make their checks UNKNOWN. */
    record Show(Boolean failoverOn, boolean groupLines, Host thisHost, Host otherHost, Interfaces thisInterfaces,
            Interfaces otherInterfaces, String lanLink, String stateLink, String versionOurs, String versionMate,
            String serialOurs, String serialMate, List<Row> table, boolean tableHeader) {}

    private static final Pattern FAILOVER_ON = Pattern.compile("(?m)^\\s*Failover (On|Off)\\s*$");
    private static final Pattern GROUP = Pattern.compile("(?im)^\\s*(Group \\d+\\b|Failover group\\b)");
    private static final Pattern HOST = Pattern.compile("(?m)^\\s*(This|Other) host:\\s*(Primary|Secondary)\\s*-\\s*(\\S.*?)\\s*$");
    private static final Pattern INTERFACE = Pattern.compile("^\\s*Interface\\s+\\S+\\s+\\([^)]*\\):\\s*(\\S.*?)\\s*$");
    private static final Pattern LAN = Pattern.compile("(?m)^\\s*Failover LAN Interface:\\s*\\S.*?\\((\\w+)\\)\\s*$");
    private static final Pattern STATE_LINK = Pattern.compile("(?m)^\\s*Link\\s*:\\s*\\S.*?\\((\\w+)\\)\\s*$");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*Version:\\s*Ours\\s+(\\S+),\\s*Mate\\s+(\\S+)\\s*$");
    private static final Pattern SERIAL = Pattern.compile("(?m)^\\s*Serial Number:\\s*Ours\\s+(\\S+),\\s*Mate\\s+(\\S+)\\s*$");
    private static final Pattern TABLE_HEADER = Pattern.compile("^\\s*Stateful\\s+Obj\\s+xmit\\s+xerr\\s+rcv\\s+rerr\\s*$");
    private static final Pattern ROW = Pattern.compile("^\\s*(\\S.*?)\\s+(\\d{1,18})\\s+(\\d{1,18})\\s+(\\d{1,18})\\s+(\\d{1,18})\\s*$");

    static Show parseShow(String output) {
        if (output == null || output.isBlank()) {
            return new Show(null, false, null, null, null, null, null, null, null, null, null, null, List.of(), false);
        }
        Matcher on = FAILOVER_ON.matcher(output);
        Boolean failoverOn = on.find() ? "On".equals(on.group(1)) : null;
        Host thisHost = null, otherHost = null;
        List<String> thisLines = new ArrayList<>(), otherLines = new ArrayList<>();
        List<Row> table = new ArrayList<>();
        boolean tableHeader = false, inTable = false;
        int section = 0; // 1 = this host, 2 = other host, 0 = neither
        for (String line : output.split("\\R")) {
            Matcher host = HOST.matcher(line);
            if (host.matches()) {
                Host parsed = new Host(host.group(2), host.group(3).replaceAll("\\s+", " "));
                if ("This".equals(host.group(1))) { thisHost = parsed; section = 1; } else { otherHost = parsed; section = 2; }
                continue;
            }
            if (line.contains("Stateful Failover Logical Update Statistics")) { section = 0; continue; }
            if (TABLE_HEADER.matcher(line).matches()) { tableHeader = true; inTable = true; continue; }
            if (inTable) {
                if (line.isBlank() || line.contains("Logical Update Queue Information")) { inTable = false; continue; }
                Matcher row = ROW.matcher(line);
                if (row.matches()) {
                    table.add(new Row(row.group(1), Long.parseLong(row.group(2)), Long.parseLong(row.group(3)),
                        Long.parseLong(row.group(4)), Long.parseLong(row.group(5))));
                } else {
                    // A row that does not parse: mark the table unusable rather than skipping it.
                    table.add(null);
                }
                continue;
            }
            Matcher iface = INTERFACE.matcher(line);
            if (iface.matches()) {
                if (section == 1) thisLines.add(iface.group(1));
                else if (section == 2) otherLines.add(iface.group(1));
            }
        }
        Matcher lan = LAN.matcher(output);
        Matcher link = STATE_LINK.matcher(output);
        Matcher version = VERSION.matcher(output);
        Matcher serial = SERIAL.matcher(output);
        boolean hasVersion = version.find(), hasSerial = serial.find();
        return new Show(failoverOn, GROUP.matcher(output).find(), thisHost, otherHost,
            thisHost == null ? null : classify(thisLines), otherHost == null ? null : classify(otherLines),
            lan.find() ? lan.group(1).toLowerCase(Locale.ROOT) : null,
            link.find() ? link.group(1).toLowerCase(Locale.ROOT) : null,
            hasVersion ? version.group(1) : null, hasVersion ? version.group(2) : null,
            hasSerial ? serial.group(1) : null, hasSerial ? serial.group(2) : null, table, tableHeader);
    }

    private static Interfaces classify(List<String> statuses) {
        int ok = 0, notMonitored = 0, bad = 0, other = 0;
        for (String status : statuses) {
            String lower = status.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (lower.equals("normal (monitored)")) ok++;
            else if (lower.equals("normal (not-monitored)")) notMonitored++;
            else if (lower.contains("failed") || lower.contains("no link") || lower.contains("link down")
                    || lower.contains("testing") || lower.contains("unknown") || lower.contains("waiting")) bad++;
            else other++;
        }
        return new Interfaces(ok, notMonitored, bad, other);
    }

    // ---- show failover state ------------------------------------------------------------------------------------

    /** Configuration-sync lines of {@code ====Configuration State===}, classified; last-failure columns are history. */
    record SyncState(boolean sectionPresent, int done, int standbyDone, int notDone, int unrecognised) {}

    static SyncState parseState(String output) {
        if (output == null) return new SyncState(false, 0, 0, 0, 0);
        boolean present = false, inSection = false;
        int done = 0, standbyDone = 0, notDone = 0, other = 0;
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (text.startsWith("====")) {
                inSection = text.toLowerCase(Locale.ROOT).contains("configuration state");
                present |= inSection;
                continue;
            }
            if (!inSection || text.isEmpty()) continue;
            String lower = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (lower.equals("sync done")) done++;
            else if (lower.equals("sync done - standby")) standbyDone++;
            else if (lower.startsWith("cold standby") || lower.startsWith("sync config") || lower.startsWith("sync file system")
                    || lower.startsWith("bulk sync")) notDone++;
            else other++;
        }
        return new SyncState(present, done, standbyDone, notDone, other);
    }

    // ---- show failover history ----------------------------------------------------------------------------------

    private static final Pattern STAMP = Pattern.compile(
        "^\\s*(\\d{1,2}):(\\d{2}):(\\d{2})\\s+(\\S+)\\s+([A-Z][a-z]{2})\\s+(\\d{1,2})\\s+(\\d{4})\\s*$");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM d uuuu", Locale.ENGLISH);

    /** The history entries' timestamps in answer order (oldest first); an unresolvable zone gives an empty instant. */
    record History(boolean header, int entries, List<Optional<Instant>> stamps) {}

    static History parseHistory(String output) {
        if (output == null) return new History(false, 0, List.of());
        List<Optional<Instant>> stamps = new ArrayList<>();
        boolean header = false;
        for (String line : output.split("\\R")) {
            if (line.contains("From State") && line.contains("To State")) header = true;
            Matcher m = STAMP.matcher(line);
            if (m.matches()) stamps.add(instant(m));
        }
        return new History(header, stamps.size(), stamps);
    }

    private static Optional<Instant> instant(Matcher m) {
        try {
            String zone = m.group(4);
            ZoneOffset offset;
            if (zone.equals("UTC") || zone.equals("GMT")) offset = ZoneOffset.UTC;
            else if (!zone.matches("[+-]\\d{2}(?::?\\d{2})?")) return Optional.empty();
            else {
                String digits = zone.substring(1).replace(":", "");
                int hours = Integer.parseInt(digits.substring(0, 2));
                int minutes = digits.length() > 2 ? Integer.parseInt(digits.substring(2)) : 0;
                offset = ZoneOffset.ofHoursMinutes(zone.charAt(0) == '-' ? -hours : hours, zone.charAt(0) == '-' ? -minutes : minutes);
            }
            var date = java.time.LocalDate.parse(m.group(5) + " " + Integer.parseInt(m.group(6)) + " " + m.group(7), MONTH);
            return Optional.of(LocalDateTime.of(date, java.time.LocalTime.of(Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)))).toInstant(offset));
        } catch (java.time.DateTimeException invalid) {
            return Optional.empty();
        }
    }

    // ---- show conn count ----------------------------------------------------------------------------------------

    private static final Pattern CONN = Pattern.compile("(\\d{1,18}) in use, (\\d{1,18}) most used");

    /** "N in use, M most used": the in-use count, or empty when the answer has another shape. */
    static Optional<Long> inUse(String output) {
        if (output == null) return Optional.empty();
        Matcher m = CONN.matcher(output);
        return m.find() ? Optional.of(Long.parseLong(m.group(1))) : Optional.empty();
    }

    // ---- one observer -------------------------------------------------------------------------------------------

    /** Everything one member (observer) answered: two {@code show failover} samples and the other reads. */
    record Observer(Show first, Show second, SyncState state, History history, Optional<Long> inUse) {}

    // ---- the nine checks ----------------------------------------------------------------------------------------

    static final String UNSUPPORTED_MODE = "UNSUPPORTED_MODE";

    static List<Result> evaluate(Observer a, Observer b, Optional<HaPairIdentity> recorded, Instant now) {
        List<Result> out = new ArrayList<>();
        out.add(roles(a.first(), b.first()));
        out.add(identity(a.first(), b.first(), recorded));
        out.add(links(a.first(), b.first()));
        out.add(interfaces(a.first(), b.first()));
        out.add(configurationSync(a.state(), b.state()));
        out.add(replication(a, b));
        out.add(sessions(a, b));
        out.add(versions(a.first(), b.first()));
        out.add(lastFailover(a.history(), b.history(), now));
        return out;
    }

    private static Result result(int check, String status, String reason, Object... pairs) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) evidence.put((String) pairs[i], pairs[i + 1]);
        return new Result(check, status, reason, evidence);
    }

    private static boolean parsed(Show s) { return s != null && s.failoverOn() != null; }

    /** Check 1: failover state and roles. */
    static Result roles(Show a, Show b) {
        if (!parsed(a) || !parsed(b)) return result(1, UNKNOWN, "FAILOVER_STATE_NOT_RECOGNISED");
        if (a.groupLines() || b.groupLines()) return result(1, UNKNOWN, UNSUPPORTED_MODE, "failoverGroups", true);
        if (!a.failoverOn() || !b.failoverOn()) return result(1, FAIL, "FAILOVER_OFF", "failoverOn", false);
        if (a.thisHost() == null || a.otherHost() == null || b.thisHost() == null || b.otherHost() == null)
            return result(1, UNKNOWN, "HOST_LINES_MISSING");
        boolean oneActive = ("Active".equals(a.thisHost().state()) && "Standby Ready".equals(b.thisHost().state()))
            || ("Standby Ready".equals(a.thisHost().state()) && "Active".equals(b.thisHost().state()));
        boolean mirrored = a.thisHost().equals(b.otherHost()) && b.thisHost().equals(a.otherHost());
        if (!mirrored) return result(1, FAIL, "VIEWS_DISAGREE", "failoverOn", true, "viewsMirrored", false, "oneActiveOneStandbyReady", oneActive);
        if (!oneActive) return result(1, FAIL, "ROLES_NOT_ACTIVE_STANDBY_READY", "failoverOn", true, "viewsMirrored", true, "oneActiveOneStandbyReady", false);
        return result(1, PASS, "ROLES_OK", "failoverOn", true, "viewsMirrored", true, "oneActiveOneStandbyReady", true);
    }

    /** Check 2: pair identity (serials) against the recorded pair. */
    static Result identity(Show a, Show b, Optional<HaPairIdentity> recorded) {
        if (!parsed(a) || !parsed(b) || a.serialOurs() == null || b.serialOurs() == null
                || a.serialOurs().isBlank() || a.serialMate().isBlank() || b.serialOurs().isBlank() || b.serialMate().isBlank())
            return result(2, UNKNOWN, "SERIAL_MISSING", "identity", "MISSING");
        if (recorded.isEmpty()) return result(2, UNKNOWN, "NO_RECORDED_PAIR", "identity", "MISSING");
        var pair = recorded.get();
        List<String> reported = List.of(a.serialOurs(), a.serialMate(), b.serialOurs(), b.serialMate());
        boolean allRecorded = reported.stream().allMatch(s -> s.equals(pair.serialLow()) || s.equals(pair.serialHigh()));
        if (!allRecorded) return result(2, FAIL, "SERIAL_NOT_IN_RECORDED_PAIR", "identity", "MISMATCH");
        boolean mirrored = a.serialOurs().equals(b.serialMate()) && a.serialMate().equals(b.serialOurs())
            && !a.serialOurs().equals(a.serialMate());
        if (!mirrored) return result(2, UNKNOWN, "RELATIONSHIP_INCONSISTENT", "identity", "AMBIGUOUS", "mirrored", false);
        // Mirrored, distinct, and each one of the recorded two: the set equals the recorded set exactly.
        return result(2, PASS, "IDENTITY_MATCH", "identity", "MATCH", "mirrored", true);
    }

    /** Check 3: the failover LAN link and the stateful link, both observers. */
    static Result links(Show a, Show b) {
        if (!parsed(a) || !parsed(b)) return result(3, UNKNOWN, "LINK_LINES_MISSING");
        List<String> statuses = new ArrayList<>(java.util.Arrays.asList(a.lanLink(), a.stateLink(), b.lanLink(), b.stateLink()));
        boolean down = statuses.stream().anyMatch(s -> s != null && !s.equals("up"));
        int missing = (int) statuses.stream().filter(java.util.Objects::isNull).count();
        if (down) return result(3, FAIL, "LINK_NOT_UP", "linksNotUp", (int) statuses.stream().filter(s -> s != null && !s.equals("up")).count(), "linksMissing", missing);
        if (missing > 0) return result(3, UNKNOWN, "LINK_LINES_MISSING", "linksMissing", missing);
        return result(3, PASS, "LINKS_UP", "linksUp", 4);
    }

    /** Check 4: monitored interfaces under both hosts on both observers. */
    static Result interfaces(Show a, Show b) {
        if (!parsed(a) || !parsed(b)) return result(4, UNKNOWN, "INTERFACE_LINES_MISSING");
        List<Interfaces> all = new ArrayList<>();
        for (Interfaces i : java.util.Arrays.asList(a.thisInterfaces(), a.otherInterfaces(), b.thisInterfaces(), b.otherInterfaces()))
            if (i != null) all.add(i);
        int ok = all.stream().mapToInt(Interfaces::monitoredNormal).sum(), notMonitored = all.stream().mapToInt(Interfaces::notMonitored).sum();
        int bad = all.stream().mapToInt(Interfaces::bad).sum(), other = all.stream().mapToInt(Interfaces::unrecognised).sum();
        if (bad > 0) return result(4, FAIL, "INTERFACE_NOT_NORMAL", "monitoredNormal", ok, "notMonitored", notMonitored, "notNormal", bad);
        boolean noLines = all.size() < 4 || all.stream().anyMatch(i -> i.total() == 0);
        if (noLines) return result(4, UNKNOWN, "INTERFACE_LINES_MISSING", "monitoredNormal", ok, "notMonitored", notMonitored);
        if (other > 0) return result(4, UNKNOWN, "INTERFACE_STATUS_NOT_RECOGNISED", "monitoredNormal", ok, "unrecognised", other);
        // Fail-closed: a pair whose every interface line is Not-Monitored proves nothing about interface health.
        if (ok == 0) return result(4, UNKNOWN, "NO_MONITORED_INTERFACE", "monitoredNormal", ok, "notMonitored", notMonitored);
        return result(4, PASS, "INTERFACES_NORMAL", "monitoredNormal", ok, "notMonitored", notMonitored);
    }

    /** Check 5: configuration sync, each observer's {@code ====Configuration State===} section. */
    static Result configurationSync(SyncState a, SyncState b) {
        if (a == null || b == null || !a.sectionPresent() || !b.sectionPresent())
            return result(5, UNKNOWN, "CONFIGURATION_STATE_MISSING");
        int notDone = a.notDone() + b.notDone();
        if (notDone > 0) return result(5, FAIL, "SYNC_NOT_DONE", "notDone", notDone);
        boolean empty = a.done() + a.standbyDone() == 0 || b.done() + b.standbyDone() == 0;
        if (empty) return result(5, UNKNOWN, "CONFIGURATION_STATE_MISSING");
        if (a.unrecognised() + b.unrecognised() > 0) return result(5, UNKNOWN, "SYNC_STATE_NOT_RECOGNISED", "unrecognised", a.unrecognised() + b.unrecognised());
        return result(5, PASS, "SYNC_DONE", "observersSyncDone", 2);
    }

    /** Check 6: stateful replication, the two samples of each observer. */
    static Result replication(Observer a, Observer b) {
        Optional<Boolean> aActive = active(a.first()), bActive = active(b.first());
        if (!usable(a.first()) || !usable(a.second()) || !usable(b.first()) || !usable(b.second()))
            return result(6, UNKNOWN, "STATEFUL_TABLE_NOT_RECOGNISED");
        int increased = 0;
        for (Observer o : List.of(a, b)) {
            if (o.first().table().size() != o.second().table().size()) return result(6, UNKNOWN, "STATEFUL_TABLE_NOT_RECOGNISED");
            for (int i = 0; i < o.first().table().size(); i++) {
                Row x = o.first().table().get(i), y = o.second().table().get(i);
                if (!x.name().equals(y.name())) return result(6, UNKNOWN, "STATEFUL_TABLE_NOT_RECOGNISED");
                if (y.xerr() < x.xerr() || y.rerr() < x.rerr() || y.xmit() < x.xmit() || y.rcv() < x.rcv())
                    return result(6, UNKNOWN, "COUNTER_DECREASED");
                if (y.xerr() > x.xerr()) increased++;
                if (y.rerr() > x.rerr()) increased++;
            }
        }
        if (increased > 0) return result(6, FAIL, "ERROR_COUNTER_INCREASED", "increasedCounters", increased);
        // Positive cumulative xmit (active) and rcv (standby) when connections are in use.
        Optional<Observer> active = aActive.orElse(false) ? Optional.of(a) : bActive.orElse(false) ? Optional.of(b) : Optional.empty();
        Optional<Observer> standby = active.isEmpty() ? Optional.empty() : active.get() == a ? Optional.of(b) : Optional.of(a);
        if (active.isEmpty() || active.get().inUse().isEmpty()) return result(6, UNKNOWN, "ACTIVE_OR_CONNECTIONS_UNKNOWN", "increasedCounters", 0);
        if (active.get().inUse().get() > 0) {
            Row generalActive = general(active.get().second()), generalStandby = general(standby.get().second());
            if (generalActive == null || generalStandby == null) return result(6, UNKNOWN, "STATEFUL_TABLE_NOT_RECOGNISED", "increasedCounters", 0);
            boolean activeSends = generalActive.xmit() > 0, standbyReceives = generalStandby.rcv() > 0;
            if (!activeSends || !standbyReceives)
                return result(6, FAIL, "NO_REPLICATION_TRAFFIC", "increasedCounters", 0, "activeTransmits", activeSends, "standbyReceives", standbyReceives);
        }
        return result(6, PASS, "REPLICATION_OK", "increasedCounters", 0, "rowsCompared", a.first().table().size() + b.first().table().size());
    }

    private static Row general(Show show) {
        return show.table().stream().filter(r -> r.name().equalsIgnoreCase("General")).findFirst().orElse(null);
    }
    private static boolean usable(Show s) {
        return parsed(s) && s.tableHeader() && !s.table().isEmpty() && s.table().stream().allMatch(java.util.Objects::nonNull);
    }
    private static Optional<Boolean> active(Show s) {
        return !parsed(s) || s.thisHost() == null ? Optional.empty() : Optional.of("Active".equals(s.thisHost().state()));
    }

    /** Check 7: the standby's connections against the active's. */
    static Result sessions(Observer a, Observer b) {
        Optional<Boolean> aActive = active(a.first()), bActive = active(b.first());
        if (a.inUse().isEmpty() || b.inUse().isEmpty()) return result(7, UNKNOWN, "CONNECTION_COUNT_NOT_RECOGNISED");
        boolean aIsActive = aActive.orElse(false), bIsActive = bActive.orElse(false);
        if (aIsActive == bIsActive) return result(7, UNKNOWN, "ACTIVE_MEMBER_NOT_DETERMINED");
        long active = aIsActive ? a.inUse().get() : b.inUse().get();
        long standby = aIsActive ? b.inUse().get() : a.inUse().get();
        if (active == 0) return result(7, PASS, "ZERO_ACTIVE", "activeCount", 0L, "standbyCount", standby);
        boolean pass;
        String rule;
        if (active >= 10_000) {
            rule = "RATIO_80";
            pass = standby * 100 >= active * 80;
        } else {
            rule = "LOW_VOLUME_ABSOLUTE_OR_RATIO";
            pass = Math.abs(active - standby) < 2_000 || standby * 100 >= active * 50;
        }
        return result(7, pass ? PASS : FAIL, pass ? "CARRIED" : "BELOW_TOLERANCE", "activeCount", active, "standbyCount", standby,
            "rule", rule);
    }

    /** Check 8: version parity, exact equality on each observer and mirrored across the two. */
    static Result versions(Show a, Show b) {
        if (!parsed(a) || !parsed(b) || a.versionOurs() == null || b.versionOurs() == null)
            return result(8, UNKNOWN, "VERSION_MISSING", "versions", "MISSING");
        boolean equal = a.versionOurs().equals(a.versionMate()) && b.versionOurs().equals(b.versionMate())
            && a.versionOurs().equals(b.versionMate()) && a.versionMate().equals(b.versionOurs());
        return equal ? result(8, PASS, "VERSIONS_MATCH", "versions", "MATCH")
            : result(8, FAIL, "VERSIONS_DIFFER", "versions", "MISMATCH");
    }

    /** Check 9 (information only): the newest history entry (the last one; the list is oldest first). */
    static Result lastFailover(History a, History b, Instant now) {
        if (a == null || b == null || a.entries() == 0 && b.entries() == 0)
            return result(9, UNKNOWN, "HISTORY_EMPTY_OR_NOT_RECOGNISED", "entries", 0);
        Instant newest = null;
        for (History h : List.of(a, b)) {
            if (h.entries() == 0) continue;
            Optional<Instant> last = h.stamps().get(h.stamps().size() - 1);
            if (last.isEmpty()) return result(9, UNKNOWN, "TIME_ZONE_NOT_RESOLVED", "entries", a.entries() + b.entries());
            if (newest == null || last.get().isAfter(newest)) newest = last.get();
        }
        if (newest.isAfter(now)) return result(9, UNKNOWN, "TIME_IN_THE_FUTURE", "entries", a.entries() + b.entries());
        boolean older = Duration.between(newest, now).compareTo(Duration.ofHours(6)) > 0;
        return result(9, older ? PASS : WARN, older ? "OLDER_THAN_6H" : "WITHIN_6H", "entries", a.entries() + b.entries(),
            "olderThanSixHours", older);
    }

    /** The run's outcome: READY when checks 1-8 PASS, NOT_READY when any of them FAILs, UNKNOWN otherwise. */
    static String outcome(List<Result> results) {
        List<Result> blocking = results.stream().filter(Result::blocking).toList();
        if (blocking.stream().anyMatch(r -> FAIL.equals(r.status()))) return "NOT_READY";
        if (blocking.size() == 8 && blocking.stream().allMatch(r -> PASS.equals(r.status()))) return "READY";
        return "UNKNOWN";
    }
}
