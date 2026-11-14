package com.securityexpert.nexus.ui2.service.zonematrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * The content of one zone-matrix version (ZONE_MATRIX_CONTRACT.md sections 3.1, 3.5 and 4, FROZEN 2026-11-13): the zones
 * and their blocks (delivery step 1) and the matrix (step 2): the version default, the explicit cells, the named service
 * sets and the template origin.
 *
 * <p>The canonical JSON is written by hand so its bytes do not depend on a serializer's configuration: keys in a fixed
 * order, zones sorted by id, blocks canonical and sorted, service sets sorted by name, cells sorted by pair, no
 * whitespace. The version hash is the SHA-256 of these bytes. Schema 1 (step 1, zones only) is still read; it is written
 * back as schema 2 with the initial matrix.</p>
 */
public record ZoneMatrixContent(List<Zone> zones, Matrix matrix) {
    public static final int SCHEMA = 2;
    /** Bounds (section 3.1). The total-block cap is provisional until the section 9 measurement sets it. */
    public static final int MAX_ZONES = 500, MAX_BLOCKS_PER_ZONE = 10_000, MAX_BLOCKS_TOTAL = 200_000,
            MAX_NAME = 64, MAX_DESCRIPTION = 500;
    /**
     * Matrix bounds (section 3.1 "request size limit on every edit route ... refused, never truncated"). The cell cap
     * keeps a version's canonical content well inside the 20 MB column bound; it is provisional like the block cap.
     */
    public static final int MAX_CELLS = 50_000, MAX_SERVICES_PER_CELL = 1_000, MAX_SERVICE_SETS = 200,
            MAX_ENTRIES_PER_SET = 1_000, MAX_TEXT = 2_000;
    /** The built-in pseudo-zone's key in a cell (section 3.3); zone ids are UUIDs, so it never collides. */
    public static final String UNASSIGNED = "UNASSIGNED";
    /** The template roles of section 6.1; a zone may carry none. */
    public static final Set<String> ROLES = Set.of("INTERNET_UNTRUSTED", "DMZ", "USER", "APPLICATION", "DATABASE",
            "MANAGEMENT", "PARTNER", "NON_PRODUCTION");
    /** Statements, strictest first (section 4.1). */
    public static final List<String> STATEMENTS = List.of("DENY_ALL", "ALLOW_LISTED", "ALLOW_ANY");
    /** Severities, lowest first (section 4.1). */
    public static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    /** Reference status of a cell (section 6.1). */
    public static final Set<String> REFERENCE_STATUSES = Set.of("EMPTY", "PROPOSED", "CONFIRMED");

    public record Zone(String id, String name, String description, String role, List<ZoneBlock> blocks) {
        public Zone {
            blocks = blocks.stream().sorted(ZoneBlock.ORDER).toList();
            description = description == null ? "" : description;
        }
    }

    /** Where the version's cells were seeded from (section 2: "the template origin"); null when none was imported. */
    public record TemplateOrigin(String name, String revision) {}

    /** A matrix-local name for a list of service entries (section 4.2); a set never contains a set. */
    public record ServiceSet(String name, List<String> entries) {
        public ServiceSet { entries = entries.stream().distinct().sorted().toList(); }
    }

    /**
     * One explicit cell: the statement for traffic from {@code source} to {@code destination} (zone ids or
     * {@link #UNASSIGNED}), its services (only for {@code ALLOW_LISTED}), severity, an optional rationale, and the
     * reference fields of section 6.1, which neXus never fills.
     */
    public record Cell(String source, String destination, String statement, List<String> services, String severity,
            String rationale, String regulationReference, String referenceStatus, String confirmedBy, String confirmedAt) {
        public Cell {
            services = services == null ? List.of() : services.stream().distinct().sorted().toList();
            regulationReference = regulationReference == null ? "" : regulationReference;
        }
        public String pair() { return source + "->" + destination; }
    }

    /** The matrix of one version: the version default (never ALLOW_ANY, section 3.5) and the explicit cells. */
    public record Matrix(String defaultStatement, String defaultSeverity, TemplateOrigin templateOrigin,
            List<ServiceSet> serviceSets, List<Cell> cells) {
        public Matrix {
            serviceSets = serviceSets.stream().sorted(Comparator.comparing(ServiceSet::name)).toList();
            cells = cells.stream().sorted(Comparator.comparing(Cell::source).thenComparing(Cell::destination)).toList();
        }
        /** A new version's matrix: default DENY_ALL, severity MEDIUM, no cells (section 6.1 "any other pair"). */
        public static Matrix initial() { return new Matrix("DENY_ALL", "MEDIUM", null, List.of(), List.of()); }

        public Optional<Cell> cell(String source, String destination) {
            return cells.stream().filter(c -> c.source().equals(source) && c.destination().equals(destination)).findFirst();
        }

        public Optional<ServiceSet> set(String name) { return serviceSets.stream().filter(s -> s.name().equals(name)).findFirst(); }

        public Matrix withCells(List<Cell> next) { return new Matrix(defaultStatement, defaultSeverity, templateOrigin, serviceSets, next); }
    }

    public ZoneMatrixContent {
        zones = zones.stream().sorted(Comparator.comparing(Zone::id)).toList();
        matrix = matrix == null ? Matrix.initial() : matrix;
    }

    public ZoneMatrixContent(List<Zone> zones) { this(zones, Matrix.initial()); }

    public static ZoneMatrixContent empty() { return new ZoneMatrixContent(List.of()); }

    /** The same matrix over new zones; cells of a zone that no longer exists go with it. */
    public ZoneMatrixContent withZones(List<Zone> next) {
        Set<String> ids = new HashSet<>();
        next.forEach(z -> ids.add(z.id()));
        ids.add(UNASSIGNED);
        return new ZoneMatrixContent(next, matrix.withCells(matrix.cells().stream()
                .filter(c -> ids.contains(c.source()) && ids.contains(c.destination())).toList()));
    }

    public ZoneMatrixContent withMatrix(Matrix next) { return new ZoneMatrixContent(zones, next); }

    /** Every cell endpoint of this version: the zone ids and UNASSIGNED. */
    public List<String> endpoints() {
        List<String> out = new ArrayList<>(zones.stream().map(Zone::id).toList());
        out.add(UNASSIGNED);
        return out;
    }

    public Optional<Zone> zone(String id) { return zones.stream().filter(z -> z.id().equals(id)).findFirst(); }

    public int blockCount() { return zones.stream().mapToInt(z -> z.blocks().size()).sum(); }

    public ZonePartition partition() {
        List<ZonePartition.Placed> placed = new ArrayList<>();
        for (Zone zone : zones) for (ZoneBlock block : zone.blocks()) placed.add(new ZonePartition.Placed(zone.id(), block));
        return ZonePartition.of(placed);
    }

    /**
     * Validates the whole content: bounds, names (unique ignoring case so two zones never read alike), roles, and the
     * partition (a block in two zones fails with {@link ZonePartition.DuplicateBlock}). Returns the partition.
     */
    public ZonePartition validate() {
        if (zones.size() > MAX_ZONES) throw new Invalid("TOO_MANY_ZONES", "at most " + MAX_ZONES + " zones");
        if (blockCount() > MAX_BLOCKS_TOTAL) throw new Invalid("TOO_MANY_BLOCKS", "at most " + MAX_BLOCKS_TOTAL + " blocks in a version");
        Set<String> names = new HashSet<>(), ids = new HashSet<>();
        for (Zone zone : zones) {
            if (zone.id() == null || !zone.id().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || !ids.add(zone.id()))
                throw new Invalid("INVALID_ZONE_ID", "zone id is not a unique UUID");
            validateName(zone.name());
            if (!names.add(zone.name().toLowerCase(Locale.ROOT))) throw new Invalid("ZONE_NAME_DUPLICATE", "another zone has this name");
            if (zone.description().length() > MAX_DESCRIPTION || zone.description().chars().anyMatch(c -> Character.isISOControl(c) && c != '\n'))
                throw new Invalid("INVALID_DESCRIPTION", "description is too long or holds control characters");
            if (zone.role() != null && !ROLES.contains(zone.role())) throw new Invalid("INVALID_ROLE", "unknown zone role");
            if (zone.blocks().size() > MAX_BLOCKS_PER_ZONE) throw new Invalid("TOO_MANY_BLOCKS", "at most " + MAX_BLOCKS_PER_ZONE + " blocks in a zone");
        }
        validateMatrix(ids);
        return partition();
    }

    /** The matrix rules of sections 3.5, 4 and 6.1; {@code zoneIds} are this version's zones. */
    private void validateMatrix(Set<String> zoneIds) {
        if (!"DENY_ALL".equals(matrix.defaultStatement()) && !"ALLOW_LISTED".equals(matrix.defaultStatement()))
            throw new Invalid("INVALID_DEFAULT", "the version default is DENY_ALL or ALLOW_LISTED; ALLOW_ANY exists only as an explicit cell");
        if (!in(SEVERITIES, matrix.defaultSeverity())) throw new Invalid("INVALID_SEVERITY", "unknown severity");
        if (matrix.serviceSets().size() > MAX_SERVICE_SETS) throw new Invalid("TOO_MANY_SERVICE_SETS", "at most " + MAX_SERVICE_SETS + " service sets");
        Set<String> setNames = new HashSet<>();
        for (ServiceSet set : matrix.serviceSets()) {
            try { ServiceEntry.validateSetName(set.name()); }
            catch (IllegalArgumentException e) { throw new Invalid("INVALID_SERVICE_SET", e.getMessage()); }
            if (!setNames.add(set.name().toLowerCase(Locale.ROOT))) throw new Invalid("SERVICE_SET_DUPLICATE", "another service set has this name");
            if (set.entries().isEmpty() || set.entries().size() > MAX_ENTRIES_PER_SET)
                throw new Invalid("INVALID_SERVICE_SET", "a service set holds 1-" + MAX_ENTRIES_PER_SET + " entries");
            for (String entry : set.entries()) {
                ServiceEntry parsed;
                try { parsed = ServiceEntry.parse(entry); }
                catch (IllegalArgumentException e) { throw new Invalid("INVALID_SERVICES", e.getMessage()); }
                if (parsed.kind() == ServiceEntry.Kind.SET) throw new Invalid("INVALID_SERVICE_SET", "a service set cannot contain a set");
            }
        }
        if (matrix.cells().size() > MAX_CELLS) throw new Invalid("TOO_MANY_CELLS", "at most " + MAX_CELLS + " explicit cells in a version");
        Set<String> pairs = new HashSet<>();
        for (Cell cell : matrix.cells()) {
            for (String end : List.of(cell.source(), cell.destination()))
                if (!end.equals(UNASSIGNED) && !zoneIds.contains(end)) throw new Invalid("CELL_ZONE_NOT_FOUND", "a cell names a zone that is not in this version");
            if (!pairs.add(cell.pair())) throw new Invalid("CELL_DUPLICATE", "two cells for one zone pair");
            if (!in(STATEMENTS, cell.statement())) throw new Invalid("INVALID_STATEMENT", "unknown statement");
            if (!in(SEVERITIES, cell.severity())) throw new Invalid("INVALID_SEVERITY", "unknown severity");
            if (!cell.statement().equals("ALLOW_LISTED") && !cell.services().isEmpty())
                throw new Invalid("SERVICES_NOT_ALLOWED", "only an ALLOW_LISTED cell lists services");
            if (cell.services().size() > MAX_SERVICES_PER_CELL)
                throw new Invalid("TOO_MANY_SERVICES", "at most " + MAX_SERVICES_PER_CELL + " services in a cell");
            for (String entry : cell.services()) {
                ServiceEntry parsed;
                try { parsed = ServiceEntry.parse(entry); }
                catch (IllegalArgumentException e) { throw new Invalid("INVALID_SERVICES", e.getMessage()); }
                if (parsed.kind() == ServiceEntry.Kind.SET && matrix.set(parsed.token()).isEmpty())
                    throw new Invalid("SERVICE_SET_NOT_FOUND", "a cell names a service set that does not exist");
            }
            validateText(cell.rationale(), "INVALID_RATIONALE");
            validateText(cell.regulationReference(), "INVALID_REFERENCE");
            String status = cell.referenceStatus();
            boolean empty = cell.regulationReference().isBlank();
            boolean confirmed = "CONFIRMED".equals(status);
            if (!in(REFERENCE_STATUSES, status) || empty != "EMPTY".equals(status)
                    || confirmed != (cell.confirmedBy() != null && cell.confirmedAt() != null)
                    || !confirmed && (cell.confirmedBy() != null || cell.confirmedAt() != null))
                throw new Invalid("INVALID_REFERENCE_STATUS", "reference status does not match the reference fields");
        }
        if (matrix.templateOrigin() != null) {
            validateText(matrix.templateOrigin().name(), "INVALID_TEMPLATE_ORIGIN");
            validateText(matrix.templateOrigin().revision(), "INVALID_TEMPLATE_ORIGIN");
        }
    }

    /** Membership that refuses null; immutable collections throw on {@code contains(null)}. */
    private static boolean in(Collection<String> values, String value) { return value != null && values.contains(value); }

    private static void validateText(String text, String code) {
        if (text == null) return;
        if (text.length() > MAX_TEXT || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n'))
            throw new Invalid(code, "text is at most " + MAX_TEXT + " characters without control characters");
    }

    // ---------- statements ----------

    /** The statement in force for one pair: its explicit cell, or the version default (section 4.1). */
    public record Effective(String statement, List<String> services, String severity, boolean explicit) {}

    /**
     * The statement in force for a pair. {@code UNASSIGNED -> UNASSIGNED} without a cell is DENY_ALL whatever the
     * version default (section 3.3, ZM-41).
     */
    public Effective effective(String source, String destination) { return statements().of(source, destination); }

    /** An index over the cells for many {@link #effective} questions against one version. */
    public Statements statements() { return new Statements(this); }

    public static final class Statements {
        private final ZoneMatrixContent content;
        private final Map<String, Cell> byPair = new HashMap<>();
        private Statements(ZoneMatrixContent content) {
            this.content = content;
            for (Cell c : content.matrix().cells()) byPair.put(c.pair(), c);
        }
        public Effective of(String source, String destination) {
            Cell cell = byPair.get(source + "->" + destination);
            if (cell != null) return new Effective(cell.statement(), content.expand(cell.services()), cell.severity(), true);
            if (source.equals(UNASSIGNED) && destination.equals(UNASSIGNED))
                return new Effective("DENY_ALL", List.of(), content.matrix().defaultSeverity(), false);
            return new Effective(content.matrix().defaultStatement(), List.of(), content.matrix().defaultSeverity(), false);
        }
    }

    /** Service entries with every {@code set:} reference replaced by the set's entries, sorted and distinct. */
    public List<String> expand(List<String> services) {
        TreeSet<String> out = new TreeSet<>();
        for (String entry : services) {
            if (entry.startsWith("set:")) matrix.set(entry.substring(4)).ifPresent(s -> out.addAll(s.entries()));
            else out.add(entry);
        }
        return List.copyOf(out);
    }

    /** True when {@code after} permits something {@code before} does not (statement relaxed or services added). */
    public static boolean looser(Effective before, Effective after) {
        int a = STATEMENTS.indexOf(before.statement()), b = STATEMENTS.indexOf(after.statement());
        if (b != a) return b > a;
        return after.statement().equals("ALLOW_LISTED") && servicesAdded(before.services(), after.services());
    }

    /** True when an entry of {@code after} permits something no entry of {@code before} does (sets expanded). */
    public static boolean servicesAdded(List<String> before, List<String> after) {
        var coverage = ServiceEntry.Coverage.of(before.stream().map(ServiceEntry::parse).toList());
        return after.stream().map(ServiceEntry::parse).anyMatch(coverage::adds);
    }

    /**
     * The hash of what a reference confirmation confirms (section 8.1 "the cell hash it confirmed"): the pair, the
     * statement, the services with sets expanded, the severity and the reference text.
     */
    public String cellSha256(Cell cell) {
        StringBuilder out = new StringBuilder("{\"source\":");
        string(out, cell.source());
        out.append(",\"destination\":"); string(out, cell.destination());
        out.append(",\"statement\":"); string(out, cell.statement());
        out.append(",\"services\":"); strings(out, expand(cell.services()));
        out.append(",\"severity\":"); string(out, cell.severity());
        out.append(",\"regulation_reference\":"); string(out, cell.regulationReference());
        return sha256(out.append('}').toString());
    }

    static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME || !name.equals(name.strip())
                || name.chars().anyMatch(Character::isISOControl))
            throw new Invalid("INVALID_ZONE_NAME", "zone name must be 1-" + MAX_NAME + " characters without control characters or outer spaces");
        if (name.equalsIgnoreCase("UNASSIGNED")) throw new Invalid("INVALID_ZONE_NAME", "UNASSIGNED is the built-in pseudo-zone");
    }

    /** A refused edit, with a stable code for the API and a reason that never echoes the refused value. */
    public static final class Invalid extends IllegalArgumentException {
        public final String code;
        public Invalid(String code, String reason) { super(reason); this.code = code; }
    }

    // ---------- canonical JSON ----------

    public String canonicalJson() {
        StringBuilder out = new StringBuilder(128 + zones.size() * 96 + matrix.cells().size() * 160);
        out.append("{\"schema\":").append(SCHEMA).append(",\"zones\":[");
        for (int i = 0; i < zones.size(); i++) {
            Zone z = zones.get(i);
            if (i > 0) out.append(',');
            out.append("{\"id\":"); string(out, z.id());
            out.append(",\"name\":"); string(out, z.name());
            out.append(",\"description\":"); string(out, z.description());
            out.append(",\"role\":"); nullable(out, z.role());
            out.append(",\"blocks\":");
            strings(out, z.blocks().stream().map(ZoneBlock::toString).toList());
            out.append('}');
        }
        out.append("],\"matrix\":{\"default_statement\":"); string(out, matrix.defaultStatement());
        out.append(",\"default_severity\":"); string(out, matrix.defaultSeverity());
        out.append(",\"template_origin\":");
        if (matrix.templateOrigin() == null) out.append("null");
        else {
            out.append("{\"name\":"); string(out, matrix.templateOrigin().name());
            out.append(",\"revision\":"); string(out, matrix.templateOrigin().revision());
            out.append('}');
        }
        out.append(",\"service_sets\":[");
        for (int i = 0; i < matrix.serviceSets().size(); i++) {
            ServiceSet set = matrix.serviceSets().get(i);
            if (i > 0) out.append(',');
            out.append("{\"name\":"); string(out, set.name());
            out.append(",\"entries\":"); strings(out, set.entries());
            out.append('}');
        }
        out.append("],\"cells\":[");
        for (int i = 0; i < matrix.cells().size(); i++) {
            Cell c = matrix.cells().get(i);
            if (i > 0) out.append(',');
            out.append("{\"source\":"); string(out, c.source());
            out.append(",\"destination\":"); string(out, c.destination());
            out.append(",\"statement\":"); string(out, c.statement());
            out.append(",\"services\":"); strings(out, c.services());
            out.append(",\"severity\":"); string(out, c.severity());
            out.append(",\"rationale\":"); nullable(out, c.rationale());
            out.append(",\"regulation_reference\":"); string(out, c.regulationReference());
            out.append(",\"reference_status\":"); string(out, c.referenceStatus());
            out.append(",\"confirmed_by\":"); nullable(out, c.confirmedBy());
            out.append(",\"confirmed_at\":"); nullable(out, c.confirmedAt());
            out.append('}');
        }
        return out.append("]}}").toString();
    }

    public String sha256() { return sha256(canonicalJson()); }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void nullable(StringBuilder out, String value) { if (value == null) out.append("null"); else string(out, value); }

    private static void strings(StringBuilder out, List<String> values) {
        out.append('[');
        for (int j = 0; j < values.size(); j++) { if (j > 0) out.append(','); string(out, values.get(j)); }
        out.append(']');
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        out.append('"');
    }

    /** Reads content stored by {@link #canonicalJson()}, schema 1 (step 1, zones only) or schema 2. */
    public static ZoneMatrixContent parse(String json, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(json);
            int schema = root.path("schema").asInt();
            if (schema != 1 && schema != SCHEMA) throw new IllegalStateException("unknown zone-matrix content schema");
            List<Zone> zones = new ArrayList<>();
            for (JsonNode z : root.path("zones")) {
                List<ZoneBlock> blocks = new ArrayList<>();
                for (JsonNode b : z.path("blocks")) blocks.add(ZoneBlock.parse(b.asText()));
                zones.add(new Zone(z.path("id").asText(), z.path("name").asText(), z.path("description").asText(""),
                        text(z.path("role")), blocks));
            }
            if (schema == 1) return new ZoneMatrixContent(zones);
            JsonNode m = root.path("matrix");
            JsonNode origin = m.path("template_origin");
            List<ServiceSet> sets = new ArrayList<>();
            for (JsonNode set : m.path("service_sets")) sets.add(new ServiceSet(set.path("name").asText(), texts(set.path("entries"))));
            List<Cell> cells = new ArrayList<>();
            for (JsonNode c : m.path("cells"))
                cells.add(new Cell(c.path("source").asText(), c.path("destination").asText(), c.path("statement").asText(),
                        texts(c.path("services")), c.path("severity").asText(), text(c.path("rationale")),
                        c.path("regulation_reference").asText(""), c.path("reference_status").asText(),
                        text(c.path("confirmed_by")), text(c.path("confirmed_at"))));
            return new ZoneMatrixContent(zones, new Matrix(m.path("default_statement").asText(), m.path("default_severity").asText(),
                    origin.isObject() ? new TemplateOrigin(origin.path("name").asText(), origin.path("revision").asText()) : null,
                    sets, cells));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("stored zone-matrix content is not readable", e);
        }
    }

    private static String text(JsonNode node) { return node.isMissingNode() || node.isNull() ? null : node.asText(); }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) out.add(n.asText());
        return out;
    }
}
