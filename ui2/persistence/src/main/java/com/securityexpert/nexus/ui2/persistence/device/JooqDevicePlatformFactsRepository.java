package com.securityexpert.nexus.ui2.persistence.device;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jooq.JSONB;
import org.jooq.Record;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqDevicePlatformFactsRepository implements DevicePlatformFactsRepository {

    /** {@code "key":"value"} pairs of a flat object; values are version strings, escaped on the way in. */
    private static final Pattern PAIR = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final String COLUMNS =
            "device_id, serial_number, hotfix_level, platform_family, content_versions, uptime_text, source_read, observed_at";

    private final TransactionBoundary transactionBoundary;

    public JooqDevicePlatformFactsRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public void record(DevicePlatformFacts facts) {
        if (facts.isEmpty()) {
            return;
        }
        String versions = toJson(facts.contentVersions());
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "insert into device_platform_facts(device_id, serial_number, hotfix_level, platform_family, content_versions, "
                        + "uptime_text, source_read, observed_at) values ({0}, {1}, {2}, {3}, {4}::jsonb, {5}, {6}, now()) "
                        + "on conflict (device_id) do update set serial_number = excluded.serial_number, "
                        + "hotfix_level = excluded.hotfix_level, platform_family = excluded.platform_family, "
                        + "content_versions = excluded.content_versions, uptime_text = excluded.uptime_text, "
                        + "source_read = excluded.source_read, observed_at = now()",
                facts.deviceId(), facts.serialNumber().orElse(null), facts.hotfixLevel().orElse(null),
                facts.platformFamily().orElse(null), versions, facts.uptimeText().orElse(null), facts.sourceRead()));
    }

    @Override
    public Optional<DevicePlatformFacts> find(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select " + COLUMNS + " from device_platform_facts where device_id = {0}", deviceId)
                .stream().findFirst().map(JooqDevicePlatformFactsRepository::toFacts));
    }

    @Override
    public Map<String, DevicePlatformFacts> findAll() {
        return transactionBoundary.inTransaction(dsl -> {
            Map<String, DevicePlatformFacts> byDevice = new LinkedHashMap<>();
            for (Record row : dsl.fetch("select " + COLUMNS + " from device_platform_facts")) {
                DevicePlatformFacts facts = toFacts(row);
                byDevice.put(facts.deviceId(), facts);
            }
            return byDevice;
        });
    }

    private static DevicePlatformFacts toFacts(Record row) {
        Timestamp observed = row.get("observed_at", Timestamp.class);
        return new DevicePlatformFacts(
                row.get("device_id", String.class),
                Optional.ofNullable(row.get("serial_number", String.class)),
                Optional.ofNullable(row.get("hotfix_level", String.class)),
                Optional.ofNullable(row.get("platform_family", String.class)),
                fromJson(row.get("content_versions", JSONB.class)),
                Optional.ofNullable(row.get("uptime_text", String.class)),
                row.get("source_read", String.class),
                Optional.ofNullable(observed).map(Timestamp::toInstant));
    }

    static String toJson(Map<String, String> versions) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : versions.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":\"").append(escape(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    static Map<String, String> fromJson(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) {
            return Map.of();
        }
        Map<String, String> versions = new LinkedHashMap<>();
        Matcher matcher = PAIR.matcher(jsonb.data());
        while (matcher.find()) {
            versions.put(unescape(matcher.group(1)), unescape(matcher.group(2)));
        }
        return versions;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
