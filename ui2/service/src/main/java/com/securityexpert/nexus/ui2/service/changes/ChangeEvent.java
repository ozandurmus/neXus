package com.securityexpert.nexus.ui2.service.changes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One line of the change feed (Operations > Change feed, mockup screen 3 of 2026-10-17): who, on which device, did
 * what, when. The feed orders and shows; it never interprets causality.
 *
 * <ul>
 *   <li>{@code time} orders the feed. {@code timeBasis} says what it is: {@code UTC} for a neXus-recorded instant,
 *       {@code DEVICE_LOCAL} for a wall-clock text a device wrote without a zone; then {@code deviceTime} carries the
 *       verbatim text and {@code time} is that text read as Europe/Istanbul <em>for ordering only</em> (the product's
 *       display zone; the device's zone is not printed anywhere, so this is an ordering assumption, stated in the
 *       feed's coverage notes, never a claim about the device clock).</li>
 *   <li>{@code key} is unique across sources and stable between requests; the cursor names the keys already shown
 *       at its boundary instant.</li>
 *   <li>{@code diff} is the compact before/after block where the source stores one: {@code -} removed, {@code +}
 *       added, {@code  } context; an empty list when the source stores no field detail.</li>
 * </ul>
 */
public record ChangeEvent(String key, Kind kind, String source, Instant time, String timeBasis, String deviceTime,
        String vendor, String deviceId, String deviceName, String actor, String what, String detail, Tone tone,
        List<DiffLine> diff, Links links) {

    /** The three kinds the mockup filters by. */
    public enum Kind { USER, NEXUS_JOB, DEVICE_EVENT }

    /** The state colour of the line's pill, from the product's role table; never a judgement about causality. */
    public enum Tone { OK, WARN, BAD, NEUTRAL, UNKNOWN }

    public record DiffLine(String op, String text) {
    }

    /** Deep links: the device page (Logs tab), the policy whose history holds the rule, the job or the job list. */
    public record Links(String device, String policyId, String ruleId, String jobId, String jobType) {
        public static final Links NONE = new Links(null, null, null, null, null);
    }

    public ChangeEvent {
        diff = diff == null ? List.of() : List.copyOf(diff);
        links = links == null ? Links.NONE : links;
        timeBasis = timeBasis == null ? "UTC" : timeBasis;
        tone = tone == null ? Tone.NEUTRAL : tone;
    }

    /** The JSON shape of one event (snake_case, like the other v2 routes). */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("kind", kind.name());
        m.put("source", source);
        m.put("time", time.toString());
        m.put("time_basis", timeBasis);
        m.put("device_time", deviceTime);
        m.put("vendor", vendor);
        m.put("device_id", deviceId);
        m.put("device_name", deviceName);
        m.put("actor", actor);
        m.put("what", what);
        m.put("detail", detail);
        m.put("tone", tone.name());
        m.put("diff", diff.stream().map(line -> Map.of("op", line.op(), "text", line.text())).toList());
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("device", links.device());
        l.put("policy_id", links.policyId());
        l.put("rule_id", links.ruleId());
        l.put("job_id", links.jobId());
        l.put("job_type", links.jobType());
        m.put("links", l);
        return m;
    }
}
