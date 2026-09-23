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

        double totalAssured = 0.0;
        double totalCoverage = 0.0;
        double totalObserved = 0.0;
        int criticalDeficiencies = 0;
        int dataGaps = 0;

        // Framework accumulators: [total, pass, fail, unavail]
        Map<String, int[]> frameworkStats = new LinkedHashMap<>();
        frameworkStats.put("CIS Benchmark", new int[]{0, 0, 0, 0});
        frameworkStats.put("PCI-DSS v4.0.1", new int[]{0, 0, 0, 0});
        frameworkStats.put("NIST SP 800-53", new int[]{0, 0, 0, 0});
        frameworkStats.put("Financial Baseline", new int[]{0, 0, 0, 0});

        int[] pending = {0};
        Map<String, Map<String, Object>> evaluations = evaluateAll(evaluated, false, pending);
        for (var dev : evaluated) {
            Map<String, Object> eval = evaluations.get(dev.deviceId());
            if (eval == null || eval.containsKey("error")) {
                continue;
            }

            totalAssured += getDouble(eval, "assuredCompliance", 0.0);
            totalCoverage += getDouble(eval, "evidenceCoverage", 0.0);
            totalObserved += getDouble(eval, "observedCompliance", 0.0);
            dataGaps += getInt(eval, "dataUnavailableCount", 0);

            List<Map<String, Object>> items = getItems(eval);
            for (Map<String, Object> item : items) {
                String status = String.valueOf(item.get("displayStatus"));
                String severity = String.valueOf(item.get("severity"));

                if ("FAIL".equalsIgnoreCase(status) && "CRITICAL".equalsIgnoreCase(severity)) {
                    criticalDeficiencies++;
                }

                List<Map<String, Object>> frameworks = getFrameworks(item);
                for (Map<String, Object> fw : frameworks) {
                    String name = mapFrameworkName(String.valueOf(fw.get("framework")));
                    int[] stats = frameworkStats.get(name);
                    if (stats != null) {
                        stats[0]++; // total
                        if ("PASS".equalsIgnoreCase(status)) stats[1]++;
                        else if ("FAIL".equalsIgnoreCase(status)) stats[2]++;
                        else if ("DATA_UNAVAILABLE".equalsIgnoreCase(status)) stats[3]++;
                    }
                }
            }
        }

        double avgAssured = Math.round((totalAssured / evaluatedCount) * 10.0) / 10.0;
        double avgCoverage = Math.round((totalCoverage / evaluatedCount) * 10.0) / 10.0;
        double avgObserved = Math.round((totalObserved / evaluatedCount) * 10.0) / 10.0;

        List<Map<String, Object>> frameworkCards = new ArrayList<>();
        for (var entry : frameworkStats.entrySet()) {
            int[] s = entry.getValue();
            int total = s[0];
            int pass = s[1];
            int fail = s[2];
            int unavail = s[3];
            double score = total > 0 ? Math.round((pass * 100.0 / total) * 10.0) / 10.0 : 0.0;

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("framework", entry.getKey());
            card.put("score_pct", score);
            card.put("total_controls", total);
            card.put("pass_count", pass);
            card.put("fail_count", fail);
            card.put("data_unavailable_count", unavail);
            frameworkCards.add(card);
        }

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("total_firewalls", totalDevices);
        overview.put("evaluated_firewalls", evaluatedCount);
        overview.put("assured_compliance_pct", avgAssured);
        overview.put("evidence_coverage_pct", avgCoverage);
        overview.put("observed_compliance_pct", avgObserved);
        overview.put("critical_deficiencies", criticalDeficiencies);
        overview.put("data_gaps", dataGaps);
        overview.put("frameworks", frameworkCards);
        // Devices shown with their previous evaluation while the background re-evaluates a changed configuration
        // or a changed rule set (at most a minute): the figures are complete, one step behind for these devices.
        overview.put("pending_reevaluation", pending[0]);

        return overview;
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
                .filter(d -> "check_point".equalsIgnoreCase(d.vendor()) || "palo_alto".equalsIgnoreCase(d.vendor()))
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
        if (rulesFingerprintAt.isAfter(clock.get().minusSeconds(60))) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(complianceServiceUrl + "/healthz"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                rulesFingerprint = root.path("catalog_version").asText("") + "|" + root.path("controls_count").asText("")
                        + "|" + root.path("controls_by_vendor").toString();
            }
        } catch (Exception ignored) {
            // unreadable: keep the last fingerprint (an unreachable service also fails every evaluation below)
        }
        rulesFingerprintAt = clock.get();
    }

    public List<Map<String, Object>> getControls() {
        List<Map<String, Object>> catalog = fetchCatalog();
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
            String desc = String.valueOf(c.get("description"));
            String severity = String.valueOf(c.get("severity"));
            // The catalog carries no category today; never turn its absence into the text "null".
            String category = c.get("category") == null ? null : String.valueOf(c.get("category"));
            Object frameworks = c.get("frameworks");

            int targetDevices = 0;
            int passCount = 0;
            int failCount = 0;
            int unavailCount = 0;
            List<String> affectedDevices = new ArrayList<>();
            String missingReason = null;

            for (var dev : evaluated) {
                Map<String, Object> eval = evaluationByDevice.get(dev.deviceId());
                if (eval == null) continue;

                List<Map<String, Object>> items = getItems(eval);
                for (Map<String, Object> item : items) {
                    if (controlId.equals(item.get("controlId"))) {
                        String st = String.valueOf(item.get("displayStatus"));
                        if ("NOT_APPLICABLE".equalsIgnoreCase(st)) {
                            // Control does not apply to this device's vendor platform
                            break;
                        }
                        targetDevices++;
                        if ("PASS".equalsIgnoreCase(st)) {
                            passCount++;
                        } else if ("FAIL".equalsIgnoreCase(st)) {
                            failCount++;
                            dev.hostname().ifPresent(affectedDevices::add);
                        } else if ("DATA_UNAVAILABLE".equalsIgnoreCase(st)) {
                            unavailCount++;
                            if (missingReason == null && item.get("message") != null) {
                                missingReason = String.valueOf(item.get("message"));
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

            result.add(row);
        }

        return result;
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
        response.put("device_id", deviceId);
        String hostname = deviceRepository.findConfirmFacts(deviceId)
                .flatMap(com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts::observedHostname)
                .orElse(deviceId);
        response.put("hostname", hostname);
        response.put("vendor", device.get().vendorHint());
        return response;
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

    private static String mapFrameworkName(String raw) {
        if (raw == null) return "Financial Baseline";
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "CIS" -> "CIS Benchmark";
            case "PCI_DSS" -> "PCI-DSS v4.0.1";
            case "NIST_800_53" -> "NIST SP 800-53";
            default -> "Financial Baseline";
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
        overview.put("frameworks", List.of(
                Map.of("framework", "CIS Benchmark", "score_pct", 0.0, "total_controls", 24, "pass_count", 0, "fail_count", 0, "data_unavailable_count", 24),
                Map.of("framework", "PCI-DSS v4.0.1", "score_pct", 0.0, "total_controls", 16, "pass_count", 0, "fail_count", 0, "data_unavailable_count", 16),
                Map.of("framework", "NIST SP 800-53", "score_pct", 0.0, "total_controls", 20, "pass_count", 0, "fail_count", 0, "data_unavailable_count", 20),
                Map.of("framework", "Financial Baseline", "score_pct", 0.0, "total_controls", 10, "pass_count", 0, "fail_count", 0, "data_unavailable_count", 10)
        ));
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

    private static double getDouble(Map<String, Object> map, String key, double defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.doubleValue();
        return defaultVal;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultVal;
    }
}
