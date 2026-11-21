package com.securityexpert.nexus.ui2.service.lifecyclesource;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleVendorRepository;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleVendorRepository.VendorEntry;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleMatcher;

/**
 * The two ways vendor lifecycle entries get in (docs/design/LIFECYCLE_CATALOG_SOURCES_CONTRACT.md section 3): a document
 * the advisory-fetch workload read online and hands over on the internal port, and a vendor page an operator saved and
 * uploads. Both go through the same parsers; both replace the stored entries of exactly the publications the document
 * stands for. Everything is untrusted: a value outside what the V219 constraints allow drops that entry, never the batch,
 * and the log carries counts and code words only.
 */
@Service
public final class LifecycleSourceService {

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleSourceService.class);

    public static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_COMPLETE_BYTES = 4096;
    /** An uploaded page: the vendor pages are 50 to 400 KB; 8 MiB leaves room and bounds the request. */
    public static final int MAX_UPLOAD_CHARS = 8 * 1024 * 1024;

    private static final Pattern CODE_WORD = Pattern.compile("^[A-Z0-9_]{1,40}$");
    private static final Pattern PRODUCT_KEY = Pattern.compile("^[A-Za-z0-9 ._/-]{1,60}$");
    private static final Pattern DOCUMENT_REF = Pattern.compile("^[A-Za-z0-9_.-]{1,80}$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The answer of an ingest or close call: an HTTP status and a fixed code word (null for success). */
    public enum Outcome {
        STORED(204, null),
        RUN_CLOSED(409, "LIFECYCLE_RUN_CLOSED"),
        COMPLETE_INVALID(400, "LIFECYCLE_COMPLETE_INVALID"),
        STORE_FAILED(500, "LIFECYCLE_STORE_FAILED");

        private final int status;
        private final String code;

        Outcome(int status, String code) {
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }

        public String code() { return code; }
    }

    /** What an upload did, or why it was refused ({@code error} is a code word, {@code message} a fixed sentence). */
    public record UploadResult(String error, String message, String uploadId, int parsed, int needsReview, int skipped) {
        static UploadResult refused(String error, String message) {
            return new UploadResult(error, message, null, 0, 0, 0);
        }
    }

    private final LifecycleVendorRepository repository;

    public LifecycleSourceService(LifecycleVendorRepository repository) {
        this.repository = repository;
    }

    /** @param url already checked by {@link LifecycleSources#feedDocumentAllowed}; {@code runId} by the controller */
    public Outcome ingest(String source, String runId, String url, byte[] document) {
        try {
            repository.openRun(runId, source);
            var existing = repository.run(runId, source);
            if (existing.isPresent() && existing.get().endedAt() != null) return Outcome.RUN_CLOSED;
            LifecyclePage.Result parsed;
            try {
                parsed = parse(LifecycleSources.feedPageForm(source, url), document, LifecycleSources.pathRef(url));
            } catch (RuntimeException e) {
                LOG.warn("lifecycle ingest source={} run={} code=PARSER_THREW", source, runId);
                repository.addToRun(runId, source, 1, 0, 1, 0, 0);
                return Outcome.STORED;
            }
            if (!parsed.recognised()) {
                // Many Cisco bulletins name no covered product (subscriptions, FTD): ignored. A Check Point or Palo Alto page
                // that is not recognised means the vendor changed the page: a rejected document, so the run is PARTIAL and says so.
                boolean mustRecognise = !"cisco_eol".equals(source);
                if (mustRecognise) LOG.warn("lifecycle ingest source={} run={} code=PAGE_NOT_RECOGNISED", source, runId);
                repository.addToRun(runId, source, 1, mustRecognise ? 0 : 1, mustRecognise ? 1 : 0, 0, 0);
                return Outcome.STORED;
            }
            String sha = sha256(document);
            var stored = new ArrayList<VendorEntry>();
            int rejected = 0;
            for (var entry : parsed.entries()) {
                var row = toStored(entry, "FEED", source, null, url, sha);
                if (row.isPresent()) stored.add(row.get()); else rejected++;
            }
            for (String ref : parsed.documentRefs()) {
                if (!DOCUMENT_REF.matcher(ref).matches()) continue;
                repository.replaceFeedDocument(source, ref, stored.stream().filter(e -> e.documentRef().equals(ref)).toList());
            }
            int review = (int) stored.stream().filter(e -> !LifecyclePage.PARSED.equals(e.parseState())).count();
            repository.addToRun(runId, source, 1, 0, rejected, stored.size(), review);
            LOG.info("lifecycle ingest source={} run={} stored={} needs_review={} rejected={}", source, runId, stored.size(), review, rejected);
            return Outcome.STORED;
        } catch (RuntimeException e) {
            LOG.error("lifecycle ingest source={} run={} code=STORE_FAILED type={}", source, runId, e.getClass().getSimpleName());
            return Outcome.STORE_FAILED;
        }
    }

    /**
     * Closes a run. FAILED when a certificate failed or nothing was received although documents were named; PARTIAL when a
     * document failed or an entry was rejected; OK otherwise (a run that only read the listings and found no bulletin
     * still confirms the source is reachable). Closing a closed run changes nothing and answers success.
     */
    public Outcome complete(String source, String runId, byte[] body) {
        int fetched;
        int failed;
        String reason;
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) return Outcome.COMPLETE_INVALID;
            JsonNode f = root.get("documents_fetched");
            JsonNode x = root.get("documents_failed");
            JsonNode r = root.get("reason");
            if (f == null || !f.isInt() || f.asInt() < 0 || f.asInt() > 1_000_000
                    || x == null || !x.isInt() || x.asInt() < 0 || x.asInt() > 1_000_000) return Outcome.COMPLETE_INVALID;
            if (r != null && !r.isNull() && !(r.isTextual() && CODE_WORD.matcher(r.asText()).matches())) return Outcome.COMPLETE_INVALID;
            fetched = f.asInt();
            failed = x.asInt();
            reason = r == null || r.isNull() ? null : r.asText();
        } catch (java.io.IOException | RuntimeException e) {
            return Outcome.COMPLETE_INVALID;
        }
        try {
            var existing = repository.run(runId, source);
            if (existing.isPresent() && existing.get().endedAt() != null) return Outcome.STORED;
            int received = existing.map(LifecycleVendorRepository.FeedRun::documentsReceived).orElse(0);
            int rejected = existing.map(LifecycleVendorRepository.FeedRun::rejected).orElse(0);
            String outcome = outcome(received, rejected, fetched, failed, reason);
            if (reason == null && !"OK".equals(outcome)) {
                reason = received == 0 ? "NOTHING_RECEIVED" : failed > 0 ? "SOME_DOCUMENTS_FAILED" : "SOME_ENTRIES_REJECTED";
            }
            repository.closeRun(runId, source, outcome, fetched, failed, reason);
            LOG.info("lifecycle run closed source={} run={} outcome={} received={} failed={}", source, runId, outcome, received, failed);
            return Outcome.STORED;
        } catch (RuntimeException e) {
            LOG.error("lifecycle run close source={} run={} code=STORE_FAILED type={}", source, runId, e.getClass().getSimpleName());
            return Outcome.STORE_FAILED;
        }
    }

    static String outcome(int received, int rejected, int fetched, int failed, String reason) {
        if ("TLS_VALIDATION_FAILED".equals(reason)) return "FAILED";
        if (received == 0) return fetched > 0 && failed == 0 && reason == null ? "OK" : "FAILED";
        return failed > 0 || rejected > 0 || reason != null ? "PARTIAL" : "OK";
    }

    /** An operator upload of a saved vendor page (contract section 3.2). Audited through the upload row. */
    public UploadResult upload(String pageForm, String page, String actor) {
        var form = pageForm == null ? null : LifecycleSources.PAGE_FORMS.get(pageForm);
        if (form == null) return UploadResult.refused("PAGE_FORM_UNKNOWN", "Choose one of the listed vendor pages");
        if (page == null || page.isBlank()) return UploadResult.refused("PAGE_EMPTY", "The uploaded file is empty");
        if (page.length() > MAX_UPLOAD_CHARS) return UploadResult.refused("PAGE_TOO_LARGE", "The uploaded file exceeds 8 MiB");
        byte[] bytes = page.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        LifecyclePage.Result parsed;
        try {
            parsed = parse(form.key(), bytes, "uploaded-bulletin");
        } catch (RuntimeException e) {
            LOG.warn("lifecycle upload form={} code=PARSER_THREW", form.key());
            return UploadResult.refused("PAGE_NOT_RECOGNISED", "The file is not a copy of the chosen vendor page");
        }
        if (!parsed.recognised() || parsed.entries().isEmpty()) {
            return UploadResult.refused("PAGE_NOT_RECOGNISED", "The file is not a copy of the chosen vendor page, or the page "
                    + "no longer has the table Overwatch reads; nothing was stored");
        }
        String uploadId = "up-" + HexFormat.of().formatHex(randomBytes());
        String sha = sha256(bytes);
        var stored = new ArrayList<VendorEntry>();
        int rejected = 0;
        for (var entry : parsed.entries()) {
            var row = toStored(entry, "OPERATOR_PAGE", form.source(), uploadId, form.canonicalUrl(), sha);
            if (row.isPresent()) stored.add(row.get()); else rejected++;
        }
        int review = (int) stored.stream().filter(e -> !LifecyclePage.PARSED.equals(e.parseState())).count();
        var refs = parsed.documentRefs().stream().filter(r -> DOCUMENT_REF.matcher(r).matches()).toList();
        repository.storeUpload(new LifecycleVendorRepository.Upload(uploadId, form.key(), form.vendor(), sha, stored.size() - review,
                review, parsed.rowsSkipped() + rejected, actor, Instant.now()), form.source(), refs, stored, actor);
        LOG.info("lifecycle upload form={} stored={} needs_review={} skipped={}", form.key(), stored.size(), review,
                parsed.rowsSkipped() + rejected);
        return new UploadResult(null, null, uploadId, stored.size() - review, review, parsed.rowsSkipped() + rejected);
    }

    /** The parser of a page form; the online path and the upload path use the same one. */
    static LifecyclePage.Result parse(String pageForm, byte[] page, String pathRef) {
        if (pageForm == null) return LifecyclePage.Result.notRecognised("unknown");
        return switch (pageForm) {
            case CiscoEolBulletinParser.PAGE_FORM -> CiscoEolBulletinParser.parse(page, pathRef);
            case PaloAltoEolParser.SOFTWARE_FORM -> PaloAltoEolParser.software(page);
            case PaloAltoEolParser.HARDWARE_FORM -> PaloAltoEolParser.hardware(page);
            case CheckPointLifecycleParser.PAGE_FORM -> CheckPointLifecycleParser.parse(page);
            default -> LifecyclePage.Result.notRecognised(pageForm);
        };
    }

    /** {@code GET /api/v2/lifecycle/catalog-sources}: the online source, the upload forms, the last uploads and the entries. */
    public Map<String, Object> sources(Instant now, boolean masked) {
        var entries = repository.entries();
        var rows = new ArrayList<Map<String, Object>>();
        var latest = repository.latestRuns();
        var good = repository.lastGoodRuns();
        for (String key : LifecycleSources.FEED_KEYS) {
            var row = new LinkedHashMap<String, Object>();
            row.put("source", key);
            row.put("origin", "FEED");
            var last = latest.stream().filter(r -> r.source().equals(key)).findFirst();
            var lastGood = good.stream().filter(r -> r.source().equals(key)).findFirst();
            row.put("last_run_outcome", last.map(LifecycleVendorRepository.FeedRun::outcome).orElse(null));
            row.put("last_run_reason", last.map(LifecycleVendorRepository.FeedRun::reason).orElse(null));
            row.put("last_run_at", last.map(r -> r.startedAt() == null ? null : r.startedAt().toString()).orElse(null));
            Instant goodAt = lastGood.map(LifecycleVendorRepository.FeedRun::endedAt).orElse(null);
            row.put("last_good_at", goodAt == null ? null : goodAt.toString());
            Long age = goodAt == null ? null : Math.max(0, java.time.Duration.between(goodAt, now).toDays());
            row.put("age_days", age);
            row.put("age_state", age == null || age > LifecycleMatcher.FEED_FRESH_DAYS ? "RED" : age > 7 ? "AMBER" : "GREEN");
            row.put("fresh_days", LifecycleMatcher.FEED_FRESH_DAYS);
            counts(row, entries.stream().filter(e -> e.source().equals(key) && "FEED".equals(e.origin())).toList());
            rows.add(row);
        }
        var forms = new ArrayList<Map<String, Object>>();
        for (String key : LifecycleSources.PAGE_FORM_ORDER) {
            var form = LifecycleSources.PAGE_FORMS.get(key);
            var row = new LinkedHashMap<String, Object>();
            row.put("page_form", form.key());
            row.put("vendor", form.vendor());
            row.put("label", form.label());
            row.put("page_url", form.canonicalUrl());
            row.put("online", LifecycleSources.FEED_KEYS.contains(form.source()));
            forms.add(row);
        }
        var uploads = repository.latestUploads(20).stream().map(u -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("upload_id", u.uploadId());
            row.put("page_form", u.pageForm());
            row.put("vendor", u.vendor());
            row.put("entries_parsed", u.entriesParsed());
            row.put("entries_needing_review", u.entriesNeedingReview());
            row.put("rows_skipped", u.rowsSkipped());
            row.put("uploaded_by", masked ? "[REDACTED]" : u.uploadedBy());
            row.put("uploaded_at", u.uploadedAt() == null ? null : u.uploadedAt().toString());
            Long age = u.uploadedAt() == null ? null : Math.max(0, java.time.Duration.between(u.uploadedAt(), now).toDays());
            row.put("age_days", age);
            row.put("age_state", age == null || age > LifecycleMatcher.HAND_LOADED_FRESH_DAYS ? "RED" : age > 90 ? "AMBER" : "GREEN");
            return (Map<String, Object>) row;
        }).toList();
        var review = entries.stream().filter(e -> !LifecyclePage.PARSED.equals(e.parseState())).map(e -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("entry_id", e.entryId());
            row.put("vendor", e.vendor());
            row.put("kind", e.kind());
            row.put("product", e.productLabel());
            row.put("key", e.productKey());
            row.put("source", e.source());
            row.put("origin", e.origin());
            row.put("parse_note", e.parseNote());
            row.put("source_url", e.sourceUrl());
            return (Map<String, Object>) row;
        }).limit(200).toList();
        var body = new LinkedHashMap<String, Object>();
        body.put("feeds", rows);
        body.put("page_forms", forms);
        body.put("uploads", uploads);
        body.put("needs_review", review);
        body.put("vendor_entries", entries.size());
        body.put("hand_loaded_fresh_days", LifecycleMatcher.HAND_LOADED_FRESH_DAYS);
        return body;
    }

    private static void counts(Map<String, Object> row, List<VendorEntry> entries) {
        row.put("entries", entries.size());
        row.put("entries_needing_review", entries.stream().filter(e -> !LifecyclePage.PARSED.equals(e.parseState())).count());
    }

    /** The entry in the form the V219 constraints accept, or empty. */
    static Optional<VendorEntry> toStored(LifecyclePage.Entry e, String origin, String source, String uploadId, String url, String sha) {
        if (e == null || e.productKey() == null || !PRODUCT_KEY.matcher(e.productKey()).matches()
                || e.documentRef() == null || !DOCUMENT_REF.matcher(e.documentRef()).matches()
                || url == null || !url.startsWith("https://") || url.length() > 2000) return Optional.empty();
        String note = e.parseNote() == null ? null : CODE_WORD.matcher(e.parseNote()).matches() ? e.parseNote() : "UNSPECIFIED_NOTE";
        String label = e.productLabel() == null || e.productLabel().isBlank() ? e.productKey() : e.productLabel();
        if (label.length() > 200) label = label.substring(0, 200);
        String id = origin + ":" + source + ":" + e.documentRef() + ":" + e.kind() + ":" + e.productKey();
        if (id.length() > 300) return Optional.empty();
        return Optional.of(new VendorEntry(id, origin, source, uploadId, e.vendor(), e.kind(), e.productKey(), label,
                e.date("end_of_sale"), e.date("end_of_engineering"), e.date("end_of_support"), e.date("end_of_security_support"),
                e.date("end_of_extended_support"), e.monthOnlyFields(), e.parseState(), note, e.documentRef(), url, sha, null));
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
