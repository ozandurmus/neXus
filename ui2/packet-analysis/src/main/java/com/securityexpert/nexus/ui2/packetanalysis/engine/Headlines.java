package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFormat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyMath;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyRound;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reference {@code headlines.py}: explain measured findings without turning uncertainty into a numeric claim. */
public final class Headlines {
    private Headlines() {
    }

    /** {@code time_labels(ts, zone)}; a null zone is the host local zone, as {@code datetime.astimezone()}. */
    public static Map<String, Object> timeLabels(double ts, String zone) {
        long micros = PyTime.fromTimestampMicros(PyRound.round(ts, 3));
        Instant instant = Instant.ofEpochSecond(PyMath.floorDiv(micros, 1_000_000L), PyMath.mod(micros, 1_000_000L) * 1000);
        ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        ZonedDateTime local = instant.atZone(zone != null && !zone.isEmpty() ? ZoneId.of(zone) : ZoneId.systemDefault());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("local", iso(local) + " local");
        out.put("utc", iso(utc).replace("+00:00", " UTC"));
        return out;
    }

    /** {@code isoformat(sep=" ", timespec="milliseconds")} of an aware datetime. */
    static String iso(ZonedDateTime t) {
        int offset = t.getOffset().getTotalSeconds();
        String sign = offset < 0 ? "-" : "+";
        int abs = Math.abs(offset);
        String tz = sign + PyFormat.format((long) (abs / 3600), "02d") + ":" + PyFormat.format((long) (abs % 3600 / 60), "02d");
        if (abs % 60 != 0) {
            tz += ":" + PyFormat.format((long) (abs % 60), "02d");
        }
        return PyFormat.format((long) t.getYear(), "04d") + "-" + PyFormat.format((long) t.getMonthValue(), "02d") + "-"
                + PyFormat.format((long) t.getDayOfMonth(), "02d") + " " + PyFormat.format((long) t.getHour(), "02d") + ":"
                + PyFormat.format((long) t.getMinute(), "02d") + ":" + PyFormat.format((long) t.getSecond(), "02d") + "."
                + PyFormat.format((long) (t.getNano() / 1_000_000), "03d") + tz;
    }

    private static String fmt(Object v, String spec) {
        return PyFormat.format(Segments.num(v), spec);
    }

    @SuppressWarnings("unchecked")
    static void addHeadlines(EngineContext ctx) {
        Db db = ctx.db;
        Topology topology = ctx.topology;
        Double end = ctx.end;
        String lossTypes = Sql.get("headlines", "LOSS_TYPES");
        Map<String, Map<String, Object>> lookup = new LinkedHashMap<>();
        for (Map<String, Object> s : ctx.segments) {
            lookup.put((String) s.get("id"), s);
        }
        Set<Object> seen = new HashSet<>();
        for (Map<String, Object> s : ctx.segments) {
            s.put("finding_ids", new ArrayList<>());
        }
        for (Map<String, Object> finding : ctx.findings) {
            Map<String, Object> s = lookup.get((String) finding.get("hop"));
            ((List<Object>) s.get("finding_ids")).add(finding.get("id"));
            Map<String, Object> metrics = (Map<String, Object>) finding.get("metrics");
            long n = (Long) metrics.get("count");
            String type = (String) finding.get("type");
            String human = type.replace('_', ' ');
            String shortText = Character.toUpperCase(human.charAt(0)) + human.substring(1).toLowerCase() + ": " + n + " "
                    + (n == 1 ? "event" : "events");
            Object wait = metrics.get("max_recovery_ms");
            if (wait != null) {
                shortText += "; max recovery " + fmt(wait, ".2f") + " ms";
            }
            finding.put("headline", shortText + ".");
            double t0 = ((Number) ((List<Object>) finding.get("time_range")).get(0)).doubleValue();
            finding.put("time_labels", timeLabels(t0, topology.reportTimezone));
            if (seen.contains(finding.get("hop"))) {
                continue;
            }
            seen.add(finding.get("hop"));
            s.put("severity", finding.get("severity"));
            String a = (String) s.get("point_a");
            String b = (String) s.get("point_b");
            String direction = (String) s.get("direction");
            Map<String, Object> labels = timeLabels(t0, topology.reportTimezone);
            String clock = s.get("offset_uncertainty_ms") != null
                    ? "Clock-corrected times are estimates; offset uncertainty ±" + fmt(s.get("offset_uncertainty_ms"), ".3f")
                            + " ms."
                    : "Clock uncertainty is unverified; times depend on the supplied or unavailable clock correction.";
            String prefix = labels.get("local") + " / " + labels.get("utc");
            String between = ((String) s.get("label")).replace(" → ", " and ");
            String text;
            if (type.equals("confirmed_device_drop")) {
                finding.put("summary", n + " confirmed device drop observations at " + finding.get("device")
                        + "; evidence stage " + finding.get("evidence_stage") + ".");
                text = "From " + prefix + ", " + n + " device drop " + (n == 1 ? "observation is" : "observations are")
                        + " confirmed at " + finding.get("device") + " by " + finding.get("evidence_stage")
                        + " evidence. The specific rule or policy cause is not inferred.";
            } else if (type.equals("handshake_blocked")) {
                text = "Handshake blocked between " + between + ", first observed at " + prefix + "; cause unknown.";
            } else if (type.equals("capture_miss")) {
                text = "From " + prefix + " onward, " + metrics.get("count") + " capture misses were observed between " + between
                        + ". These are capture-quality findings, not network loss.";
            } else if (type.equals("unknown")) {
                Object reason = s.get("reason");
                text = "At " + prefix + ", missing appearances between " + between + " are inconclusive: "
                        + (Evidence.truthy(reason) ? reason : "insufficient remaining coverage") + ".";
            } else {
                Object first = db.scalar(Sql.fmt("headlines", "first_loss", "LOSS_TYPES", lossTypes), a, b, direction);
                if (first == null) {
                    text = "From " + prefix + " onward, " + metrics.get("count")
                            + " control-segment disappearances were observed between " + between + "; cause unknown.";
                } else {
                    double firstTs = (Double) first;
                    labels = timeLabels(firstTs, topology.reportTimezone);
                    prefix = labels.get("local") + " / " + labels.get("utc");
                    Object[] counts = db.one(Sql.fmt("headlines", "loss_counts", "LOSS_TYPES", lossTypes), topology.stallMs,
                            topology.stallMs, a, b, direction, firstTs, end, end);
                    long lost = (Long) counts[0];
                    long quick = (Long) counts[1];
                    long stalls = (Long) counts[2];
                    Object maxStall = counts[3];
                    Object[] denominatorParams = {a, direction, firstTs, end, end};
                    long denominator = NeighbourScope.excludes(ctx, a, b, direction)
                            ? db.count(NeighbourScope.filtered(Sql.get("headlines", "denominator")),
                                    NeighbourScope.params(denominatorParams, a, b, direction))
                            : db.count(Sql.get("headlines", "denominator"), denominatorParams);
                    Double rate = denominator != 0 && s.get("loss_percent") != null
                            ? PyMath.trueDivide(100 * lost, denominator) : null;
                    List<String> path = topology.path(direction);
                    int index = path.indexOf(a);
                    List<Map<String, Object>> prior = new ArrayList<>();
                    for (int i = 0; i < index; i++) {
                        prior.add(lookup.get(direction + ":" + path.get(i) + ":" + path.get(i + 1)));
                    }
                    String upstream = "No earlier capture point is available.";
                    String status = "unavailable";
                    if (!prior.isEmpty()) {
                        upstream = "Upstream comparison is inconclusive.";
                        status = "unknown";
                        boolean clean = true;
                        for (Map<String, Object> p : prior) {
                            Object ratio = p.get("eligible_ratio");
                            if (!(p.get("reason") == null && ratio instanceof Double d && d == 1.0)) {
                                clean = false;
                            }
                        }
                        if (clean) {
                            long previous = db.count(Sql.fmt("headlines", "previous", "LOSS_TYPES", lossTypes), direction,
                                    new ArrayList<>(path.subList(0, index)), firstTs, end, end);
                            String label = null;
                            for (Topology.Point p : topology.points) {
                                if (p.id().equals(a)) {
                                    label = p.label();
                                    break;
                                }
                            }
                            upstream = previous == 0 ? "No loss was observed before " + label + " in the monitored path."
                                    : previous + " loss events were also observed earlier in the monitored path.";
                            status = previous == 0 ? "none_observed" : "loss_observed";
                        }
                    }
                    String rateText = rate != null ? PyFormat.format(rate, ".2f") + "%"
                            : "an unknown proportion (" + (Evidence.truthy(s.get("reason")) ? s.get("reason")
                                    : "no eligible denominator") + ")";
                    text = "From " + prefix + " onward, " + rateText + " of " + direction
                            + " observed data packets were classified as lost between " + between + ". " + upstream + " ";
                    text += PyFormat.format(PyMath.trueDivide(100 * quick, lost), ".1f") + "% recovered quickly; "
                            + PyFormat.format(PyMath.trueDivide(100 * stalls, lost), ".1f") + "% had measured stalls";
                    text += maxStall != null ? " (max " + PyFormat.format((Double) maxStall / 1000, ".3f") + " s)." : ".";
                    long other = lost - quick - stalls;
                    if (other > 0) {
                        text += " " + PyFormat.format(PyMath.trueDivide(100 * other, lost), ".1f")
                                + "% remained unrecovered or had other impact.";
                    }
                    Map<String, Object> hm = new LinkedHashMap<>();
                    hm.put("start", firstTs);
                    hm.put("end", end);
                    hm.put("lost", lost);
                    hm.put("eligible_data_packets", denominator);
                    hm.put("loss_percent", rate);
                    hm.put("quickly_recovered", quick);
                    hm.put("measured_stalls", stalls);
                    hm.put("max_stall_ms", maxStall);
                    hm.put("other_impact", other);
                    hm.put("upstream_status", status);
                    s.put("headline_metrics", hm);
                }
            }
            s.put("time_labels", labels);
            s.put("clock_caveat", clock);
            s.put("headline", text + " " + clock);
        }
    }
}
