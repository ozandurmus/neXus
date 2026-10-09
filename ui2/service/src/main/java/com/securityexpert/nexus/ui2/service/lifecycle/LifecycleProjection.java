package com.securityexpert.nexus.ui2.service.lifecycle;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogEntry;

/** Pure stored-evidence projection. Product labels never supply vendor milestone dates. */
public final class LifecycleProjection {
    private static final Pattern CP = Pattern.compile("^(R\\d{2}\\.\\d{2})(?:$|[\\s+].*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAN = Pattern.compile("^(?:PAN-OS\\s+)?(\\d+\\.\\d+)(?:\\.\\d+(?:-h\\d+)?)?$", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private LifecycleProjection() { }

    public static String vendor(String hint) {
        return switch (hint) {
            case "check_point" -> "CHECKPOINT";
            case "palo_alto" -> "PALOALTO";
            default -> hint.toUpperCase(java.util.Locale.ROOT);
        };
    }

    public static String train(String vendor, String version) {
        if (version == null) return null;
        var pattern = switch (vendor) { case "CHECKPOINT" -> CP; case "PALOALTO" -> PAN; default -> null; };
        if (pattern == null) return null;
        var match = pattern.matcher(version.trim());
        return match.matches() ? match.group(1).toUpperCase(java.util.Locale.ROOT) : null;
    }

    public static Map<String, Object> match(String vendor, String kind, String observed, List<LifecycleCatalogEntry> catalog) {
        boolean hardware = "HARDWARE".equals(kind);
        boolean normalized = !hardware && ("CHECKPOINT".equals(vendor) || "PALOALTO".equals(vendor));
        String key = normalized ? train(vendor, observed) : observed;
        var candidates = catalog.stream().filter(e -> e.vendor().equals(vendor) && e.kind().equals(kind))
                .filter(e -> key != null && key.equals(normalized ? train(vendor, e.product()) : e.product())).toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("status", candidates.size() == 1 ? "MATCHED" : "NO_LIFECYCLE_DATA");
        body.put("reason", candidates.size() == 1 ? null : candidates.size() > 1 ? "Ambiguous catalog matches"
                : key == null ? "Model/version missing or software train unsupported" : "No catalog match");
        body.put("basis", candidates.size() == 1 ? hardware ? "model exact" : normalized ? "version train" : "version exact" : null);
        body.put("catalog", candidates.size() == 1 ? catalog(candidates.getFirst(), false) : null);
        return body;
    }

    public static Map<String, Object> catalog(LifecycleCatalogEntry e, boolean masked) {
        var body = new LinkedHashMap<String, Object>();
        body.put("catalog_id", e.catalogId()); body.put("vendor", e.vendor()); body.put("kind", e.kind());
        body.put("product", e.product()); body.put("end_of_sale", text(e.endOfSale()));
        body.put("end_of_support", text(e.endOfSupport())); body.put("end_of_engineering", text(e.endOfEngineering()));
        body.put("source", e.source()); body.put("note", masked ? "[REDACTED]" : e.note());
        body.put("imported_by", masked ? "[REDACTED]" : e.importedBy()); body.put("imported_at", e.importedAt().toString());
        return body;
    }

    public static List<Map<String, Object>> licenses(String vendor, String stored, LocalDate today) {
        if (!"INFOBLOX".equals(vendor) || stored == null) return List.of();
        var result = new ArrayList<Map<String, Object>>();
        try {
            var root = JSON.readTree(stored);
            if (root == null || !root.isArray()) return List.of(Map.of("status", "UNKNOWN", "label", "Stored license summary is invalid"));
            for (var value : root) {
                var row = new LinkedHashMap<String, Object>();
                row.put("name", value.path("type").asText("UNKNOWN"));
                row.put("kind", value.path("kind").asText("UNKNOWN"));
                row.put("member", value.path("member").asText("grid"));
                String raw = value.path("expiry_date").asText("");
                LocalDate expiry = null;
                try { if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) expiry = LocalDate.parse(raw); }
                catch (RuntimeException ignored) { /* Retain the stored label as UNKNOWN. */ }
                row.put("label", raw); row.put("expiry", text(expiry));
                row.put("status", expiry == null ? "UNKNOWN" : expiry.isBefore(today) ? "EXPIRED" : "DATED");
                row.put("days_remaining", expiry == null ? null : ChronoUnit.DAYS.between(today, expiry));
                result.add(row);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of(Map.of("status", "UNKNOWN", "label", "Stored license summary is invalid"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> device(DeviceSummaryRecord d, List<LifecycleCatalogEntry> catalog,
            com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogRepository.StoredInventory inventory, LocalDate today) {
        String vendor = vendor(d.vendorHint());
        String model = inventory == null ? null : inventory.model();
        String version = inventory == null ? null : inventory.version();
        String licensesJson = inventory == null ? null : inventory.licensesJson();
        var hw = match(vendor, "HARDWARE", model, catalog);
        var sw = match(vendor, "SOFTWARE", version, catalog);
        var licenses = licenses(vendor, licensesJson, today);
        var milestones = new ArrayList<Map<String, Object>>();
        var supportDays = new ArrayList<Long>();
        for (var part : List.of(Map.entry("Hardware", hw), Map.entry("Software", sw))) {
            if (!(part.getValue().get("catalog") instanceof Map<?, ?> dates)) continue;
            for (String field : List.of("end_of_sale", "end_of_support", "end_of_engineering")) {
                if (dates.get(field) instanceof String date) {
                    long days = ChronoUnit.DAYS.between(today, LocalDate.parse(date));
                    milestones.add(Map.of("name", part.getKey() + " " + field.replace('_', ' '), "date", date, "days_remaining", days));
                    if (field.equals("end_of_support")) supportDays.add(days);
                }
            }
            // Fleet match projections contain facts, not free-text admin provenance or importer identity.
            var safe = new LinkedHashMap<>((Map<String, Object>) dates);
            safe.remove("note"); safe.remove("imported_by"); part.getValue().put("catalog", safe);
        }
        var future = milestones.stream().filter(m -> (long) m.get("days_remaining") >= 0)
                .min(Comparator.comparingLong(m -> (long) m.get("days_remaining")));
        var next = future.orElseGet(() -> milestones.stream().max(Comparator.comparingLong(m -> (long) m.get("days_remaining"))).orElse(null));
        Long minimum = supportDays.stream().min(Long::compare).orElse(null);
        String risk = minimum != null && minimum < 0 ? "EXPIRED" : minimum != null && minimum < 180 ? "HIGH"
                : minimum != null && minimum < 365 ? "MEDIUM" : supportDays.size() == 2 ? "LOW" : "UNKNOWN";
        boolean matched = "MATCHED".equals(hw.get("status")) && "MATCHED".equals(sw.get("status"));
        var body = new LinkedHashMap<String, Object>();
        body.put("device_id", d.deviceId()); body.put("hostname", d.observedHostname().orElse(null));
        body.put("cluster_member_ref", d.clusterMemberRef().orElse(null)); body.put("vendor", vendor);
        body.put("model", model); body.put("software_version", version);
        body.put("hardware", hw); body.put("software", sw); body.put("status", matched ? "MATCHED" : "NO_LIFECYCLE_DATA");
        body.put("risk", risk); body.put("support_days", minimum); body.put("next_milestone", next);
        body.put("licenses", licenses); body.put("license_status", !vendor.equals("INFOBLOX") ? "NOT_COLLECTED"
                : licensesJson == null ? "UNKNOWN" : "COLLECTED");
        body.put("support_contract_status", "NOT_COLLECTED");
        body.put("nearest_license", licenses.stream().filter(l -> l.get("expiry") != null)
                .min(Comparator.comparing(l -> (String) l.get("expiry"))).orElse(null));
        return body;
    }

    private static String text(LocalDate date) { return date == null ? null : date.toString(); }
}
