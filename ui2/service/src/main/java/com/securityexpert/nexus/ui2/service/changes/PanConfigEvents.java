package com.securityexpert.nexus.ui2.service.changes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Palo Alto config-log entries as change events. A stored {@code pan_config} chunk is JSON Lines, one object per
 * {@code <entry>} with every child element name as a key (Phase B contract section 6, redacted on write by
 * {@code PanLogRedactor}). The field names used here are the ones measured on all 39 firewalls (findings 2026-10-17
 * section 5): {@code admin}, {@code cmd}, {@code result}, {@code receive_time}, {@code seqno}, {@code client}, plus
 * {@code path} / {@code full-path} and {@code before-change-preview} / {@code after-change-preview} where present.
 * A line that is not a JSON object, or has no {@code receive_time}, is counted as unparsed and left out: the feed
 * shows what the device wrote, never a reconstruction.
 *
 * <p>{@code receive_time} is the device wall clock ({@code yyyy/MM/dd HH:mm:ss}, no zone, Phase B section 4.7). The
 * event keeps the verbatim text as {@code device_time} and orders by it read as {@link #ORDERING_ZONE} (the product's
 * display zone); the coverage note says so.</p>
 */
public final class PanConfigEvents {
    /** The device clock is read in this zone for ordering only; the device's own zone is not printed anywhere. */
    public static final ZoneId ORDERING_ZONE = ZoneId.of("Europe/Istanbul");
    static final DateTimeFormatter RECEIVE_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT);
    /** A before/after preview longer than this is cut, with a marker: the feed is a timeline, not a configuration viewer. */
    static final int MAX_PREVIEW_CHARS = 600;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private PanConfigEvents() {}

    /** The events of one chunk's lines, newest first is not guaranteed (the caller sorts); {@code unparsed} counts the rest. */
    public record Parsed(List<ChangeEvent> events, int unparsed) {
    }

    public static Parsed parse(List<String> lines, String deviceId, String chunkId, Projection projection) {
        List<ChangeEvent> out = new ArrayList<>();
        int unparsed = 0;
        int index = 0;
        for (String line : lines) {
            index++;
            Optional<ChangeEvent> event = parseLine(line, deviceId, chunkId + ":" + index, projection);
            if (event.isPresent()) out.add(event.get());
            else unparsed++;
        }
        return new Parsed(out, unparsed);
    }

    static Optional<ChangeEvent> parseLine(String line, String deviceId, String key, Projection projection) {
        if (line == null || line.isBlank()) return Optional.empty();
        Map<String, Object> fields;
        try {
            fields = JSON.readValue(line, OBJECT);
        } catch (java.io.IOException e) {
            return Optional.empty();
        }
        String receiveTime = text(fields, "receive_time");
        if (receiveTime == null) return Optional.empty();
        LocalDateTime local;
        try {
            local = LocalDateTime.parse(receiveTime.strip(), RECEIVE_TIME);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        Instant ordering = local.atZone(ORDERING_ZONE).toInstant();
        String admin = text(fields, "admin");
        String cmd = text(fields, "cmd");
        String result = text(fields, "result");
        String client = text(fields, "client");
        String path = firstText(fields, "full-path", "path", "fullpath");
        String before = firstText(fields, "before-change-preview", "before-change-detail");
        String after = firstText(fields, "after-change-preview", "after-change-detail");
        String seqno = text(fields, "seqno");

        StringBuilder what = new StringBuilder();
        what.append(cmd == null || cmd.isBlank() ? "config command UNKNOWN" : cmd.strip());
        if (path != null && !path.isBlank()) what.append(' ').append(projection.text(path.strip()));
        StringBuilder detail = new StringBuilder();
        if (result != null && !result.isBlank()) detail.append("result ").append(result.strip());
        if (client != null && !client.isBlank()) detail.append(detail.length() > 0 ? " · " : "").append("client ").append(client.strip());
        if (seqno != null && !seqno.isBlank()) detail.append(detail.length() > 0 ? " · " : "").append("seqno ").append(seqno.strip());

        List<ChangeEvent.DiffLine> diff = new ArrayList<>();
        if (before != null && !before.isBlank()) diff.add(new ChangeEvent.DiffLine("-", projection.text(cut(before))));
        if (after != null && !after.isBlank()) diff.add(new ChangeEvent.DiffLine("+", projection.text(cut(after))));

        ChangeEvent.Tone tone = result != null && result.toLowerCase(Locale.ROOT).contains("fail") ? ChangeEvent.Tone.BAD : ChangeEvent.Tone.NEUTRAL;
        return Optional.of(new ChangeEvent("pan_config:" + key, ChangeEvent.Kind.USER, "pan_config", ordering, "DEVICE_LOCAL",
                receiveTime.strip(), "palo_alto", deviceId, projection.device(deviceId),
                admin == null || admin.isBlank() ? "UNKNOWN" : projection.principal(admin.strip()),
                what.toString(), detail.length() == 0 ? null : detail.toString(), tone, diff,
                new ChangeEvent.Links(deviceLink(deviceId), null, null, null, null)));
    }

    static String deviceLink(String deviceId) {
        return "?screen=inventory&device_id=" + java.net.URLEncoder.encode(deviceId, java.nio.charset.StandardCharsets.UTF_8) + "&tab=logs";
    }

    private static String text(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static String firstText(Map<String, Object> fields, String... names) {
        for (String name : names) {
            String value = text(fields, name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String cut(String value) {
        String v = value.strip();
        return v.length() <= MAX_PREVIEW_CHARS ? v : v.substring(0, MAX_PREVIEW_CHARS) + " … (cut)";
    }
}
