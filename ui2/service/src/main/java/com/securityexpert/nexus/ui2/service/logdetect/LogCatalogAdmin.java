package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.CatalogRow;
import com.securityexpert.nexus.ui2.service.logdetect.LogFindingReadService.Answer;

/**
 * The admin side of the template catalog (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 6.5 and 9, CLI parity
 * {@code log-catalog}, {@code log-catalog-diff}, {@code log-catalog-freeze}): list versions, show a diff, start the
 * offline build of a DRAFT and freeze a DRAFT. Reading uses {@code log_detection_settings_read}, building and freezing
 * {@code log_detection_settings_write} (security_admin, never aiview): the same actions as the switch, no new action id.
 * Answers carry versions, ids, digests and counts, never template words beyond the stored vocabulary form.
 *
 * <p>The build is offline and long: it runs on one background thread, one at a time; the answer to the start is
 * {@code STARTED} and the outcome is read back from the list ({@code build}).</p>
 */
public final class LogCatalogAdmin {
    private static final Pattern VERSION = Pattern.compile("cat-[0-9]{1,6}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LogCatalogService catalogs;
    private final LogTemplateRepository repository;
    private final Executor executor;
    private final Clock clock;
    private final AtomicBoolean building = new AtomicBoolean();
    private final AtomicReference<Map<String, Object>> lastBuild = new AtomicReference<>(Map.of("state", "IDLE"));

    public LogCatalogAdmin(LogCatalogService catalogs, LogTemplateRepository repository, Executor executor, Clock clock) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Answer list() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalogs", catalogs.catalogs());
        body.put("active", repository.activeCatalog().map(CatalogRow::catalogVersion).orElse(null));
        body.put("build", lastBuild.get());
        body.put("note", "only a FROZEN version is matched online, and only while the P2 switch is on");
        return Answer.ok(body);
    }

    /** The diff of {@code version} against {@code from}; without {@code from}, the diff stored when it was frozen. */
    public Answer diff(String version, String from) {
        if (version == null || !VERSION.matcher(version).matches() || (from != null && !VERSION.matcher(from).matches())) {
            return Answer.refused(400, "CATALOG_VERSION_INVALID", "A catalog version is cat- and a number.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (from == null) {
            Optional<CatalogRow> row = repository.catalog(version);
            if (row.isEmpty()) return Answer.refused(404, "CATALOG_NOT_FOUND", "No such catalog version.");
            body.put("catalog_version", version);
            body.put("state", row.get().state());
            body.put("previous_version", row.get().previousVersion());
            body.put("manifest", parse(row.get().manifestJson()));
            body.put("diff", parse(row.get().diffJson()));
            body.put("class_counts", catalogs.classCounts(version));
            return Answer.ok(body);
        }
        Optional<Map<String, Object>> diff = catalogs.diff(from, version);
        if (diff.isEmpty()) return Answer.refused(404, "CATALOG_NOT_FOUND", "No such catalog version.");
        body.put("diff", diff.get());
        return Answer.ok(body);
    }

    /** Starts the offline build of a DRAFT over {@code from}..{@code to} (device-local days, 7 to 92 days). */
    public Answer startBuild(String actorFingerprint, Map<String, Object> request) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return Answer.refused(403, "LOG_DETECTION_SETTINGS_NOT_PERMITTED", "No authenticated session.");
        }
        LocalDate from;
        LocalDate to;
        try {
            if (request == null || !(request.get("from") instanceof String f) || !(request.get("to") instanceof String t)) {
                return Answer.refused(400, "CATALOG_WINDOW_INVALID", "The body must carry from and to (yyyy-mm-dd).");
            }
            for (String key : request.keySet()) {
                if (!key.equals("from") && !key.equals("to")) {
                    return Answer.refused(400, "CATALOG_WINDOW_INVALID", key + ": not a catalog build field");
                }
            }
            from = LocalDate.parse(f);
            to = LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            return Answer.refused(400, "CATALOG_WINDOW_INVALID", "from and to are dates (yyyy-mm-dd).");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        if (days < LogCatalogService.MIN_WINDOW_DAYS || days > LogCatalogService.MAX_WINDOW_DAYS) {
            return Answer.refused(400, "CATALOG_WINDOW_INVALID", "The training window is 7 to 92 days.");
        }
        if (!building.compareAndSet(false, true)) {
            return Answer.refused(409, "CATALOG_BUILD_RUNNING", "A catalog build is already running.");
        }
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("state", "RUNNING");
        started.put("from", from.toString());
        started.put("to", to.toString());
        started.put("started_at", clock.instant().toString());
        lastBuild.set(started);
        try {
            executor.execute(() -> run(actorFingerprint, from, to, started));
        } catch (RuntimeException e) {
            building.set(false);
            lastBuild.set(Map.of("state", "FAILED", "reason", "NOT_STARTED"));
            return Answer.refused(503, "CATALOG_BUILD_NOT_STARTED", "The build could not be started.");
        }
        return Answer.ok(started);
    }

    private void run(String actor, LocalDate from, LocalDate to, Map<String, Object> started) {
        Map<String, Object> done = new LinkedHashMap<>(started);
        try {
            LogCatalogService.BuildResult r = catalogs.build(actor, from, to, LogCatalogBuilder.Params.defaults());
            done.put("state", r.stored() ? "DONE" : "REFUSED");
            done.put("reason", r.reason());
            done.put("catalog_version", r.catalogVersion());
            done.put("templates", r.templates());
            done.put("chunks_read", r.chunksRead());
            done.put("chunks_skipped", r.chunksSkipped());
            done.put("digest", r.digest());
        } catch (RuntimeException e) {
            done.put("state", "FAILED");
            done.put("reason", e.getClass().getSimpleName());
        } finally {
            done.put("finished_at", clock.instant().toString());
            lastBuild.set(done);
            building.set(false);
        }
    }

    public Answer freeze(String actorFingerprint, String version) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return Answer.refused(403, "LOG_DETECTION_SETTINGS_NOT_PERMITTED", "No authenticated session.");
        }
        if (version == null || !VERSION.matcher(version).matches()) {
            return Answer.refused(400, "CATALOG_VERSION_INVALID", "A catalog version is cat- and a number.");
        }
        LogCatalogService.FreezeResult r = catalogs.freeze(actorFingerprint, version);
        if (!r.frozen()) {
            return switch (r.reason()) {
                case "CATALOG_NOT_FOUND" -> Answer.refused(404, r.reason(), "No such catalog version.");
                default -> Answer.refused(409, r.reason(), "Only a DRAFT version can be frozen; a frozen version is never edited.");
            };
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalog_version", r.catalog().catalogVersion());
        body.put("state", r.catalog().state());
        body.put("previous_version", r.catalog().previousVersion());
        body.put("digest", r.catalog().digest());
        body.put("diff", parse(r.catalog().diffJson()));
        return Answer.ok(body);
    }

    private static Object parse(String json) {
        if (json == null) return null;
        try {
            return JSON.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
