package com.securityexpert.nexus.ui2.service.advisorystore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository;
import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredAdvisory;
import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredKnownExploited;
import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredRule;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryCore;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParsedAdvisory;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ProductRule;

/**
 * Ingest of one fetched document and the closing of a fetch run (contract section 4). The document is parsed in memory by
 * the parser of its source; the parsed advisories replace the stored ones with the same id; the raw document is not
 * stored (its SHA-256 and address are). Everything fetched is untrusted (rule 7): a parsed value outside what the V203
 * constraints allow is cleaned or the advisory is rejected, so that one bad entry never fails the batch and never reaches
 * the database as free text. The log carries counts and code words only, never document text.
 */
@Service
public final class AdvisoryIngestService {

    private static final Logger LOG = LoggerFactory.getLogger(AdvisoryIngestService.class);

    /** Contract section 4: the document, at most 16 MiB. */
    public static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    /** The close body is a few numbers and a code word. */
    public static final int MAX_COMPLETE_BYTES = 4096;

    private static final Pattern CODE_WORD = Pattern.compile("^[A-Z0-9_]{1,40}$");
    private static final Pattern CVE = Pattern.compile("^CVE-[0-9]{4}-[0-9]{4,10}$");
    private static final Pattern PRODUCT_KEY = Pattern.compile("^[a-z0-9_]{1,30}:[a-z0-9_-]{1,40}$");
    private static final Pattern TRAIN = Pattern.compile("^[A-Za-z0-9._*-]{1,40}$");
    private static final int MAX_ADVISORIES_PER_DOCUMENT = 20_000;
    private static final int MAX_RULES_PER_ADVISORY = 200;
    private static final int MAX_CVES_PER_ADVISORY = 200;
    private static final int MAX_RULE_JSON = 200_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The answer of an ingest or close call: an HTTP status and a fixed code word (null for success). */
    public enum Outcome {
        STORED(204, null),
        CORE_MISSING(503, "ADVISORY_CORE_MISSING"),
        PARSER_MISSING(503, "ADVISORY_PARSER_MISSING"),
        RUN_CLOSED(409, "ADVISORY_RUN_CLOSED"),
        COMPLETE_INVALID(400, "ADVISORY_COMPLETE_INVALID"),
        STORE_FAILED(500, "ADVISORY_STORE_FAILED");

        private final int status;
        private final String code;

        Outcome(int status, String code) {
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }

        public String code() { return code; }
    }

    private final AdvisoryRepository repository;
    private final Supplier<Optional<AdvisoryCore>> core;

    @Autowired
    public AdvisoryIngestService(AdvisoryRepository repository, ObjectProvider<AdvisoryCore> core) {
        this(repository, () -> Optional.ofNullable(core.getIfAvailable()));
    }

    public AdvisoryIngestService(AdvisoryRepository repository, Supplier<Optional<AdvisoryCore>> core) {
        this.repository = repository;
        this.core = core;
    }

    /** True when the core (parsers, rule codec) is present; the controller answers 503 before reading a body otherwise. */
    public boolean coreAvailable() {
        return core.get().isPresent();
    }

    /**
     * @param source an allowed source key; {@code url} already checked against the source's host and path
     * @param runId already checked against {@code ^[A-Za-z0-9_-]{8,64}$}
     */
    public Outcome ingest(String source, String runId, String url, byte[] document) {
        Optional<AdvisoryCore> available = core.get();
        if (available.isEmpty()) return Outcome.CORE_MISSING;
        AdvisoryCore advisoryCore = available.get();
        var parser = advisoryCore.parser(source);
        if (parser.isEmpty()) return Outcome.PARSER_MISSING;
        try {
            repository.openRun(runId, source);
            var existing = repository.run(runId, source);
            if (existing.isPresent() && existing.get().endedAt() != null) return Outcome.RUN_CLOSED;

            AdvisoryModel.ParseResult parsed;
            try {
                parsed = parser.get().parse(url, document);
            } catch (RuntimeException e) {
                // A parser must not throw (AdvisoryParser); if one does, the document counts as rejected and changes nothing.
                LOG.warn("advisory ingest source={} run={} code=PARSER_THREW", source, runId);
                repository.addToRun(runId, source, 1, 1, 0, 0);
                return Outcome.STORED;
            }
            String sha = sha256(document);
            int rejected = Math.max(0, parsed.rejected());
            List<StoredAdvisory> stored = new ArrayList<>();
            List<ParsedAdvisory> candidates = parsed.advisories();
            if (candidates.size() > MAX_ADVISORIES_PER_DOCUMENT) {
                rejected += candidates.size() - MAX_ADVISORIES_PER_DOCUMENT;
                candidates = candidates.subList(0, MAX_ADVISORIES_PER_DOCUMENT);
            }
            for (ParsedAdvisory advisory : candidates) {
                Optional<StoredAdvisory> row = toStored(advisoryCore, source, url, sha, advisory);
                if (row.isPresent()) stored.add(row.get()); else rejected++;
            }
            repository.upsertAdvisories(stored);
            // The known-exploited set is replaced as a whole, but only by a document that yielded entries: an empty or
            // unreadable catalog must not erase the flag (contract rule 4 spirit, section 4 "changes nothing").
            if (AdvisoryModel.SOURCE_CISA_KEV.equals(source) && !parsed.knownExploited().isEmpty()) {
                repository.replaceKnownExploited(parsed.knownExploited().stream()
                        .filter(k -> k.cveId() != null && CVE.matcher(k.cveId()).matches() && k.dateAdded() != null)
                        .map(k -> new StoredKnownExploited(k.cveId(), k.dateAdded(), k.ransomwareUse())).toList());
            }
            int needsReview = (int) stored.stream().filter(a -> "NEEDS_REVIEW".equals(a.parseState())).count();
            repository.addToRun(runId, source, 1, rejected, stored.size(), needsReview);
            LOG.info("advisory ingest source={} run={} stored={} needs_review={} rejected={}", source, runId, stored.size(),
                    needsReview, rejected);
            return Outcome.STORED;
        } catch (RuntimeException e) {
            LOG.error("advisory ingest source={} run={} code=STORE_FAILED type={}", source, runId, e.getClass().getSimpleName());
            return Outcome.STORE_FAILED;
        }
    }

    /**
     * Closes the run (contract section 4). Outcome: FAILED when nothing was received, PARTIAL when some document failed or
     * some entry was rejected, otherwise OK. Closing a run that is already closed changes nothing and answers success, so
     * a fetcher may repeat the call after a lost answer.
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
            int received = existing.map(AdvisoryRepository.FeedRun::documentsReceived).orElse(0);
            int rejected = existing.map(AdvisoryRepository.FeedRun::rejected).orElse(0);
            String outcome = received == 0 ? "FAILED" : failed > 0 || rejected > 0 ? "PARTIAL" : "OK";
            if (reason == null && !"OK".equals(outcome)) {
                reason = received == 0 ? "NOTHING_RECEIVED" : failed > 0 ? "SOME_DOCUMENTS_FAILED" : "SOME_ENTRIES_REJECTED";
            }
            repository.closeRun(runId, source, outcome, fetched, failed, reason);
            LOG.info("advisory run closed source={} run={} outcome={} received={} failed={} rejected={}", source, runId, outcome,
                    received, failed, rejected);
            return Outcome.STORED;
        } catch (RuntimeException e) {
            LOG.error("advisory run close source={} run={} code=STORE_FAILED type={}", source, runId, e.getClass().getSimpleName());
            return Outcome.STORE_FAILED;
        }
    }

    /** The parsed advisory in the form the V203 constraints accept, or empty when it cannot be stored as it is. */
    static Optional<StoredAdvisory> toStored(AdvisoryCore core, String source, String documentUrl, String sha, ParsedAdvisory a) {
        if (a == null || a.advisoryId() == null || a.advisoryId().length() < 3 || a.advisoryId().length() > 240
                || !a.advisoryId().startsWith(source + ":") || !source.equals(a.source())
                || a.severity() == null || a.parseState() == null) return Optional.empty();
        String title = a.title() == null ? "" : a.title().strip();
        if (title.isEmpty()) title = a.advisoryId();
        if (title.length() > 300) title = title.substring(0, 300);
        String link = a.sourceUrl() != null && a.sourceUrl().startsWith("https://") && a.sourceUrl().length() <= 2000
                ? a.sourceUrl() : documentUrl;
        String note = a.parseNote() == null ? null : CODE_WORD.matcher(a.parseNote()).matches() ? a.parseNote() : "UNSPECIFIED_NOTE";
        BigDecimal score = a.cvssScore() == null ? null : a.cvssScore().setScale(1, RoundingMode.HALF_UP);
        if (score != null && (score.signum() < 0 || score.compareTo(BigDecimal.TEN) > 0)) score = null;
        List<String> cves = a.cveIds().stream().filter(c -> c != null && CVE.matcher(c).matches()).distinct()
                .limit(MAX_CVES_PER_ADVISORY).toList();
        if (a.rules().size() > MAX_RULES_PER_ADVISORY) return Optional.empty();
        List<StoredRule> rules = new ArrayList<>();
        for (ProductRule rule : a.rules()) {
            if (rule.productKey() == null || !PRODUCT_KEY.matcher(rule.productKey()).matches()
                    || rule.train() == null || !TRAIN.matcher(rule.train()).matches()) return Optional.empty();
            String json = core.rulesToJson(rule.rules());
            if (json == null || json.length() < 2 || json.length() > MAX_RULE_JSON) return Optional.empty();
            String fixed = rule.fixedText() == null ? null : rule.fixedText().strip();
            if (fixed != null && fixed.length() > 200) fixed = fixed.substring(0, 200);
            if (fixed != null && fixed.isEmpty()) fixed = null;
            rules.add(new StoredRule(rule.productKey(), rule.train(), json, fixed));
        }
        return Optional.of(new StoredAdvisory(a.advisoryId(), source, title, a.severity().name(), score, cves, a.publishedOn(),
                a.updatedOn(), link, a.parseState().name(), note, sha, null, rules));
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
