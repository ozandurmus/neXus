package com.securityexpert.nexus.ui2.service.search;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.jooq.Record;
import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.service.overview.ConfigurationProjection;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;

/** Read-only search over displayed device facts, latest configuration projection and stored evidence. */
@Service
public final class GlobalSearchService {
    private record RunRef(String deviceId, String runId, String vendor) { }

    private final DeviceRepository devices;
    private final DevicePlatformFactsRepository facts;
    private final TransactionBoundary transactions;
    private final PrivacyMaskingResponseBodyAdvice masking;
    // 105 devices today; old runs are evicted so a long-lived service cannot retain unbounded configuration rows.
    private final Map<String, List<ConfigurationProjection.Row>> rowsByRun = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<ConfigurationProjection.Row>> eldest) {
            return size() > 128;
        }
    };

    public GlobalSearchService(DeviceRepository devices, DevicePlatformFactsRepository facts,
            TransactionBoundary transactions, PrivacyMaskingResponseBodyAdvice masking) {
        this.devices = devices;
        this.facts = facts;
        this.transactions = transactions;
        this.masking = masking;
    }

    public Map<String, Object> search(String term, int limit, boolean masked) {
        List<DeviceSummaryRecord> deviceRows = devices.listAll();
        Map<String, DevicePlatformFacts> platformFacts = facts.findAll();
        // Register names before masking free-form setting values and terminal reasons.
        if (masked) {
            for (DeviceSummaryRecord device : deviceRows) {
                Map<String, Object> identity = identity(device, platformFacts.get(device.deviceId()));
                masking.maskObject(identity, null);
            }
        }
        List<Map<String, Object>> deviceHits = new ArrayList<>();
        Map<String, Map<String, Object>> identityById = new HashMap<>();
        for (DeviceSummaryRecord device : deviceRows) {
            Map<String, Object> identity = identity(device, platformFacts.get(device.deviceId()));
            identity = visibleIdentity(identity, masking, masked);
            identityById.put(device.deviceId(), identity);
            if (deviceHits.size() < limit && matches(term, identity, "device_id")) {
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("device_id", device.deviceId());
                hit.put("name", identity.get("hostname"));
                hit.put("serial", identity.get("serial_number"));
                hit.put("model", identity.get("model"));
                hit.put("software_version", identity.get("software_version"));
                hit.put("cluster", identity.get("cluster_member_ref"));
                hit.put("management_address", identity.get("management_ip"));
                hit.put("vendor", identity.get("vendor"));
                hit.put("href", "?screen=inventory&device_id=" + url(device.deviceId()));
                deviceHits.add(hit);
            }
        }

        List<RunRef> runs = transactions.inTransaction(dsl -> dsl.fetch(
                "select p.device_id, coalesce(case when p.sanitized_text is not null then p.run_id end, a.run_id) as run_id, p.vendor "
                + "from (select distinct on (device_id) device_id, run_id, vendor, sanitized_text "
                + "from device_configuration_run where is_primary order by device_id, collected_at desc) p "
                + "left join lateral (select run_id from device_configuration_run "
                + "where device_id = p.device_id and read_kind = 'active' and sanitized_text is not null "
                + "order by collected_at desc limit 1) a on p.vendor = 'palo_alto'")
                .map(r -> new RunRef(r.get("device_id", String.class), r.get("run_id", String.class), r.get("vendor", String.class))));
        loadRows(runs);
        // One hit per (section, setting): the same setting on 38 members is one answer with a device count, not 38
        // rows from the first device (live check 2026-09-23). The sample is the first device in list order.
        Map<String, Map<String, Object>> settingGroups = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> devicesBySetting = new HashMap<>();
        for (RunRef run : runs) {
            if (run.runId() == null) continue;
            Map<String, Object> identity = identityById.get(run.deviceId());
            if (identity == null) continue;
            List<ConfigurationProjection.Row> rows;
            synchronized (rowsByRun) { rows = rowsByRun.getOrDefault(run.runId(), List.of()); }
            for (ConfigurationProjection.Row row : rows) {
                String section = display(row.section(), masked);
                String setting = display(row.setting(), masked);
                String value = display(row.value(), masked);
                if (!matches(term, Map.of("device", Objects.toString(identity.get("hostname"), ""),
                        "cluster", Objects.toString(identity.get("cluster_member_ref"), ""),
                        "section", section, "setting", setting, "value", value))) continue;
                String key = section + " \u203a " + setting;
                devicesBySetting.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).add(run.deviceId());
                if (!settingGroups.containsKey(key)) {
                    if (settingGroups.size() == limit) continue;
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("device", identity.get("hostname"));
                    hit.put("cluster", identity.get("cluster_member_ref"));
                    hit.put("section", section);
                    hit.put("setting", setting);
                    hit.put("value_excerpt", value.length() > 80 ? value.substring(0, 80) : value);
                    hit.put("href", "?screen=configuration&device_id=" + url(run.deviceId()));
                    settingGroups.put(key, hit);
                }
            }
        }
        List<Map<String, Object>> settings = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : settingGroups.entrySet()) {
            e.getValue().put("device_count", devicesBySetting.get(e.getKey()).size());
            settings.add(e.getValue());
        }

        List<Map<String, Object>> evidence = transactions.inTransaction(dsl -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Record r : dsl.fetch("select job_id, terminal_reason from jobs "
                    + "where submitted_at >= now() - interval '7 days' order by submitted_at desc")) {
                String id = r.get("job_id", String.class);
                String reason = display(r.get("terminal_reason", String.class), masked);
                if (matches(term, Map.of("job_id", id, "terminal_reason", reason))) {
                    out.add(Map.of("kind", "job", "label", id, "detail", reason,
                            "href", "?screen=operations&tab=jobs&q=" + url(id)));
                    if (out.size() == limit) break;
                }
            }
            for (Record r : dsl.fetch("select artefact_id from backup_artefact where artefact_class = 'backup' "
                    + "order by created_at desc")) {
                if (out.size() >= limit) break;
                String id = r.get("artefact_id", String.class);
                if (contains(id, term)) out.add(Map.of("kind", "backup", "label", id,
                        "detail", "Backup artefact", "href", "?screen=backups&q=" + url(id)));
            }
            return out;
        });
        return limited(deviceHits, settings, evidence, limit);
    }

    static Map<String, Object> limited(List<Map<String, Object>> deviceHits,
            List<Map<String, Object>> settings, List<Map<String, Object>> evidence, int limit) {
        // Share a total limit across groups so a broad device match cannot hide all settings and evidence.
        List<Map<String, Object>> d = new ArrayList<>(), s = new ArrayList<>(), e = new ArrayList<>();
        int i = 0;
        while (d.size() + s.size() + e.size() < limit
                && (i < deviceHits.size() || i < settings.size() || i < evidence.size())) {
            if (i < deviceHits.size() && d.size() + s.size() + e.size() < limit) d.add(deviceHits.get(i));
            if (i < settings.size() && d.size() + s.size() + e.size() < limit) s.add(settings.get(i));
            if (i < evidence.size() && d.size() + s.size() + e.size() < limit) e.add(evidence.get(i));
            i++;
        }
        return Map.of("devices", d, "settings", s, "evidence", e);
    }

    private void loadRows(List<RunRef> runs) {
        Set<String> missing;
        synchronized (rowsByRun) {
            missing = runs.stream().map(RunRef::runId).filter(Objects::nonNull)
                    .filter(id -> !rowsByRun.containsKey(id)).collect(Collectors.toSet());
        }
        if (missing.isEmpty()) return;
        Map<String, String> vendors = runs.stream().filter(r -> r.runId() != null)
                .collect(Collectors.toMap(RunRef::runId, RunRef::vendor, (a, b) -> a));
        Map<String, List<ConfigurationProjection.Row>> loaded = transactions.inTransaction(dsl -> {
            Map<String, List<ConfigurationProjection.Row>> out = new HashMap<>();
            for (Record r : dsl.fetch("select run_id, sanitized_text from device_configuration_run where run_id = any({0})",
                    (Object) missing.toArray(String[]::new))) {
                String id = r.get("run_id", String.class);
                String text = r.get("sanitized_text", String.class);
                if (text != null) out.put(id, "palo_alto".equals(vendors.get(id))
                        ? ConfigurationProjection.paloAlto(text) : ConfigurationProjection.checkPoint(text));
            }
            return out;
        });
        synchronized (rowsByRun) { rowsByRun.putAll(loaded); }
    }

    private Map<String, Object> identity(DeviceSummaryRecord device, DevicePlatformFacts platform) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("device_id", device.deviceId());
        out.put("hostname", device.observedHostname().orElse(null));
        out.put("serial_number", platform == null ? null : platform.serialNumber().orElse(null));
        out.put("model", device.observedModel().orElse(null));
        out.put("software_version", device.observedSoftwareVersion().orElse(null));
        out.put("cluster_member_ref", device.clusterMemberRef().orElse(null));
        out.put("management_ip", device.managementIp().orElse(null));
        out.put("vendor", device.vendorHint());
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> visibleIdentity(Map<String, Object> raw,
            PrivacyMaskingResponseBodyAdvice masking, boolean isMasked) {
        return isMasked ? (Map<String, Object>) masking.maskObject(raw, null) : raw;
    }

    private String display(String raw, boolean masked) {
        if (raw == null) return "";
        return masked ? (String) masking.maskObject(raw, null) : raw;
    }

    static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    private static String url(String opaqueId) {
        return URLEncoder.encode(opaqueId, StandardCharsets.UTF_8);
    }

    static boolean matches(String term, Map<String, Object> fields, String... excluded) {
        Set<String> skip = Set.of(excluded);
        return fields.entrySet().stream().anyMatch(e -> !skip.contains(e.getKey())
                && e.getValue() instanceof String s && contains(s, term));
    }
}
