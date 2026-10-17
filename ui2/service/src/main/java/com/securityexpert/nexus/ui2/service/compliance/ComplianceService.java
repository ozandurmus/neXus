package com.securityexpert.nexus.ui2.service.compliance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationQueryService;

/**
 * Service layer coordinating with the {@code ui2-compliance} microservice (Port 8085).
 * Strictly complies with DIR-2: zero imports of worker classes.
 */
public final class ComplianceService {

    /**
     * One device to evaluate: its latest configuration run's canonical hash is the cache key -- the evaluation is a
     * pure function of the configuration text and the compliance service's rule set (no clock is read), so an
     * unchanged configuration under an unchanged rule set needs no re-evaluation (2026-09-23: a 10-minute expiry
     * made every Overview/Compliance open after 10 idle minutes re-evaluate ~100 devices one by one, ~15.6 s).
     */
    record Target(String deviceId, Optional<String> hostname, String vendor, Optional<String> canonicalHash) {
    }

    /** Backstop age: an entry is re-evaluated after this even with an unchanged configuration and rule set. */
    static final Duration MAX_AGE = Duration.ofHours(6);
    /** Concurrent evaluations when the cache cannot answer (the compliance service runs on virtual threads). */
    static final int PARALLELISM = 8;

    private final ComplianceEvaluationStore store;
    private volatile boolean loaded;
    private final Supplier<List<Target>> targets;
    private final Function<String, Optional<String>> sanitizedText;
    private final Supplier<Instant> clock;
    private final int parallelism;
    private final Duration maxAge;
    private volatile String rulesFingerprint = "";
    private final java.util.concurrent.atomic.AtomicLong textNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong callNanos = new java.util.concurrent.atomic.AtomicLong();
    private volatile Instant rulesFingerprintAt = Instant.EPOCH;

    private final String complianceServiceUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ConfigurationQueryService configurationQueryService;
    private final DeviceRepository deviceRepository;

    // Cache of device evaluation results to enable instant UI responsiveness
    private final Map<String, CachedEvaluation> evaluationCache = new ConcurrentHashMap<>();
    /** A failed evaluation (timeout, non-200) per device and configuration: not retried before this window passes. */
    static final Duration FAILURE_RETRY = Duration.ofHours(1);
    private final Map<String, Instant> failedAt = new ConcurrentHashMap<>();

    private record CachedEvaluation(Instant timestamp, String configHash, String canonicalHash, String rulesFingerprint,
            Map<String, Object> result) {
    }

    public ComplianceService(ConfigurationQueryService configurationQueryService,
                             DeviceRepository deviceRepository) {
        this(configurationQueryService, deviceRepository, resolveUrl());
    }

    public ComplianceService(ConfigurationQueryService configurationQueryService,
                             DeviceRepository deviceRepository,
                             String complianceServiceUrl) {
        this(configurationQueryService, deviceRepository, complianceServiceUrl, ComplianceEvaluationStore.inMemory());
    }

    /** Production wiring: evaluations persist in {@code compliance_evaluation} (V59). */
    public ComplianceService(ConfigurationQueryService configurationQueryService, DeviceRepository deviceRepository,
            ComplianceEvaluationStore store) {
        this(configurationQueryService, deviceRepository, resolveUrl(), store);
    }

    public ComplianceService(ConfigurationQueryService configurationQueryService, DeviceRepository deviceRepository,
            String complianceServiceUrl, ComplianceEvaluationStore store) {
        this(configurationQueryService, deviceRepository, complianceServiceUrl,
                () -> {
                    // Firewall compliance: a management server (Panorama, MDS) is not a firewall and is never evaluated
                    // (2026-09-23: Panorama's 23.7 MB configuration timed out every evaluation, added 24 data gaps to the
                    // fleet figures and held every screen open 15 s while it was retried).
                    java.util.Set<String> managers = deviceRepository.listAll().stream()
                            .filter(d -> "management_server".equals(d.role()))
                            .map(com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord::deviceId)
                            .collect(java.util.stream.Collectors.toSet());
                    return configurationQueryService.listDevices().stream()
                        .filter(d -> d.latestRun().isPresent() && !managers.contains(d.deviceId()))
                        .map(d -> new Target(d.deviceId(), d.hostname(), d.vendor(), d.latestRun().map(r -> r.canonicalHash())))
                        .toList();
                },
                configurationQueryService::sanitizedText, Instant::now, PARALLELISM, MAX_AGE, store);
    }

    /** Test seam: the device list, the configuration text, the clock, parallelism and backstop age are injected. */
    ComplianceService(ConfigurationQueryService configurationQueryService, DeviceRepository deviceRepository,
            String complianceServiceUrl, Supplier<List<Target>> targets, Function<String, Optional<String>> sanitizedText,
            Supplier<Instant> clock, int parallelism, Duration maxAge, ComplianceEvaluationStore store) {
        this.store = store;
        this.targets = targets;
        this.sanitizedText = sanitizedText;
        this.clock = clock;
        this.parallelism = Math.max(1, parallelism);
        this.maxAge = maxAge;
        this.configurationQueryService = configurationQueryService;
        this.deviceRepository = deviceRepository;
        this.complianceServiceUrl = complianceServiceUrl != null && !complianceServiceUrl.isBlank()
                ? complianceServiceUrl.trim().replaceAll("/+$", "")
                : "http://127.0.0.1:8085";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    private static String resolveUrl() {
        String env = System.getenv("UI2_COMPLIANCE_SERVICE_URL");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return "http://ui2-compliance.ui2.svc.cluster.local:8085";
    }

    public Map<String, Object> getOverview() {
        List<Target> all = allDevices();
        List<Target> evaluated = evaluable(all);

        int totalDevices = all.size();
        int evaluatedCount = evaluated.size();

        if (evaluatedCount == 0) {
            return emptyOverview(totalDevices);
        }

        int[] pending = {0};
        Map<String, Map<String, Object>> evaluations = evaluateAll(evaluated, false, pending);
        List<Map<String, Object>> answered = new ArrayList<>();
        for (var dev : evaluated) {
            Map<String, Object> eval = evaluations.get(dev.deviceId());
            if (eval != null && !eval.containsKey("error")) {
                answered.add(eval);
            }
        }

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("total_firewalls", totalDevices);
        overview.put("evaluated_firewalls", evaluatedCount);
        overview.putAll(aggregateChecks(answered));
        // Devices shown with their previous evaluation while the background re-evaluates a changed configuration
        // or a changed rule set (at most a minute): the figures are complete, one step behind for these devices.
        overview.put("pending_reevaluation", pending[0]);

        return overview;
    }

    /** The outcome of one control on one firewall, as every figure on the Overview and Compliance screens counts it. */
    enum CheckOutcome { PASS, FAIL, UNAVAILABLE }

    /**
     * {@code null} for NOT_APPLICABLE: the control does not apply to this firewall, so it is not a control check at all
     * and is counted nowhere (2026-10-17: it was counted in each framework's total but in none of its parts, so the
     * CIS, PCI and NIST cards were 8 checks larger than pass + fail + unavailable, and every score was computed over
     * that larger total). Any status other than PASS or FAIL is not evidence and counts as unavailable.
     */
    static CheckOutcome checkOutcome(Object displayStatus) {
        String st = String.valueOf(displayStatus);
        if ("NOT_APPLICABLE".equalsIgnoreCase(st)) return null;
        if ("PASS".equalsIgnoreCase(st)) return CheckOutcome.PASS;
        if ("FAIL".equalsIgnoreCase(st)) return CheckOutcome.FAIL;
        return CheckOutcome.UNAVAILABLE;
    }

    /** One decimal, the same rounding the Overview screen applies ({@code Math.round(1000 * n / d) / 10}). */
    static double pct1(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : Math.round(numerator * 1000.0 / denominator) / 10.0;
    }

    /**
     * Fleet figures over control checks (one control on one firewall), from the answered evaluations' items: every
     * total is the sum of its pass, fail and unavailable parts, the fleet percentages are pooled over the same checks
     * (not a mean of per-firewall percentages, which weighted a 9-check firewall like a 59-check one and was labelled
     * as a share of checks), data gaps are the unavailable checks, and an item counts once per framework however many
     * references it carries to that framework.
     */
    static Map<String, Object> aggregateChecks(List<Map<String, Object>> evaluations) {
        long pass = 0;
        long fail = 0;
        long unavailable = 0;
        int criticalDeficiencies = 0;
        // Framework accumulators: [pass, fail, unavailable]
        Map<String, long[]> frameworkStats = new LinkedHashMap<>();
        for (String name : List.of("CIS Benchmark", "PCI-DSS v4.0.1", "NIST SP 800-53", "Financial Baseline")) {
            frameworkStats.put(name, new long[]{0, 0, 0});
        }
        for (Map<String, Object> eval : evaluations) {
            for (Map<String, Object> item : getItems(eval)) {
                CheckOutcome outcome = checkOutcome(item.get("displayStatus"));
                if (outcome == null) {
                    continue;
                }
                switch (outcome) {
                    case PASS -> pass++;
                    case FAIL -> fail++;
                    case UNAVAILABLE -> unavailable++;
                }
                if (outcome == CheckOutcome.FAIL && "CRITICAL".equalsIgnoreCase(String.valueOf(item.get("severity")))) {
                    criticalDeficiencies++;
                }
                java.util.Set<String> counted = new java.util.HashSet<>();
                for (Map<String, Object> fw : getFrameworks(item)) {
                    String name = mapFrameworkName(String.valueOf(fw.get("framework")));
                    long[] stats = frameworkStats.get(name);
                    if (stats != null && counted.add(name)) {
                        stats[outcome.ordinal()]++;
                    }
                }
            }
        }
        long checks = pass + fail + unavailable;
        List<Map<String, Object>> frameworkCards = new ArrayList<>();
        for (var entry : frameworkStats.entrySet()) {
            long[] s = entry.getValue();
            long total = s[0] + s[1] + s[2];
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("framework", entry.getKey());
            card.put("score_pct", pct1(s[0], total));
            card.put("total_controls", total);
            card.put("pass_count", s[0]);
            card.put("fail_count", s[1]);
            card.put("data_unavailable_count", s[2]);
            frameworkCards.add(card);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assured_compliance_pct", pct1(pass, checks));
        out.put("evidence_coverage_pct", pct1(pass + fail, checks));
        out.put("observed_compliance_pct", pct1(pass, pass + fail));
        out.put("critical_deficiencies", criticalDeficiencies);
        out.put("data_gaps", unavailable);
        out.put("control_checks", checks);
        out.put("frameworks", frameworkCards);
        return out;
    }

    /** One compliance target's critical checks, for the executive estate map (PO 2026-09-25). */
    public record DeviceCriticals(int criticalFail, int criticalUnavailable, int criticalPass, int evidencedChecks) {
    }

    /**
     * Per evaluated device: its critical checks failing, not evidenced and passing, and its checks of any severity
     * with evidence (pass or fail) -- latest stored evaluation.
     */
    public Map<String, DeviceCriticals> criticalsByDevice() {
        List<Target> evaluated = evaluable(allDevices());
        Map<String, DeviceCriticals> out = new LinkedHashMap<>();
        evaluateAll(evaluated, false, new int[1]).forEach((id, eval) -> {
            if (eval == null || eval.containsKey("error")) {
                return;
            }
            out.put(id, deviceCriticals(eval));
        });
        return out;
    }

    /** One evaluation's critical checks failing, not evidenced and passing, and its checks with evidence. */
    static DeviceCriticals deviceCriticals(Map<String, Object> eval) {
        int fail = 0;
        int unavailable = 0;
        int pass = 0;
        int evidenced = 0;
        for (Map<String, Object> item : getItems(eval)) {
            CheckOutcome outcome = checkOutcome(item.get("displayStatus"));
            if (outcome == CheckOutcome.PASS || outcome == CheckOutcome.FAIL) evidenced++;
            if (outcome == null || !"CRITICAL".equalsIgnoreCase(String.valueOf(item.get("severity")))) {
                continue;
            }
            switch (outcome) {
                case FAIL -> fail++;
                case UNAVAILABLE -> unavailable++;
                case PASS -> pass++;
            }
        }
        return new DeviceCriticals(fail, unavailable, pass, evidenced);
    }

    /**
     * True when at least one device evaluation is cached and none is older than the 10-minute freshness window --
     * the Overview reads the compliance figures only then, and never triggers an evaluation itself
     * (OVERVIEW_EXCEPTION_SCREEN_CONTRACT §3 Section 5).
     */
    public boolean isEvaluationCacheWarm() {
        ensureLoaded();
        return !evaluationCache.isEmpty();
    }

    /**
     * Background pass (the warm-up, every minute): evaluates every device whose stored evaluation does not match its
     * current configuration and rule set, or is older than the backstop age. Screens never wait for this.
     * @return the number of devices evaluated
     */
    public int refreshInBackground() {
        int[] evaluatedNow = {0};
        List<Target> evaluated = evaluable(allDevices());
        ensureLoaded();
        refreshRulesFingerprint();
        if (rulesFingerprint.isEmpty()) {
            return 0; // the compliance service has not answered its health check yet: try again next pass
        }
        long misses = evaluated.stream().filter(t -> cachedResult(t.deviceId(), t.canonicalHash()) == null).count();
        evaluateAll(evaluated, true, evaluatedNow);
        return (int) misses;
    }

    /** Loads the stored evaluations once (V59), so a restart or deploy starts with every device already evaluated. */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            try {
                for (ComplianceEvaluationStore.Stored st : store.loadAll()) {
                    Map<String, Object> parsed = mapper.readValue(st.resultJson(), new TypeReference<Map<String, Object>>() {});
                    evaluationCache.putIfAbsent(st.deviceId(), new CachedEvaluation(st.evaluatedAt(), st.configHash(),
                            st.canonicalHash(), st.rulesFingerprint(), parsed));
                }
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "[COMPLIANCE] stored evaluations not loaded: {0}", e.getMessage());
            }
            loaded = true;
        }
    }

    /** Evaluates every target not answered by the cache, {@link #PARALLELISM} at a time; results keyed by device id. */
    /**
     * @param background false for a screen request: a device with any stored evaluation is answered from it at once
     *     (counted in {@code pendingOut} when it no longer matches the current configuration or rule set, the
     *     background pass catches it up); only a device never evaluated is evaluated in the request.
     */
    Map<String, Map<String, Object>> evaluateAll(List<Target> evaluated, boolean background, int[] pendingOut) {
        ensureLoaded();
        refreshRulesFingerprint();
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        List<Target> misses = new ArrayList<>();
        for (Target t : evaluated) {
            Map<String, Object> hit = cachedResult(t.deviceId(), t.canonicalHash());
            CachedEvaluation previous = evaluationCache.get(t.deviceId());
            if (hit == null && !background && previous != null) {
                // a configuration or rule change the background has not caught up with yet; the backstop age alone
                // (same configuration, same rules) is not pending -- that result is still exact
                boolean exact = t.canonicalHash().isPresent() && t.canonicalHash().get().equals(previous.canonicalHash())
                        && rulesFingerprint.equals(previous.rulesFingerprint());
                if (!exact) {
                    pendingOut[0]++;
                }
                out.put(t.deviceId(), previous.result());
            } else if (hit != null) {
                out.put(t.deviceId(), hit);
            } else {
                misses.add(t);
                out.put(t.deviceId(), null); // keep the caller's order
            }
        }
        long t0 = System.nanoTime();
        if (misses.size() <= 1 || parallelism == 1) {
            misses.forEach(t -> out.put(t.deviceId(), evaluateDevice(t.deviceId(), t.canonicalHash())));
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, misses.size()));
            try {
                List<Future<Map<String, Object>>> futures = new ArrayList<>();
                for (Target t : misses) {
                    futures.add(pool.submit(() -> evaluateDevice(t.deviceId(), t.canonicalHash())));
                }
                for (int i = 0; i < misses.size(); i++) {
                    try {
                        out.put(misses.get(i).deviceId(), futures.get(i).get());
                    } catch (Exception e) {
                        out.put(misses.get(i).deviceId(), evaluateDevice(misses.get(i).deviceId(), misses.get(i).canonicalHash()));
                    }
                }
            } finally {
                pool.shutdown();
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (ms > 500) {
            LOG.log(System.Logger.Level.INFO, "[COMPLIANCE_TIMING] {0} cached, {1} evaluated in {2} ms (text read {3} ms, service call {4} ms, summed)",
                    evaluated.size() - misses.size(), misses.size(), ms, textNanos.getAndSet(0) / 1_000_000, callNanos.getAndSet(0) / 1_000_000);
        }
        return out;
    }

    private static final System.Logger LOG = System.getLogger(ComplianceService.class.getName());

    private List<Target> allDevices() {
        long t0 = System.nanoTime();
        List<Target> list = targets.get();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (ms > 500) {
            LOG.log(System.Logger.Level.INFO, "[COMPLIANCE_TIMING] device list {0} ms ({1} devices)", ms, list.size());
        }
        return list;
    }

    private static List<Target> evaluable(List<Target> all) {
        return all.stream()
                .filter(d -> "check_point".equalsIgnoreCase(d.vendor()) || "palo_alto".equalsIgnoreCase(d.vendor())
                        || "fortinet".equalsIgnoreCase(d.vendor()) || "cisco_asa".equalsIgnoreCase(d.vendor()))
                .toList();
    }

    /** A cached result for an unchanged configuration under an unchanged rule set, younger than the backstop age. */
    private Map<String, Object> cachedResult(String deviceId, Optional<String> knownCanonicalHash) {
        CachedEvaluation cached = evaluationCache.get(deviceId);
        if (cached != null && knownCanonicalHash.isPresent() && knownCanonicalHash.get().equals(cached.canonicalHash())
                && rulesFingerprint.equals(cached.rulesFingerprint())
                && cached.timestamp().isAfter(clock.get().minus(maxAge))) {
            return cached.result();
        }
        return null;
    }

    /**
     * The compliance service's rule-set identity (catalog version and control count from its health endpoint), read at
     * most once a minute. A change invalidates every cached evaluation; when it cannot be read the last value stands.
     */
    private void refreshRulesFingerprint() {
        // rate-limited once known; while still unknown (the compliance service not up yet at start) every pass retries,
        // so evaluations are never stored under an empty fingerprint and redone a minute later
        if (!rulesFingerprint.isEmpty() && rulesFingerprintAt.isAfter(clock.get().minusSeconds(60))) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(complianceServiceUrl + "/healthz"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                // order-independent: the service builds controls_by_vendor with Map.of, whose order changes per JVM start
                java.util.TreeMap<String, String> byVendor = new java.util.TreeMap<>();
                root.path("controls_by_vendor").fields().forEachRemaining(e -> byVendor.put(e.getKey(), e.getValue().asText()));
                rulesFingerprint = root.path("catalog_version").asText("") + "|" + root.path("controls_count").asText("")
                        + "|" + byVendor;
            }
        } catch (Exception ignored) {
            // unreadable: keep the last fingerprint (an unreachable service also fails every evaluation below)
        }
        rulesFingerprintAt = clock.get();
    }

    public List<Map<String, Object>> getControls() {
        return getControls(fetchCatalog());
    }

    private List<Map<String, Object>> getControls(List<Map<String, Object>> catalog) {
        List<Target> evaluated = evaluable(allDevices());

        // One evaluation per device, computed before the catalog loop. Measured live (2026-09-22): the
        // per-control loop below used to call evaluateDevice() for every control x every device, and
        // each call read and decrypted that device's sanitized artefact before consulting the cache --
        // ~50 controls x ~90 collected devices, several minutes per GET, so the screen never loaded.
        Map<String, Map<String, Object>> evaluationByDevice = new LinkedHashMap<>();
        evaluateAll(evaluated, false, new int[1]).forEach((id, eval) -> {
            if (eval != null) {
                evaluationByDevice.put(id, eval);
            }
        });

        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> c : catalog) {
            String controlId = String.valueOf(c.get("id"));
            String title = String.valueOf(c.get("title"));
            String desc = c.get("rationale") == null ? null : String.valueOf(c.get("rationale"));
            String severity = String.valueOf(c.get("severity"));
            // The catalog carries no category today; never turn its absence into the text "null".
            String category = c.get("category") == null ? null : String.valueOf(c.get("category"));
            Object frameworks = c.get("frameworks");

            int targetDevices = 0;
            int passCount = 0;
            int failCount = 0;
            int unavailCount = 0;
            List<String> affectedDevices = new ArrayList<>();
            java.util.Set<String> vendors = new java.util.TreeSet<>();
            String missingReason = null;

            for (var dev : evaluated) {
                Map<String, Object> eval = evaluationByDevice.get(dev.deviceId());
                if (eval == null) continue;

                List<Map<String, Object>> items = getItems(eval);
                for (Map<String, Object> item : items) {
                    if (controlId.equals(item.get("controlId"))) {
                        CheckOutcome outcome = checkOutcome(item.get("displayStatus"));
                        if (outcome == null) {
                            // Control does not apply to this device's vendor platform
                            break;
                        }
                        targetDevices++;
                        vendors.add(dev.vendor());
                        switch (outcome) {
                            case PASS -> passCount++;
                            case FAIL -> {
                                failCount++;
                                dev.hostname().ifPresent(affectedDevices::add);
                            }
                            case UNAVAILABLE -> {
                                unavailCount++;
                                if (missingReason == null && item.get("message") != null) {
                                    missingReason = String.valueOf(item.get("message"));
                                }
                            }
                        }
                        break;
                    }
                }
            }

            double compliancePct = targetDevices > 0 ? Math.round((passCount * 100.0 / targetDevices) * 10.0) / 10.0 : 0.0;
            String status = unavailCount > 0 && passCount == 0 && failCount == 0 ? "DATA_UNAVAILABLE" :
                    (failCount > 0 ? "FAIL" : (passCount > 0 ? "PASS" : "NOT_APPLICABLE"));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("control_id", controlId);
            row.put("title", title);
            row.put("description", desc);
            row.put("family", familyOf(frameworks));
            row.put("severity", severity);
            row.put("category", category);
            row.put("frameworks", frameworks);
            row.put("status", status);
            row.put("compliance_pct", compliancePct);
            row.put("target_device_count", targetDevices);
            row.put("pass_count", passCount);
            row.put("fail_count", failCount);
            row.put("data_unavailable_count", unavailCount);
            row.put("missing_reason", missingReason);
            row.put("affected_devices", affectedDevices);
            // Two vendors' checks can share a title: the vendor keeps them apart on screen (PO review 2026-09-25).
            row.put("vendors", new ArrayList<>(vendors));

            result.add(row);
        }

        return result;
    }

    private static String familyOf(Object frameworks) {
        if (frameworks instanceof List<?> mappings) {
            for (Object mapping : mappings) {
                if (mapping instanceof Map<?, ?> m && "NIST_800_53".equals(m.get("framework"))) {
                    Object ref = m.get("reference");
                    if (ref == null) ref = m.get("clauseId");
                    if (ref instanceof String s && s.matches("^[A-Z]{2}-.*")) return s.substring(0, 2);
                }
            }
        }
        return null;
    }

    public Map<String, Object> getControlDetail(String controlId) {
        List<Map<String, Object>> catalog = fetchCatalog();
        Map<String, Object> control = catalog.stream()
                .filter(c -> controlId.equals(c.get("id"))).findFirst().orElse(null);
        if (control == null) return null;
        Map<String, Object> summary = getControls(catalog).stream()
                .filter(c -> controlId.equals(c.get("control_id"))).findFirst().orElseThrow();
        List<Map<String, Object>> expected = new ArrayList<>();
        Object bindings = control.get("bindings");
        if (bindings instanceof List<?> list) for (Object entry : list) {
            if (entry instanceof Map<?, ?> binding) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("vendor", binding.get("vendor"));
                line.put("text", assertionText(binding.get("assertion")));
                expected.add(line);
            }
        }
        boolean secret = isSecretEvidence(bindings);
        List<Map<String, Object>> devices = new ArrayList<>();
        List<Target> targets = evaluable(allDevices());
        Map<String, Map<String, Object>> evaluations = evaluateAll(targets, false, new int[1]);
        for (Target target : targets) {
            Map<String, Object> evaluation = evaluations.get(target.deviceId());
            if (evaluation == null) continue;
            for (Map<String, Object> item : getItems(evaluation)) {
                if (!controlId.equals(item.get("controlId")) || "NOT_APPLICABLE".equals(item.get("displayStatus"))) continue;
                Map<String, Object> device = new LinkedHashMap<>();
                device.put("device_id", target.deviceId());
                device.put("hostname", target.hostname().orElse(null));
                device.put("vendor", target.vendor());
                device.put("status", item.get("displayStatus"));
                device.put("observed_value", secret ? null : item.get("observedValue"));
                device.put("guidance", item.get("guidance"));
                device.put("message", secret ? "Evidence withheld" : item.get("message"));
                devices.add(device);
                break;
            }
        }
        devices.sort(java.util.Comparator.comparingInt(d -> switch (String.valueOf(d.get("status"))) {
            case "FAIL" -> 0;
            case "PASS" -> 2;
            default -> 1;
        }));
        Map<String, Object> detail = new LinkedHashMap<>();
        for (String key : List.of("control_id", "title", "severity", "status", "frameworks")) detail.put(key, summary.get(key));
        detail.put("rationale", summary.get("description"));
        detail.put("expected", expected);
        detail.put("devices", devices);
        return detail;
    }

    private static boolean isSecretEvidence(Object bindings) {
        if (!(bindings instanceof List<?> list)) return false;
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> binding) || !(binding.get("evidenceRequirement") instanceof Map<?, ?> evidence)) continue;
            String path = String.valueOf(evidence.get("settingKey")).toLowerCase(Locale.ROOT);
            if (path.matches(".*(community|pre.?shared|password.hash|expert.hash|grub2.hash|private.key|secret|key.material).*")) return true;
        }
        return false;
    }

    static String assertionText(Object assertion) {
        if (!(assertion instanceof Map<?, ?> rule)) return "see benchmark";
        String target = String.valueOf(rule.get("targetString"));
        String number = String.valueOf(rule.get("targetNumber"));
        Object rawList = rule.get("targetList");
        String values = rawList instanceof List<?> list ? String.join(", ", list.stream().map(String::valueOf).toList()) : "";
        return switch (String.valueOf(rule.get("op"))) {
            case "gte", "count_gte" -> "at least " + number;
            case "lte", "count_lte" -> "at most " + number;
            case "equals" -> "set to " + target;
            case "not_equals" -> "not set to " + target;
            case "present" -> "configured";
            case "absent" -> "not configured";
            case "in" -> "one of: " + values;
            case "matches", "any_match" -> "matches the required pattern";
            case "not_match", "none_match" -> "does not match the prohibited pattern";
            case "not_in" -> "not one of: " + values;
            default -> "see benchmark";
        };
    }

    public Map<String, Object> getDeviceCompliance(String deviceId) {
        Optional<DeviceRecord> device = deviceRepository.find(deviceId);
        if (device.isEmpty()) {
            return Map.of("error", "NOT_FOUND", "message", "Device not found");
        }

        Map<String, Object> eval = evaluateDevice(deviceId);
        if (eval == null) {
            return Map.of("error", "EVALUATION_FAILED", "message", "Could not evaluate compliance for device");
        }

        Map<String, Object> response = new LinkedHashMap<>(eval);
        response.put("items", publicItems(eval, fetchCatalog()));
        response.put("device_id", deviceId);
        String hostname = deviceRepository.findConfirmFacts(deviceId)
                .flatMap(com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts::observedHostname)
                .orElse(deviceId);
        response.put("hostname", hostname);
        response.put("vendor", device.get().vendorHint());
        return response;
    }

    static List<Map<String, Object>> publicItems(Map<String, Object> evaluation, List<Map<String, Object>> catalog) {
        return getItems(evaluation).stream().map(item -> {
            Map<String, Object> projected = new LinkedHashMap<>(item);
            Map<String, Object> control = catalog.stream()
                    .filter(c -> java.util.Objects.equals(c.get("id"), item.get("controlId"))).findFirst().orElse(null);
            // A missing catalog cannot establish that an observed value is safe to export.
            if (control == null || isSecretEvidence(control.get("bindings"))) {
                projected.put("observedValue", null);
                projected.put("message", "Evidence withheld");
            }
            return projected;
        }).toList();
    }

    public Map<String, Object> evaluateDevice(String deviceId) {
        return evaluateDevice(deviceId, Optional.empty());
    }

    /**
     * @param knownCanonicalHash the latest configuration run's canonical hash when the caller already
     *     holds it -- lets a fresh cached evaluation be returned without reading and decrypting the
     *     sanitized artefact at all; the artefact is read only when the cache cannot answer.
     */
    public Map<String, Object> evaluateDevice(String deviceId, Optional<String> knownCanonicalHash) {
        Map<String, Object> hit = cachedResult(deviceId, knownCanonicalHash);
        if (hit != null) {
            return hit;
        }
        CachedEvaluation cached = evaluationCache.get(deviceId);

        Optional<DeviceRecord> device = deviceRepository.find(deviceId);
        String vendor = device.map(DeviceRecord::vendorHint).orElse("check_point");

        long tText = System.nanoTime();
        Optional<String> sanitizedConfig = sanitizedText.apply(deviceId);
        textNanos.addAndGet(System.nanoTime() - tText);
        String configText = sanitizedConfig.orElse("");

        if (cached != null && cached.configHash().equals(String.valueOf(configText.hashCode()))
                && rulesFingerprint.equals(cached.rulesFingerprint()) && cached.timestamp().isAfter(clock.get().minus(maxAge))) {
            return cached.result();
        }

        String failureKey = deviceId + "|" + configText.hashCode() + "|" + rulesFingerprint;
        Instant lastFailure = failedAt.get(failureKey);
        boolean skipCall = lastFailure != null && lastFailure.isAfter(clock.get().minus(FAILURE_RETRY));
        try {
            if (skipCall) {
                throw new IllegalStateException("evaluation failed recently; retried after " + FAILURE_RETRY);
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(complianceServiceUrl + "/api/v1/compliance/evaluate"))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-Nexus-Device-Id", deviceId)
                    .header("X-Nexus-Vendor", vendor)
                    .POST(HttpRequest.BodyPublishers.ofString(configText, StandardCharsets.UTF_8))
                    .build();

            long tCall = System.nanoTime();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            callNanos.addAndGet(System.nanoTime() - tCall);
            if (response.statusCode() == 200) {
                Map<String, Object> parsed = mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
                Instant at = clock.get();
                evaluationCache.put(deviceId, new CachedEvaluation(at, String.valueOf(configText.hashCode()),
                        knownCanonicalHash.orElse(null), rulesFingerprint, parsed));
                try {
                    store.save(new ComplianceEvaluationStore.Stored(deviceId, knownCanonicalHash.orElse(null),
                            String.valueOf(configText.hashCode()), rulesFingerprint, at, response.body()));
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "[COMPLIANCE] evaluation not stored: {0}", e.getMessage());
                }
                return parsed;
            }
            failedAt.put(failureKey, clock.get());
        } catch (Exception e) {
            if (!skipCall) {
                failedAt.put(failureKey, clock.get());
            }
        }

        // Return empty evaluation if microservice unreachable and no config
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("deviceId", deviceId);
        fallback.put("vendor", vendor);
        fallback.put("totalAssigned", 24);
        fallback.put("passCount", 0);
        fallback.put("failCount", 0);
        fallback.put("dataUnavailableCount", 24);
        fallback.put("observedCompliance", 0.0);
        fallback.put("evidenceCoverage", 0.0);
        fallback.put("assuredCompliance", 0.0);
        fallback.put("items", List.of());
        return fallback;
    }

    private List<Map<String, Object>> fetchCatalog() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(complianceServiceUrl + "/api/v1/compliance/catalog"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                if (root.has("controls")) {
                    return mapper.readValue(root.get("controls").toString(),
                            new TypeReference<List<Map<String, Object>>>() {});
                }
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    /** The four framework cards; any other framework (or none) is on no card rather than folded into one. */
    private static String mapFrameworkName(String raw) {
        if (raw == null) return null;
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "CIS" -> "CIS Benchmark";
            case "PCI_DSS" -> "PCI-DSS v4.0.1";
            case "NIST_800_53" -> "NIST SP 800-53";
            case "FINANCIAL_BASELINE" -> "Financial Baseline";
            default -> null;
        };
    }

    private static Map<String, Object> emptyOverview(int totalDevices) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("total_firewalls", totalDevices);
        overview.put("evaluated_firewalls", 0);
        overview.put("assured_compliance_pct", 0.0);
        overview.put("evidence_coverage_pct", 0.0);
        overview.put("observed_compliance_pct", 0.0);
        overview.put("critical_deficiencies", 0);
        overview.put("data_gaps", 0);
        overview.put("control_checks", 0);
        // No evaluation: every framework has no check at all (it used to report 24/16/20/10 invented checks).
        overview.put("frameworks", aggregateChecks(List.of()).get("frameworks"));
        return overview;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getItems(Map<String, Object> map) {
        Object items = map.get("items");
        return items instanceof List ? (List<Map<String, Object>>) items : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getFrameworks(Map<String, Object> item) {
        Object fw = item.get("frameworks");
        return fw instanceof List ? (List<Map<String, Object>>) fw : List.of();
    }

}
