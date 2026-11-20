package com.securityexpert.nexus.ui2.service.security;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.platform.RoleToken;

class AiviewMaskingLeakAuditTest {
    record Endpoint(String key, Method handler, String actionId) {}

    static List<Endpoint> reachableEndpoints() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Controller.class));
        var registry = new ActionRegistry();
        List<Endpoint> out = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("com.securityexpert.nexus.ui2.service")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            RequestMapping cls = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = cls == null || cls.path().length == 0 ? new String[] {""} : cls.path();
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                String[] paths = rm.path().length == 0 ? new String[] {""} : rm.path();
                for (var verb : rm.method()) {
                    for (String p : prefixes) for (String q : paths) {
                        String template = (p + q).replaceAll("\\{[^}/]+}", "*");
                        String action = actionFor(verb.name(), template);
                        out.add(new Endpoint(verb.name() + " " + template, m, action));
                    }
                }
            }
        }
        return out;
    }

    static String actionFor(String method, String path) {
        var routes = SecurityWebMvcConfig.ACTION_ID_BY_ROUTE;
        String exact = routes.get(method + " " + path);
        if (exact != null) return exact;
        String[] seg = path.split("/", -1);
        for (int i = seg.length - 1; i >= 1; i--) {
            if (seg[i].isEmpty()) continue;
            String[] w = seg.clone();
            w[i] = "*";
            String c = routes.get(method + " " + String.join("/", w));
            if (c != null) return c;
        }
        return null;
    }

    static boolean aiviewMayCall(String actionId) {
        var d = new ActionRegistry().find(actionId);
        if (d.isEmpty()) return false;
        return d.get().requiredRoleToken().isEmpty() || d.get().requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER);
    }

    static final java.util.regex.Pattern KEY_LITERAL = java.util.regex.Pattern.compile(
            "(?:\\.put|\\bentry|\\bMap\\.of|\\bputIfAbsent)\\(\\s*\"([A-Za-z][A-Za-z0-9_]{2,40})\"\\s*,");

    static java.util.SortedSet<String> harvestKeys() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("user.dir"), "src/main/java");
        java.util.SortedSet<String> keys = new java.util.TreeSet<>();
        try (var files = java.nio.file.Files.walk(root)) {
            for (var f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                var m = KEY_LITERAL.matcher(java.nio.file.Files.readString(f));
                while (m.find()) keys.add(m.group(1));
            }
        }
        return keys;
    }

    static final java.util.regex.Pattern IDENTITY_NAMED = java.util.regex.Pattern.compile(
            "(?i)host|serial|user|owner|actor|email|fqdn|login|principal|(_|^)by$|[a-z]By$|(^|_)sn(_|$)|device_?name|display_?name|(^|_)label$|vs_?name|zone_?name");

    static final byte[] KEY = String.join("-", "synthetic", "leak", "audit", "key", "0001")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    static com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice freshAdvice(String registeredHost) {
        var topo = new com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer(KEY);
        topo.setDeviceDirectory(() -> List.of(
                new com.securityexpert.nexus.ui2.service.privacy.MaskingDeviceDirectory.Entry("dev-1", registeredHost, null)));
        return new com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice(
                new com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker(KEY), topo);
    }

    /** Seeds are generated per run: host-, address-, serial- and user-shaped, never literals that look real. */
    record Seeds(String host, String ip, String serial, String user) {
        static Seeds generate() {
            var r = new java.util.Random();
            return new Seeds("qx" + Long.toHexString(r.nextLong() >>> 8) + "-edge",
                    (11 + r.nextInt(100)) + "." + r.nextInt(250) + "." + r.nextInt(250) + "." + (1 + r.nextInt(250)),
                    "ZQ" + Long.toHexString(r.nextLong() >>> 16).toUpperCase(java.util.Locale.ROOT),
                    "uq" + Long.toHexString(r.nextLong() >>> 24));
        }
    }

    static String json(Object o) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(o);
    }

    /** Declared return types an aiview-reachable handler may have, and why each is safe. */
    static final java.util.Set<String> FUNNEL_TYPES = java.util.Set.of("Map", "List", "Object", "?", "void",
            // advice special cases that project themselves and refuse an unmasked body for aiview
            "IpSearchResponse", "ChangeFeedResponse", "PacketDeviceEventsResponse", "ReportResponse", "PolicyResponse",
            "JobPage",
            // counts and closed vocabularies only (no names, addresses, serials or users)
            "Stats", "Facets", "TranscriptHealth");

    /** Byte and stream bodies bypass the advice; each is masked by its own handler (reviewed 2026-11-15). */
    static final java.util.Set<String> SELF_MASKED_BODIES = java.util.Set.of(
            "GET /api/v2/jobs/export.csv", "GET /api/v2/packet-analysis/cases/*/export/expert-report",
            "GET /api/v2/policy/changes.csv", "GET /api/v2/reports/runs/*/download",
            "GET /api/v2/policy/policies/*/hygiene.csv");

    static String simpleType(java.lang.reflect.Type t) {
        if (t instanceof ParameterizedType p) {
            String raw = ((Class<?>) p.getRawType()).getSimpleName();
            if (raw.equals("ResponseEntity")) return simpleType(p.getActualTypeArguments()[0]);
            return raw;
        }
        if (t instanceof java.lang.reflect.WildcardType) return "?";
        if (t instanceof Class<?> c) return c.getSimpleName();
        return t.getTypeName();
    }

    @Test
    void everyAiviewReachableRouteHasAMaskedBodyType() throws Exception {
        List<String> reachable = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (var e : reachableEndpoints()) {
            if (e.actionId() == null || !aiviewMayCall(e.actionId())) continue;
            reachable.add(e.key());
            String type = simpleType(e.handler().getGenericReturnType());
            boolean ok = FUNNEL_TYPES.contains(type)
                    || (type.equals("StreamingResponseBody") || type.equals("byte[]")) && SELF_MASKED_BODIES.contains(e.key());
            if (!ok) problems.add(e.key() + " returns " + type);
        }
        org.assertj.core.api.Assertions.assertThat(reachable.size()).as("aiview-reachable routes").isGreaterThan(100);
        org.assertj.core.api.Assertions.assertThat(problems)
                .as("a new body type or byte/stream body reaches aiview without review").isEmpty();
        System.out.println("AIVIEW_REACHABLE_ROUTES " + reachable.size());
    }

    @Test
    void identityKeysTheFunnelHandlesNeverLetSeededValuesThrough() throws Exception {
        var s = Seeds.generate();
        Map<String, String> byKey = new java.util.LinkedHashMap<>();
        for (String k : List.of("hostname", "device", "subject_cn", "issuer_cn", "point_label", "capture_name", "file"))
            byKey.put(k, s.host());
        for (String k : List.of("address", "management_ip", "network", "src", "dst", "client_ip", "server_ip", "next_hop",
                "gateway", "destination", "pre_nat_src", "post_nat_dst", "assumed_subnet"))
            byKey.put(k, s.ip());
        byKey.put("serial_number", s.serial());
        byKey.put("virtual_system", s.host());
        for (var entry : byKey.entrySet()) {
            for (int nest = 0; nest < 3; nest++) {
                Object body = Map.of(entry.getKey(), entry.getValue());
                if (nest == 1) body = Map.of("items", List.of(body));
                if (nest == 2) body = List.of(Map.of("rows", List.of(body)));
                String out = json(freshAdvice(s.host()).maskObject(body, null));
                org.assertj.core.api.Assertions.assertThat(out).as(entry.getKey() + " nest " + nest)
                        .doesNotContain(entry.getValue());
            }
        }
    }

    @Test
    void freeTextNamingARegisteredDeviceIsMaskedOnAColdProcess() throws Exception {
        var s = Seeds.generate();
        // Fresh pseudonymizer: no response has masked this host as a name yet (a restarted pod, or the first row of a page).
        for (String k : List.of("terminal_reason", "message", "observed_value", "reason", "detail", "failedCheck", "note")) {
            String out = json(freshAdvice(s.host()).maskObject(
                    Map.of(k, "ssh to " + s.host() + " at " + s.ip() + " failed"), null));
            org.assertj.core.api.Assertions.assertThat(out).as(k).doesNotContain(s.host(), s.ip());
        }
        var event = new com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobEvent("job-1", "inventory_collect",
                "dev-1", s.host(), "FAILED", "FAILED", "timeout talking to " + s.host(), java.time.Instant.EPOCH, null, 1L,
                false, 0L, "device", null);
        org.assertj.core.api.Assertions.assertThat(json(freshAdvice(s.host()).maskObject(event, null))).doesNotContain(s.host());
    }

    /**
     * Identity-named keys the funnel does not rewrite by itself. Each was reviewed on 2026-11-15: the producing handler
     * either omits or tokenises the value for aiview, the route is never aiview-reachable, or the value was registered
     * earlier in the same body. A NEW identity-named key fails here until someone reviews it and adds it with a reason.
     */
    static final Map<String, String> REVIEWED = Map.ofEntries(
            Map.entry("actor", "ZoneMatrixService tokenises; DiagnosticService maskSerial; ChangeEvent self-masks"),
            Map.entry("actorCandidates", "PolicyPrivacy.mask projects it (policy bodies)"),
            Map.entry("actorState", "PolicyPrivacy closed vocabulary"),
            Map.entry("actor_fingerprint", "audit log and notification text only (security_admin)"),
            Map.entry("approvedBy", "CpFailoverController removes it from every aiview view"),
            Map.entry("asa_show_failover_this_host", "diagnostic catalogue title, not data"),
            Map.entry("changedBy", "security_admin screens only"),
            Map.entry("changed_by", "ScheduledJobSwitches passes the principal function (masked for aiview)"),
            Map.entry("created_by", "PacketAnalysisController and TopologyView omit it when masked"),
            Map.entry("deleted_by", "SupportBundleController omits it (with requested_by, reason, case_ref) for the replay viewer"),
            Map.entry("device_name", "PacketDeviceEventsService, ChangeEvent self-mask; JobEvent has its own branch"),
            Map.entry("destination_host", "SupportBundleUploadController: a vendor host is a fixed product constant; a typed generic host is omitted for the replay viewer"),
            Map.entry("display_name", "capture and telemetry names go through maskPolicyName; credential admin is security_admin"),
            Map.entry("ended_by_actor_fingerprint", "session administration, security_admin"),
            Map.entry("host", "upload destination catalogue: fixed vendor host constants, null for the generic server"),
            Map.entry("support_bundle_upload_host_key", "audit presentation allowlist table name, not a response key"),
            Map.entry("failover_actor_authority", "audit presentation allowlist table name (V257), not a response key"),
            Map.entry("host_key_changed", "boolean flag on an upload row"),
            Map.entry("host_key_fingerprint", "SupportBundleUploadController omits it for the replay viewer (with remote_user, reason, case_ref)"),
            Map.entry("hop_label", "PacketDeviceEventsService self-masks"),
            Map.entry("imported_by", "LifecycleProjection redacts when masked"),
            Map.entry("label", "Overview/Report/PacketDeviceEvents label pseudonyms; schedule labels are product text"),
            Map.entry("login_security", "settings flag name"),
            Map.entry("owner", "SystemStatusService workload name (platform, not estate)"),
            Map.entry("owner_kind", "closed vocabulary"),
            Map.entry("owner_live", "boolean"),
            Map.entry("owner_name", "SystemStatusService workload name (platform, not estate)"),
            Map.entry("packet_analysis_host_telemetry", "audit event name"),
            Map.entry("published_by", "ZoneMatrixService tokenises actors"),
            Map.entry("remote_user", "SupportBundleUploadController omits it for the replay viewer (the vendor case number or account)"),
            Map.entry("requested_by", "PacketAnalysisController omits it when masked"),
            Map.entry("serial", "GlobalSearch is onboarding_admin only; policy firewall names use PolicyPrivacy key serial"),
            Map.entry("ssh_host_key_observation", "audit event name"),
            Map.entry("updated_by", "system-log settings, security_admin only"),
            Map.entry("uploaded_by", "PacketAnalysisController omits it when masked"),
            Map.entry("user", "device configuration category label"),
            Map.entry("username", "credential administration, security_admin only"),
            Map.entry("vs_name", "InventoryController: one of the body's own virtual_systems, masked earlier in the body"),
            Map.entry("zone_name", "zone-matrix lookup, never aiview"),
            Map.entry("confirmed_by", "zone-matrix cell confirmation, tokenised by actors.token, never aiview"));

    @Test
    void identityNamedKeysThatPassThroughAreReviewed() throws Exception {
        var s = Seeds.generate();
        java.util.SortedSet<String> unreviewed = new java.util.TreeSet<>();
        for (String k : harvestKeys()) {
            if (!IDENTITY_NAMED.matcher(k).find() || REVIEWED.containsKey(k)) continue;
            if (json(freshAdvice(s.host()).maskObject(Map.of(k, s.user()), null)).contains(s.user())) unreviewed.add(k);
        }
        org.assertj.core.api.Assertions.assertThat(unreviewed).as("identity-named response keys the masking funnel leaves raw").isEmpty();
    }
}
