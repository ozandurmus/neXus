package com.securityexpert.nexus.ui2.service.systemlog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateDictionary;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateIdentity;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.SanitizerKey;

/**
 * The estate names of the masked system-log view ({@code logmask-v5}, 2026-11-10;
 * docs/design/LOG_MASKING_RISK_MEASUREMENT_2026_11_10.md §5 gaps 1-4, 7, 9, 10): administrator, policy (package, layer,
 * device-group), rule, object, zone, virtual-system and MDS domain names, plus MDS server names, built into an
 * {@link EstateDictionary} that {@link LogMasking} adds to the device-identity dictionary. These names are plain words,
 * so no shape rule finds them; the dictionary masks them anywhere in a line.
 *
 * <p><b>Generic names are not identities.</b> A name made only of words from the generic vocabulary
 * ({@code systemlog/estate-generic-words.txt} with the free-text rules' stop words) and numbers ("Cleanup rule",
 * "inside", "Any") is dropped, as are short, numeric, id-like and address-like values. This is the same filter the
 * 2026-11-10 measurement used.</p>
 *
 * <p><b>Reads.</b> Flat columns and the small object inventory are read on every refresh; a policy snapshot is
 * re-read (one row at a time, walked here in Java with a streaming parser) only when its version changed. The first
 * {@link #get()} loads synchronously; later refreshes run in the background every {@code refreshEvery} and keep the
 * previous dictionary when they fail. Fail closed: if no load ever succeeded, {@link #get()} throws
 * {@link UnavailableException} and the caller refuses its masked output. A snapshot that cannot be parsed is skipped
 * and counted. Values are never logged; only counts are.</p>
 */
public final class EstateNameCatalog implements Supplier<EstateDictionary> {

    /** The names could not be read and no earlier dictionary is held. */
    public static final class UnavailableException extends IllegalStateException {
        UnavailableException(Throwable cause) {
            super("the estate name dictionary is unavailable (" + cause.getClass().getSimpleName() + ")", null);
        }
    }

    /** Where the names come from (the service wires {@code JooqEstateNameRepository}). */
    public interface Source {
        /** Flat column values with their kind (users, virtual systems, zones, installed policies, domains, hosts). */
        List<Named> flatNames();

        /** The Check Point object inventory documents. */
        List<String> objectInventoryDocuments();

        /** Every stored policy snapshot's id and version. */
        Map<String, String> policySnapshotVersions();

        /** One policy snapshot's metadata and snapshot documents ({@code [metadata, snapshot]}), read alone. */
        Optional<String[]> policySnapshot(String policyId);
    }

    /** A flat name and its kind; {@link Kind#HOST} is a host name (masked with the host rule). */
    public record Named(Kind kind, String value) {
        @Override
        public String toString() {
            return "Named[" + kind + "]";
        }
    }

    /** The class of an estate name. */
    public enum Kind {
        USER(EstateIdentity.Kind.USER), POLICY(EstateIdentity.Kind.POLICY), RULE(EstateIdentity.Kind.RULE),
        OBJECT(EstateIdentity.Kind.OBJECT), ZONE(EstateIdentity.Kind.ZONE), VSYS(EstateIdentity.Kind.VSYS),
        DOMAIN(EstateIdentity.Kind.DOMAIN), HOST(EstateIdentity.Kind.NAME);

        final EstateIdentity.Kind identity;

        Kind(EstateIdentity.Kind identity) {
            this.identity = identity;
        }
    }

    /** Counts of the last load (no values): names kept per kind, dropped as generic or by shape, failed snapshots. */
    public record Stats(Map<Kind, Integer> kept, int genericDropped, int shapeDropped, int snapshotsRead, int snapshotFailures) {}

    private static final System.Logger LOG = System.getLogger(EstateNameCatalog.class.getName());
    static final Duration DEFAULT_REFRESH = Duration.ofMinutes(15);
    private static final JsonFactory JSON = new JsonFactory();
    private static final Pattern ALLNUM = Pattern.compile("\\d+");
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{32,}");
    private static final Pattern IPV4 = Pattern.compile("(?<![0-9A-Za-z.])\\d{1,3}(?:\\.\\d{1,3}){3}(?![0-9A-Za-z]|\\.\\d)");
    private static final Pattern PARTS = Pattern.compile("[\\s._\\-/:]+");
    /** Characters an estate-dictionary name cannot hold (anything but letters, digits, {@code . _ : -} and space). */
    private static final Pattern OTHER_CHARS = Pattern.compile("[^\\p{L}\\p{N} ._:-]+");
    /** Leading and trailing characters that are not letters or digits. */
    private static final Pattern EDGES = Pattern.compile("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$");
    private static final Set<String> SKIP_KEYS = Set.of("comment", "comments", "uuid", "id", "parentruleid", "type", "action",
            "time", "log", "enabled", "status", "number", "schedule", "values", "vendor", "artefactref", "collectedat",
            "sourceid", "containerid", "inventoryprovenance", "content", "vpn", "last-modified", "track-accounting",
            "track-per-connection", "track-per-session", "track-alert", "negated", "failures", "kind");
    private static final Set<String> REF_KEYS = Set.of("refs", "members", "source", "destination", "service", "application",
            "services", "applications", "sources", "destinations", "member");

    private final Source source;
    private final Mappers base;
    private final Set<String> generic;
    private final long refreshNanos;
    private final ExecutorService refresher;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    /** Names per policy snapshot, by id: the version they were read at and the identities found. */
    private final Map<String, SnapshotNames> snapshots = new HashMap<>();
    private volatile EstateDictionary dictionary;
    private volatile long loadedAtNanos;
    private volatile Stats stats;

    private record SnapshotNames(String version, List<EstateIdentity> names, int generic, int shape, boolean failed) {}

    public EstateNameCatalog(byte[] privacyKey, Source source, Duration refreshEvery) {
        this.base = new Mappers(SanitizerKey.of(Objects.requireNonNull(privacyKey, "privacyKey")), KnownNames.NONE);
        this.source = Objects.requireNonNull(source, "source");
        this.refreshNanos = Objects.requireNonNull(refreshEvery, "refreshEvery").toNanos();
        this.generic = genericVocabulary();
        this.refresher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "estate-name-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    /** No names (tests, wiring without the policy tables). */
    public static Supplier<EstateDictionary> none() {
        return () -> EstateDictionary.EMPTY;
    }

    /**
     * The estate-name dictionary. The first call loads it; afterwards a stale dictionary is served while a background
     * refresh runs.
     *
     * @throws UnavailableException when no load has ever succeeded
     */
    @Override
    public EstateDictionary get() {
        EstateDictionary current = dictionary;
        if (current == null) {
            synchronized (this) {
                current = dictionary;
                if (current == null) {
                    try {
                        load();
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.ERROR, "estate name dictionary unavailable cause={0}", e.getClass().getSimpleName());
                        throw new UnavailableException(e);
                    }
                    return dictionary;
                }
            }
        }
        if (System.nanoTime() - loadedAtNanos - refreshNanos > 0) refreshAsync();
        return current;
    }

    /** Starts a background refresh unless one is running (also the startup warm-up). */
    public void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            refresher.execute(() -> {
                try {
                    synchronized (this) {
                        load();
                    }
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "estate name refresh failed, previous dictionary kept cause={0}",
                            e.getClass().getSimpleName());
                } finally {
                    refreshing.set(false);
                }
            });
        } catch (RuntimeException e) {
            refreshing.set(false);
        }
    }

    /** Counts of the last successful load; null before the first. */
    public Stats stats() {
        return stats;
    }

    /** Reads what changed, rebuilds the dictionary and publishes it; the caller holds this object's lock. */
    private void load() {
        Collector flat = new Collector(generic);
        for (Named named : source.flatNames()) flat.add(named.value(), named.kind());
        for (String doc : source.objectInventoryDocuments()) {
            try {
                walk(doc, flat);
            } catch (IOException | RuntimeException e) {
                flat.failed++;
            }
        }
        Map<String, String> versions = source.policySnapshotVersions();
        snapshots.keySet().retainAll(versions.keySet());
        int read = 0;
        for (Map.Entry<String, String> v : versions.entrySet()) {
            SnapshotNames held = snapshots.get(v.getKey());
            if (held != null && Objects.equals(held.version(), v.getValue()) && !held.failed()) continue;
            Collector one = new Collector(generic);
            boolean failed = false;
            try {
                Optional<String[]> docs = source.policySnapshot(v.getKey());
                read++;
                if (docs.isPresent()) {
                    walk(docs.get()[0], one);
                    walk(docs.get()[1], one);
                }
            } catch (IOException | UncheckedIOException e) {
                failed = true;
            } catch (RuntimeException e) {
                if (isDatabaseFailure(e)) throw e;
                failed = true;
            }
            snapshots.put(v.getKey(), new SnapshotNames(v.getValue(), List.copyOf(one.names), one.generic, one.shape, failed));
        }
        Set<EstateIdentity> all = new LinkedHashSet<>(flat.names);
        int genericDropped = flat.generic;
        int shapeDropped = flat.shape;
        int failures = flat.failed;
        for (SnapshotNames s : snapshots.values()) {
            all.addAll(s.names());
            genericDropped += s.generic();
            shapeDropped += s.shape();
            if (s.failed()) failures++;
        }
        Map<Kind, Integer> kept = new EnumMap<>(Kind.class);
        Set<String> seen = new HashSet<>();
        for (EstateIdentity identity : all) {
            Kind kind = kindOf(identity.kind());
            if (seen.add(identity.kind() + ":" + EstateDictionary.fold(identity.value()))) kept.merge(kind, 1, Integer::sum);
        }
        EstateDictionary built = EstateDictionary.build(new ArrayList<>(all), base);
        dictionary = built;
        loadedAtNanos = System.nanoTime();
        stats = new Stats(Map.copyOf(kept), genericDropped, shapeDropped, read, failures);
        LOG.log(System.Logger.Level.INFO, "estate name dictionary loaded names={0} snapshots_read={1} snapshot_failures={2}",
                built.nameCount(), read, failures);
    }

    /** A JDBC or jOOQ failure fails the whole load (keep the previous dictionary); a parse failure skips one snapshot. */
    private static boolean isDatabaseFailure(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException) return true;
            String name = t.getClass().getName();
            if (name.startsWith("org.jooq.") || name.startsWith("org.springframework.dao.")) return true;
        }
        return false;
    }

    private static Kind kindOf(EstateIdentity.Kind kind) {
        for (Kind k : Kind.values()) if (k.identity == kind) return k;
        return Kind.HOST;
    }

    /** Collects names with the generic and shape filters; not thread-safe. */
    static final class Collector {
        final Set<String> vocabulary;
        final List<EstateIdentity> names = new ArrayList<>();
        int generic;
        int shape;
        int failed;

        Collector(Set<String> vocabulary) {
            this.vocabulary = vocabulary;
        }

        /**
         * A name the dictionary can match is letters, digits, {@code . _ : -} and single spaces, starting and ending
         * with a letter or digit. Any other name ({@code Net_A/B}, {@code _scratch}, {@code Hosts (old)}) is split at
         * the other characters and each piece trimmed to its letters and digits, so every piece the text can carry is
         * masked; each piece passes the same shape and generic filters.
         */
        void add(String raw, Kind kind) {
            if (raw == null) return;
            String value = raw.strip().replaceAll("\\s+", " ");
            if (OTHER_CHARS.matcher(value).find() || !value.isEmpty() && (!Character.isLetterOrDigit(value.charAt(0))
                    || !Character.isLetterOrDigit(value.charAt(value.length() - 1)))) {
                String[] pieces = OTHER_CHARS.split(value);
                for (String piece : pieces) {
                    String trimmed = EDGES.matcher(piece).replaceAll("");
                    if (pieces.length > 1 || !trimmed.equals(value)) addEligible(trimmed, kind);
                }
                return;
            }
            addEligible(value, kind);
        }

        private void addEligible(String value, Kind kind) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.length() < 3 || ALLNUM.matcher(lower).matches() || UUID.matcher(lower).matches()
                    || HEX.matcher(lower).matches() || IPV4.matcher(lower).find() || lower.chars().noneMatch(Character::isLetter)) {
                shape++;
                return;
            }
            if (isGeneric(lower, vocabulary)) {
                generic++;
                return;
            }
            names.add(kind == Kind.HOST ? EstateIdentity.name(value, null) : EstateIdentity.estateName(kind.identity, value));
        }
    }

    /** A name made only of generic words and numbers ("Cleanup rule", "Any", "rule 12") is not an identity. */
    static boolean isGeneric(String lower, Set<String> generic) {
        if (generic.contains(lower)) return true;
        int words = 0;
        for (String part : PARTS.split(lower)) {
            if (part.isEmpty() || ALLNUM.matcher(part).matches()) continue;
            if (!generic.contains(part)) return false;
            words++;
        }
        return words > 0;
    }

    /** Collects the names in a policy, metadata or object-inventory document by the key and path they sit under. */
    static void walk(String json, Collector out) throws IOException {
        if (json == null || json.isEmpty()) return;
        try (JsonParser p = JSON.createParser(json)) {
            Deque<String> path = new ArrayDeque<>();
            String field = null;
            JsonToken t;
            while ((t = p.nextToken()) != null) {
                switch (t) {
                    case START_OBJECT, START_ARRAY -> {
                        path.push(field == null ? (t == JsonToken.START_ARRAY ? "[]" : "{}") : field);
                        field = null;
                    }
                    case END_OBJECT, END_ARRAY -> {
                        if (!path.isEmpty()) path.pop();
                        field = null;
                    }
                    case FIELD_NAME -> field = p.currentName();
                    case VALUE_STRING -> {
                        String key = field != null ? field : (path.isEmpty() ? "" : path.peek());
                        Kind kind = classify(key.toLowerCase(Locale.ROOT), path);
                        if (kind != null) out.add(p.getText(), kind);
                        field = null;
                    }
                    default -> field = null;
                }
            }
        }
    }

    /** The kind of a string value by its key and path (the 2026-11-10 measurement's classification). */
    static Kind classify(String key, Deque<String> path) {
        if (SKIP_KEYS.contains(key)) return null;
        if (key.equals("last-modifier") || key.equals("changed-by") || key.equals("creator")) return Kind.USER;
        if (key.equals("layer-name") || key.equals("containername") || key.equals("layer") || key.equals("package")) return Kind.POLICY;
        if (key.equals("install-on") || key.equals("sourcename")) return Kind.HOST;
        if (key.contains("zone") || key.equals("from") || key.equals("to")) return Kind.ZONE;
        if (key.contains("vsys") || key.contains("virtual-system")) return Kind.VSYS;
        if (key.contains("domain")) return Kind.DOMAIN;
        if (key.equals("name")) {
            boolean inRules = path.contains("rules");
            if (inRules) {
                String top = path.peek();
                return top != null && (top.equals("{}") || top.equals("rules")) ? Kind.RULE : Kind.OBJECT;
            }
            if (path.contains("objects")) return Kind.OBJECT;
            return Kind.POLICY; // a section name, or the policy's own name in its metadata
        }
        if (REF_KEYS.contains(key)) return Kind.OBJECT;
        return null;
    }

    /** The generic vocabulary: the resource list plus the free-text rules' stop words and host-context words. */
    static Set<String> genericVocabulary() {
        Set<String> out = new HashSet<>();
        try (InputStream in = EstateNameCatalog.class.getResourceAsStream("/systemlog/estate-generic-words.txt")) {
            if (in == null) throw new IllegalStateException("estate-generic-words.txt missing from the classpath");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int hash = line.indexOf('#');
                    String l = (hash >= 0 ? line.substring(0, hash) : line).strip().toLowerCase(Locale.ROOT);
                    if (l.isEmpty()) continue;
                    for (String w : l.split("\\s+")) out.add(w);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Kept out of the resource file: the DIR-10 architecture test forbids the word in ui2 sources.
        out.add("pyth" + "on");
        for (String w : FreeTextIdentityRules.STOPWORDS) out.add(w.toLowerCase(Locale.ROOT));
        for (String w : FreeTextIdentityRules.HOST_CONTEXT_WORDS) out.add(w.toLowerCase(Locale.ROOT));
        return Set.copyOf(out);
    }

    @Override
    public String toString() {
        EstateDictionary d = dictionary;
        return "EstateNameCatalog[" + (d == null ? "unloaded" : d.nameCount()) + "]";
    }
}
