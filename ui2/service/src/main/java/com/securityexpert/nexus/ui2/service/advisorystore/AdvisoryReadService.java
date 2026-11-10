package com.securityexpert.nexus.ui2.service.advisorystore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository;
import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.FeedRun;
import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredAdvisory;
import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredKnownExploited;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryCore;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.VerdictKind;
import com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryEstate.AdvisoryRow;
import com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryEstate.DeviceAssessment;
import com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryEstate.DeviceFacts;
import com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryEstate.DeviceVerdict;
import com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryEstate.SourceState;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleProjection;

/**
 * The read interface of advisory matching, part 1 (docs/design/ADVISORY_MATCHING_CONTRACT.md section 8). Verdicts are
 * computed here, when read, from one load of the stored advisories, rules and known-exploited set (section 5); nothing
 * is cached or stored. Advisory text is public data and is returned as plain strings; advisory links are returned only
 * when they are {@code https} and on the source's link host list (rule 7). Device names are returned under the key
 * {@code hostname} next to {@code device_id}, which the response masking advice pseudonymizes for the replay viewer exactly
 * as it does for the Lifecycle fleet rows.
 *
 * <p>When the advisory core bean is absent (the branch started without the matching lane) the list is empty, the sources
 * table is still real, and each device reads {@code NOT_COVERED} with the reason {@code NO_COMPARATOR}.</p>
 */
@Service
public final class AdvisoryReadService {

    private final AdvisoryRepository repository;
    private final DeviceRepository devices;
    private final Supplier<Optional<AdvisoryCore>> core;

    @Autowired
    public AdvisoryReadService(AdvisoryRepository repository, DeviceRepository devices, ObjectProvider<AdvisoryCore> core) {
        this(repository, devices, () -> Optional.ofNullable(core.getIfAvailable()));
    }

    public AdvisoryReadService(AdvisoryRepository repository, DeviceRepository devices, Supplier<Optional<AdvisoryCore>> core) {
        this.repository = repository;
        this.devices = devices;
        this.core = core;
    }

    /** The device facts the verdicts need, from the device directory (the same stored columns the Lifecycle view reads). */
    public static DeviceFacts facts(DeviceSummaryRecord d) {
        return new DeviceFacts(d.deviceId(), d.vendorHint(), d.role(), d.observedModel().orElse(null),
                d.observedSoftwareVersion().orElse(null), d.observedHostname().orElse(null));
    }

    /** The Vulnerabilities object of each device (key: device id) for the Lifecycle fleet payload. */
    public Map<String, Map<String, Object>> vulnerabilities(List<DeviceFacts> facts, Instant now) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Optional<AdvisoryCore> available = core.get();
        if (available.isEmpty()) {
            for (DeviceFacts d : facts) result.put(d.deviceId(), assessmentJson(new DeviceAssessment(
                    AdvisoryEstate.NOT_COVERED, "NO_COMPARATOR", null, 0, 0, 0, 0, 0, null, null)));
            return result;
        }
        AdvisoryEstate estate = estate(available.get(), facts, sourceStates(now), now);
        estate.devices().forEach((id, a) -> result.put(id, assessmentJson(a)));
        return result;
    }

    /** {@code GET /api/v2/lifecycle/advisories}. */
    public Map<String, Object> list(Instant now) {
        var body = new LinkedHashMap<String, Object>();
        body.put("as_of", now.toString());
        Optional<AdvisoryCore> available = core.get();
        if (available.isEmpty()) {
            body.put("advisories", List.of());
            return body;
        }
        AdvisoryEstate estate = estate(available.get(), deviceFacts(), sourceStates(now), now);
        body.put("advisories", estate.advisories().stream().filter(AdvisoryRow::listed).sorted(AdvisoryEstate.LIST_ORDER)
                .map(AdvisoryReadService::advisoryJson).toList());
        return body;
    }

    /** {@code GET /api/v2/lifecycle/advisories/{id}}; empty for an unknown id. */
    public Optional<Map<String, Object>> detail(String advisoryId, Instant now) {
        Optional<AdvisoryCore> available = core.get();
        if (available.isEmpty()) return Optional.empty();
        AdvisoryEstate estate = estate(available.get(), deviceFacts(), sourceStates(now), now);
        return estate.advisories().stream().filter(r -> r.advisory().advisoryId().equals(advisoryId)).findFirst().map(row -> {
            var body = new LinkedHashMap<String, Object>();
            body.put("advisory", advisoryJson(row));
            body.put("devices", row.verdicts().stream().sorted(VERDICT_ORDER).map(v -> verdictJson(advisoryId, v)).toList());
            return body;
        });
    }

    /** {@code GET /api/v2/lifecycle/advisory-sources}: the four sources in display order, each with the basis of its age. */
    public Map<String, Object> sources(Instant now) {
        Map<String, SourceState> states = sourceStates(now);
        Map<String, FeedRun> latest = new HashMap<>();
        repository.latestRuns().forEach(r -> latest.put(r.source(), r));
        var rows = new ArrayList<Map<String, Object>>();
        for (String key : AdvisorySources.KEYS) {
            SourceState s = states.get(key);
            FeedRun run = latest.get(key);
            var row = new LinkedHashMap<String, Object>();
            row.put("source", key);
            row.put("last_run_at", run == null ? null : text(run.endedAt() != null ? run.endedAt() : run.startedAt()));
            row.put("outcome", run == null ? null : run.outcome() == null ? "RUNNING" : run.outcome());
            row.put("reason", run == null ? null : run.reason());
            row.put("advisories", s.advisories());
            row.put("needs_review", s.needsReview());
            row.put("oldest", text(s.oldest()));
            row.put("newest", text(s.newest()));
            row.put("age_days", s.ageDays());
            row.put("age_state", s.ageState());
            rows.add(row);
        }
        return Map.of("sources", rows);
    }

    // ---- loading ------------------------------------------------------------------------------------------------

    private List<DeviceFacts> deviceFacts() {
        return devices.listAll().stream().map(AdvisoryReadService::facts).toList();
    }

    private AdvisoryEstate estate(AdvisoryCore advisoryCore, List<DeviceFacts> facts, Map<String, SourceState> sources, Instant now) {
        List<StoredAdvisory> advisories = repository.advisories(Optional.empty());
        List<StoredKnownExploited> kev = repository.knownExploited();
        return AdvisoryEstate.evaluate(advisoryCore, advisories, kev, sources, facts);
    }

    /** One state per source key: counts and dates from the store, the age from the last OK or PARTIAL run. */
    Map<String, SourceState> sourceStates(Instant now) {
        Map<String, AdvisoryRepository.SourceSummary> summaries = new HashMap<>();
        repository.sourceSummaries().forEach(s -> summaries.put(s.source(), s));
        Map<String, FeedRun> good = new HashMap<>();
        repository.lastGoodRuns().forEach(r -> good.put(r.source(), r));
        List<StoredKnownExploited> kev = repository.knownExploited();
        Map<String, SourceState> states = new LinkedHashMap<>();
        for (String key : AdvisorySources.KEYS) {
            var summary = summaries.get(key);
            int count = summary == null ? 0 : summary.advisories();
            LocalDate oldest = summary == null ? null : summary.oldest();
            LocalDate newest = summary == null ? null : summary.newest();
            if (AdvisorySources.KEYS.get(3).equals(key)) {
                // The known-exploited catalog holds entries, not advisories: its count and dates are those of the entries.
                count = kev.size();
                oldest = kev.stream().map(StoredKnownExploited::dateAdded).min(Comparator.naturalOrder()).orElse(null);
                newest = kev.stream().map(StoredKnownExploited::dateAdded).max(Comparator.naturalOrder()).orElse(null);
            }
            FeedRun run = good.get(key);
            Instant lastGood = run == null ? null : run.endedAt();
            Long age = AdvisoryEstate.ageDays(lastGood, now);
            states.put(key, new SourceState(key, lastGood, age, AdvisoryEstate.ageState(age), count,
                    summary == null ? 0 : summary.needsReview(), oldest, newest));
        }
        return states;
    }

    // ---- JSON ---------------------------------------------------------------------------------------------------

    static Map<String, Object> assessmentJson(DeviceAssessment a) {
        var body = new LinkedHashMap<String, Object>();
        body.put("state", a.state());
        body.put("reason", a.reason());
        body.put("product_key", a.productKey());
        body.put("affected", a.affected());
        body.put("affected_exploited", a.affectedExploited());
        body.put("affected_critical_high", a.affectedCriticalHigh());
        body.put("not_evaluable", a.notEvaluable());
        body.put("fixed", a.fixed());
        body.put("worst_severity", a.worstSeverity());
        if (a.compared() == null) {
            body.put("compared", null);
        } else {
            var c = new LinkedHashMap<String, Object>();
            c.put("source", a.compared().source());
            c.put("advisories", a.compared().advisories());
            c.put("oldest", text(a.compared().oldest()));
            c.put("newest", text(a.compared().newest()));
            c.put("loaded_at", text(a.compared().lastGoodAt()));
            c.put("age_days", a.compared().ageDays());
            body.put("compared", c);
        }
        return body;
    }

    static Map<String, Object> advisoryJson(AdvisoryRow row) {
        StoredAdvisory a = row.advisory();
        var body = new LinkedHashMap<String, Object>();
        body.put("advisory_id", a.advisoryId());
        body.put("source", a.source());
        body.put("title", a.title());
        body.put("severity", a.severity());
        body.put("cvss_score", a.cvssScore());
        body.put("cve_ids", a.cveIds());
        body.put("exploited", row.exploited());
        body.put("exploited_since", text(row.exploitedSince()));
        body.put("published_on", text(a.publishedOn()));
        body.put("updated_on", text(a.updatedOn()));
        body.put("source_url", AdvisorySources.linkAllowed(a.source(), a.sourceUrl()) ? a.sourceUrl() : null);
        body.put("parse_state", a.parseState());
        body.put("parse_note", a.parseNote());
        body.put("devices", Map.of("affected", row.counts().affected(), "fixed", row.counts().fixed(),
                "not_affected", row.counts().notAffected(), "not_evaluable", row.counts().notEvaluable()));
        return body;
    }

    private static final Comparator<DeviceVerdict> VERDICT_ORDER = Comparator
            .comparingInt((DeviceVerdict v) -> rank(v.kind()))
            .thenComparing(v -> v.device().hostname() == null ? "" : v.device().hostname())
            .thenComparing(v -> v.device().deviceId());

    private static int rank(VerdictKind kind) {
        return switch (kind) { case AFFECTED -> 0; case NOT_EVALUABLE -> 1; case FIXED -> 2; case NOT_AFFECTED -> 3; default -> 4; };
    }

    /**
     * One device row of an advisory. It carries the advisory id so that the response masking advice lets the version and
     * the vendor's fix text through for the masked persona (a four-part version would otherwise be masked as an address);
     * the hostname next to the device id is pseudonymized as on every other screen.
     */
    static Map<String, Object> verdictJson(String advisoryId, DeviceVerdict v) {
        var body = new LinkedHashMap<String, Object>();
        body.put("advisory_id", advisoryId);
        body.put("device_id", v.device().deviceId());
        body.put("hostname", v.device().hostname());
        body.put("vendor", v.device().vendorHint() == null ? null : LifecycleProjection.vendor(v.device().vendorHint()));
        body.put("version", v.device().version());
        body.put("verdict", v.kind().name());
        body.put("basis", v.basis());
        body.put("fixed_text", v.fixedText());
        return body;
    }

    private static String text(LocalDate date) { return date == null ? null : date.toString(); }

    private static String text(Instant instant) { return instant == null ? null : instant.toString(); }
}
