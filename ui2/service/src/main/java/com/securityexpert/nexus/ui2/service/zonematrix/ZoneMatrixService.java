package com.securityexpert.nexus.ui2.service.zonematrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.policy.PolicyPrivacy;
import com.securityexpert.nexus.ui2.service.policy.PolicyQueryService;
import com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zone matrix delivery steps 1, 2 and 3 (ZONE_MATRIX_CONTRACT.md section 10.1, FROZEN 2026-11-13). Step 1: the zone model,
 * the draft and its revisions, four-eyes publishing, the version views and the audited address lookup. Step 2: the
 * matrix (version default, explicit cells, service vocabulary and named service sets), the template import with empty
 * reference fields, reference confirmation by compliance_admin, and the loosening diff that needs a rationale before
 * publish. Step 3: pure deterministic evaluation of stored policies on demand, witness view gating and audit, and
 * privacy projection.
 */
public final class ZoneMatrixService {
    private static final Logger LOG = LoggerFactory.getLogger(ZoneMatrixService.class);

    private static final Set<String> FIXED_SECTION_NAMES = Set.of(
            "Pre rules", "Post rules", "Local rules", "Inline layer unavailable",
            "Pre NAT rules", "Post NAT rules", "Pre default rules", "Post default rules",
            "Local NAT rules", "Local default rules", "global");

    /** Stored-policy read port for step 3 (backed by {@link PolicyQueryService} in production, or an in-memory map in tests). */
    public interface PolicyCatalog {
        List<Map<String, Object>> catalogViews();
        Optional<PolicySnapshot> find(String policyId);
    }
    /** How much of a version the caller may see (section 3.4 and 8.2). */
    public enum View {
        /** replay_viewer: pseudonymised zone names, no descriptions, no blocks, actors as tokens. */
        MASKED,
        /** zone_matrix_read: names, roles, sizes; no blocks. */
        NAMES,
        /** zone_matrix_zones_read: blocks too; every such response is on record. */
        BLOCKS
    }

    /** Role facts the four-eyes rule needs; production answers them through the RBAC evaluator. */
    public interface Principals {
        boolean holdsComplianceAdmin(String actor);
        boolean isRoot(String actor);
    }

    /** A refusal with an HTTP status and a stable code. */
    public static final class Refused extends RuntimeException {
        public final int status;
        public final String code;
        public final Map<String, Object> details;
        public Refused(int status, String code, String reason) { this(status, code, reason, Map.of()); }
        public Refused(int status, String code, String reason, Map<String, Object> details) {
            super(reason, null, false, false); this.status = status; this.code = code; this.details = details;
        }
    }

    /** One zone as the editor submits it. */
    public record ZoneInput(String name, String description, String role, List<String> blocks) {}

    /** One explicit cell as the editor submits it (section 4.1); reference status is never submitted, it is derived. */
    public record CellInput(String source, String destination, String statement, List<String> services, String severity,
            String rationale, String regulation_reference) {}

    private final ZoneMatrixRepository repository;
    private final Principals principals;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PolicyCatalog policyCatalog;
    private final DeviceRepository devices;
    private final TopologyNamePseudonymizer nameMasker;
    private final SubnetPreservingIpMasker ipMasker;
    private final Consumer<String> onVersionPublished;

    public ZoneMatrixService(ZoneMatrixRepository repository, Principals principals, ObjectMapper mapper, Clock clock) {
        this(repository, principals, mapper, clock, (PolicyCatalog) null, null,
                new TopologyNamePseudonymizer(new byte[32]), new SubnetPreservingIpMasker(new byte[32]), null);
    }

    public ZoneMatrixService(
            ZoneMatrixRepository repository,
            Principals principals,
            ObjectMapper mapper,
            Clock clock,
            PolicyQueryService policies,
            DeviceRepository devices,
            TopologyNamePseudonymizer nameMasker,
            SubnetPreservingIpMasker ipMasker) {
        this(repository, principals, mapper, clock, wrapCatalog(policies), devices, nameMasker, ipMasker, null);
    }

    public ZoneMatrixService(
            ZoneMatrixRepository repository,
            Principals principals,
            ObjectMapper mapper,
            Clock clock,
            PolicyQueryService policies,
            DeviceRepository devices,
            TopologyNamePseudonymizer nameMasker,
            SubnetPreservingIpMasker ipMasker,
            Consumer<String> onVersionPublished) {
        this(repository, principals, mapper, clock, wrapCatalog(policies), devices, nameMasker, ipMasker,
                onVersionPublished);
    }

    public ZoneMatrixService(
            ZoneMatrixRepository repository,
            Principals principals,
            ObjectMapper mapper,
            Clock clock,
            PolicyCatalog policyCatalog,
            DeviceRepository devices,
            TopologyNamePseudonymizer nameMasker,
            SubnetPreservingIpMasker ipMasker) {
        this(repository, principals, mapper, clock, policyCatalog, devices, nameMasker, ipMasker, null);
    }

    public ZoneMatrixService(
            ZoneMatrixRepository repository,
            Principals principals,
            ObjectMapper mapper,
            Clock clock,
            PolicyCatalog policyCatalog,
            DeviceRepository devices,
            TopologyNamePseudonymizer nameMasker,
            SubnetPreservingIpMasker ipMasker,
            Consumer<String> onVersionPublished) {
        this.repository = repository;
        this.principals = principals;
        this.mapper = mapper;
        this.clock = clock;
        this.policyCatalog = policyCatalog;
        this.devices = devices;
        this.nameMasker = nameMasker != null ? nameMasker : new TopologyNamePseudonymizer(new byte[32]);
        this.ipMasker = ipMasker != null ? ipMasker : new SubnetPreservingIpMasker(new byte[32]);
        this.onVersionPublished = onVersionPublished;
    }

    private static PolicyCatalog wrapCatalog(PolicyQueryService policies) {
        if (policies == null) return null;
        return new PolicyCatalog() {
            @Override public List<Map<String, Object>> catalogViews() { return policies.catalogViews(); }
            @Override public Optional<PolicySnapshot> find(String policyId) {
                return policies.find(policyId).map(s -> ZoneMatrixPolicyBridge.withDomainObjects(policies, s));
            }
        };
    }

    // ---------------------------------------------------------------- reads

    /** Published versions for every reader; drafts and discarded drafts only for editors and publishers (section 7.1). */
    public List<Map<String, Object>> versions(View view, boolean reviewer) {
        var actors = new ActorTokens(view);
        return repository.list().stream().filter(v -> reviewer || published(v)).map(v -> summary(v, actors)).toList();
    }

    public Map<String, Object> version(String versionId, View view, String actor, boolean reviewer) {
        Version version = repository.find(versionId).filter(v -> reviewer || published(v))
                .orElseThrow(() -> new Refused(404, "NOT_FOUND", "no such version"));
        if (view == View.BLOCKS) record(actor, "BLOCKS_VIEW", versionId);
        return detail(version, view);
    }

    /**
     * The draft (or {@code draft: null}), its diff against the active version, and the publish check for this caller.
     * A reader who may neither edit nor publish learns only that a draft exists (section 7.1: the draft is shown to
     * editors).
     */
    public Map<String, Object> draft(View view, String actor, boolean replayViewer, boolean reviewer) {
        Optional<Version> draft = repository.draft();
        Optional<Version> active = repository.active();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", active.map(v -> summary(v, new ActorTokens(view))).orElse(null));
        out.put("draft_exists", draft.isPresent());
        if (draft.isEmpty() || !reviewer) { out.put("draft", null); out.put("diff", null); out.put("publish_check", null); return out; }
        if (view == View.BLOCKS) record(actor, "BLOCKS_VIEW", draft.get().versionId());
        out.put("draft", detail(draft.get(), view));
        out.put("diff", diff(active.map(this::content), content(draft.get()), view));
        out.put("publish_check", publishCheck(draft.get(), active, actor, replayViewer));
        return out;
    }

    /** Which zone an address belongs to under one version (default: the active version). Always on record. */
    public Map<String, Object> lookup(String actor, String address, String versionId) {
        ZoneBlock parsed;
        try { parsed = ZoneBlock.address(address == null ? "" : address.strip()); }
        catch (IllegalArgumentException e) { throw new Refused(400, "INVALID_ADDRESS", e.getMessage()); }
        Version version = (versionId == null || versionId.isBlank() ? repository.active() : repository.find(versionId))
                .orElseThrow(() -> new Refused(404, "NOT_FOUND", "no such version"));
        record(actor, "ADDRESS_LOOKUP", version.versionId());
        ZoneMatrixContent content = content(version);
        String zoneId = content.partition().zoneOf(parsed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version_id", version.versionId());
        out.put("number", version.number());
        out.put("state", version.state());
        out.put("family", parsed.family().name());
        out.put("zone_id", zoneId);
        out.put("zone_name", zoneId == null ? "UNASSIGNED" : content.zone(zoneId).map(ZoneMatrixContent.Zone::name).orElse(null));
        return out;
    }

    // ---------------------------------------------------------------- edits

    public Map<String, Object> createZone(String actor, Long expectedRevision, ZoneInput input) {
        String zoneId = UUID.randomUUID().toString();
        return edit(actor, expectedRevision, "ZONE_CREATE", zoneId, content -> {
            List<ZoneMatrixContent.Zone> zones = new ArrayList<>(content.zones());
            zones.add(zone(zoneId, input));
            return content.withZones(zones);
        });
    }

    public Map<String, Object> updateZone(String actor, Long expectedRevision, String zoneId, ZoneInput input) {
        return edit(actor, expectedRevision, "ZONE_UPDATE", zoneId, content -> {
            if (content.zone(zoneId).isEmpty()) throw new Refused(404, "ZONE_NOT_FOUND", "no such zone in the draft");
            return content.withZones(content.zones().stream().map(z -> z.id().equals(zoneId) ? zone(zoneId, input) : z).toList());
        });
    }

    public Map<String, Object> deleteZone(String actor, Long expectedRevision, String zoneId) {
        return edit(actor, expectedRevision, "ZONE_DELETE", zoneId, content -> {
            if (content.zone(zoneId).isEmpty()) throw new Refused(404, "ZONE_NOT_FOUND", "no such zone in the draft");
            return content.withZones(content.zones().stream().filter(z -> !z.id().equals(zoneId)).toList());
        });
    }

    // ---------------------------------------------------------------- matrix edits (step 2)

    /**
     * Sets one explicit cell. A change to its statement, services, severity or reference text sets a confirmed
     * reference back to PROPOSED and clears who confirmed it (section 3.4 "References", ZM-23); an empty reference is
     * EMPTY.
     */
    public Map<String, Object> setCell(String actor, Long expectedRevision, CellInput input) {
        if (input == null || input.source() == null || input.destination() == null)
            throw new Refused(400, "INVALID_CELL", "source and destination are required");
        List<String> services = services(input.services());
        String reference = input.regulation_reference() == null ? "" : input.regulation_reference().strip();
        String rationale = input.rationale() == null || input.rationale().isBlank() ? null : input.rationale().strip();
        return edit(actor, expectedRevision, "CELL_SET", null, content -> {
            Optional<ZoneMatrixContent.Cell> old = content.matrix().cell(input.source(), input.destination());
            var draft = new ZoneMatrixContent.Cell(input.source(), input.destination(), input.statement(), services,
                    input.severity(), rationale, reference, "PROPOSED", null, null);
            boolean keep = old.isPresent() && "CONFIRMED".equals(old.get().referenceStatus())
                    && old.get().statement().equals(draft.statement()) && old.get().services().equals(draft.services())
                    && old.get().severity().equals(draft.severity()) && old.get().regulationReference().equals(reference);
            var cell = new ZoneMatrixContent.Cell(draft.source(), draft.destination(), draft.statement(), draft.services(),
                    draft.severity(), rationale, reference, reference.isBlank() ? "EMPTY" : keep ? "CONFIRMED" : "PROPOSED",
                    keep ? old.get().confirmedBy() : null, keep ? old.get().confirmedAt() : null);
            List<ZoneMatrixContent.Cell> cells = new ArrayList<>(content.matrix().cells());
            cells.removeIf(c -> c.pair().equals(cell.pair()));
            cells.add(cell);
            return content.withMatrix(content.matrix().withCells(cells));
        }, null);
    }

    /** Removes one explicit cell; the pair then follows the version default. */
    public Map<String, Object> clearCell(String actor, Long expectedRevision, String source, String destination) {
        return edit(actor, expectedRevision, "CELL_CLEAR", null, content -> {
            if (content.matrix().cell(source, destination).isEmpty()) throw new Refused(404, "CELL_NOT_FOUND", "no explicit cell for this pair");
            return content.withMatrix(content.matrix().withCells(content.matrix().cells().stream()
                    .filter(c -> !(c.source().equals(source) && c.destination().equals(destination))).toList()));
        }, null);
    }

    /** Sets the version default: DENY_ALL or ALLOW_LISTED, never ALLOW_ANY (section 3.5, ZM-41). */
    public Map<String, Object> setDefault(String actor, Long expectedRevision, String statement, String severity) {
        return edit(actor, expectedRevision, "DEFAULT_SET", null, content -> {
            var m = content.matrix();
            return content.withMatrix(new ZoneMatrixContent.Matrix(statement, severity, m.templateOrigin(), m.serviceSets(), m.cells()));
        }, null);
    }

    /**
     * Creates or replaces a named service set. A confirmed cell that uses the set has its services changed, so its
     * reference goes back to PROPOSED (section 3.4).
     */
    public Map<String, Object> saveServiceSet(String actor, Long expectedRevision, String name, List<String> entries) {
        List<String> parsed = services(entries);
        return edit(actor, expectedRevision, "SERVICE_SET_SAVE", null, content -> {
            var m = content.matrix();
            var set = new ZoneMatrixContent.ServiceSet(name == null ? "" : name, parsed);
            boolean changed = m.set(set.name()).map(old -> !old.entries().equals(set.entries())).orElse(false);
            List<ZoneMatrixContent.ServiceSet> sets = new ArrayList<>(m.serviceSets());
            sets.removeIf(s -> s.name().equals(set.name()));
            sets.add(set);
            List<ZoneMatrixContent.Cell> cells = m.cells().stream().map(c -> changed && c.services().contains("set:" + set.name())
                    && "CONFIRMED".equals(c.referenceStatus()) ? unconfirmed(c) : c).toList();
            return content.withMatrix(new ZoneMatrixContent.Matrix(m.defaultStatement(), m.defaultSeverity(), m.templateOrigin(), sets, cells));
        }, null);
    }

    /** Deletes a named service set no cell uses. */
    public Map<String, Object> deleteServiceSet(String actor, Long expectedRevision, String name) {
        return edit(actor, expectedRevision, "SERVICE_SET_DELETE", null, content -> {
            var m = content.matrix();
            if (m.set(name == null ? "" : name).isEmpty()) throw new Refused(404, "SERVICE_SET_NOT_FOUND", "no such service set");
            if (m.cells().stream().anyMatch(c -> c.services().contains("set:" + name)))
                throw new Refused(409, "SERVICE_SET_IN_USE", "a cell uses this service set; change the cell first");
            return content.withMatrix(new ZoneMatrixContent.Matrix(m.defaultStatement(), m.defaultSeverity(), m.templateOrigin(),
                    m.serviceSets().stream().filter(s -> !s.name().equals(name)).toList(), m.cells()));
        }, null);
    }

    /**
     * Seeds the draft's cells from the template by zone role (section 6.1), with every reference field empty. A draft
     * that already has cells is replaced only when the editor says so.
     */
    public Map<String, Object> importTemplate(String actor, Long expectedRevision, boolean replace) {
        return edit(actor, expectedRevision, "TEMPLATE_IMPORT", null, content -> {
            if (!content.matrix().cells().isEmpty() && !replace)
                throw new Refused(409, "TEMPLATE_REPLACES_CELLS", "the draft already has cells; importing the template replaces them all");
            return content.withMatrix(ZoneMatrixTemplate.seed(content));
        }, null);
    }

    /**
     * Confirms one cell's reference (section 3.4 and 6.1): compliance_admin only, never the local root administrator
     * or aiview. The request carries the hash of the cell the confirmer read; a different cell is REQUEST_CHANGED. The
     * confirmation is a draft revision (so the confirmer is a participant of the four-eyes rule, ZM-22) and a row in
     * the append-only confirmation table.
     */
    public Map<String, Object> confirmReference(String actor, boolean replayViewer, Long expectedRevision, String source,
            String destination, String cellSha256) {
        if (replayViewer || principals.isRoot(actor) || !principals.holdsComplianceAdmin(actor))
            throw new Refused(403, "COMPLIANCE_ROLE_REQUIRED", "only a compliance administrator confirms a reference");
        if (source == null || destination == null || cellSha256 == null)
            throw new Refused(400, "INVALID_CONFIRMATION", "source, destination and cell_sha256 are required");
        Instant now = clock.instant();
        return edit(actor, expectedRevision, "REFERENCE_CONFIRM", null, content -> {
            var cell = content.matrix().cell(source, destination)
                    .orElseThrow(() -> new Refused(404, "CELL_NOT_FOUND", "no explicit cell for this pair"));
            if (!content.cellSha256(cell).equals(cellSha256)) throw changed();
            if (cell.referenceStatus().equals("EMPTY")) throw new Refused(409, "REFERENCE_EMPTY", "the cell has no reference to confirm");
            if (cell.referenceStatus().equals("CONFIRMED")) throw new Refused(409, "ALREADY_CONFIRMED", "the reference is already confirmed");
            var confirmed = new ZoneMatrixContent.Cell(cell.source(), cell.destination(), cell.statement(), cell.services(),
                    cell.severity(), cell.rationale(), cell.regulationReference(), "CONFIRMED", actor, now.toString());
            return content.withMatrix(content.matrix().withCells(content.matrix().cells().stream()
                    .map(c -> c.pair().equals(cell.pair()) ? confirmed : c).toList()));
        }, (tx, draftId, revision) -> tx.recordConfirmation(draftId, revision, source, destination, cellSha256, actor, now));
    }

    private static ZoneMatrixContent.Cell unconfirmed(ZoneMatrixContent.Cell c) {
        return new ZoneMatrixContent.Cell(c.source(), c.destination(), c.statement(), c.services(), c.severity(), c.rationale(),
                c.regulationReference(), "PROPOSED", null, null);
    }

    /** Parses submitted service entries; refused lines are reported one by one, never dropped (section 3.1). */
    private static List<String> services(List<String> texts) {
        List<String> values = texts == null ? List.of() : texts;
        if (values.size() > ZoneMatrixContent.MAX_SERVICES_PER_CELL)
            throw new Refused(400, "TOO_MANY_SERVICES", "at most " + ZoneMatrixContent.MAX_SERVICES_PER_CELL + " services");
        List<String> out = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            String text = values.get(i) == null ? "" : values.get(i).strip();
            try { out.add(ServiceEntry.parse(text).text()); }
            catch (IllegalArgumentException e) { errors.add(Map.of("index", i, "reason", e.getMessage())); }
        }
        if (!errors.isEmpty()) throw new Refused(400, "INVALID_SERVICES", "some services are not canonical entries", Map.of("errors", errors));
        return out;
    }

    /** The template rows and how they are read, for the Matrix tab (section 6.1). */
    public Map<String, Object> template() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", ZoneMatrixTemplate.NAME);
        out.put("revision", ZoneMatrixTemplate.REVISION);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var row : ZoneMatrixTemplate.ROWS) rows.add(Map.of("row", row.text(), "statement", row.statement(), "severity", row.severity()));
        var u = ZoneMatrixTemplate.UNASSIGNED_ROW;
        rows.add(Map.of("row", u.text(), "statement", u.statement(), "severity", u.severity()));
        rows.add(Map.of("row", "any other pair (version default)", "statement", "DENY_ALL", "severity", "MEDIUM"));
        out.put("rows", rows);
        out.put("basis", "Overwatch proposal based on general segmentation practice, not on a BDDK clause");
        out.put("roles", ZoneMatrixContent.ROLES.stream().sorted().toList());
        return out;
    }

    public Map<String, Object> discardDraft(String actor, long expectedRevision) {
        Instant now = clock.instant();
        return repository.locked(tx -> {
            Version draft = tx.draft().orElseThrow(() -> new Refused(404, "NO_DRAFT", "there is no draft"));
            if (draft.revision() != expectedRevision) throw changed();
            tx.appendRevision(draft.versionId(), draft.revision() + 1, actor, now, "DRAFT_DISCARD", null,
                    draft.contentSha256(), draft.content(), null);
            tx.discard(draft.versionId());
            return Map.of("discarded", draft.versionId());
        });
    }

    /** Work done in the edit's transaction after its revision row is written. */
    @FunctionalInterface
    private interface AfterRevision { void run(ZoneMatrixRepository.DraftTransaction tx, String draftId, long revision); }

    private Map<String, Object> edit(String actor, Long expectedRevision, String kind, String zoneId, UnaryOperator<ZoneMatrixContent> change) {
        return edit(actor, expectedRevision, kind, zoneId, change, null);
    }

    private Map<String, Object> edit(String actor, Long expectedRevision, String kind, String zoneId, UnaryOperator<ZoneMatrixContent> change,
            AfterRevision after) {
        Instant now = clock.instant();
        return repository.locked(tx -> {
            Optional<Version> existing = tx.draft();
            if (existing.isPresent() != (expectedRevision != null)
                    || existing.isPresent() && existing.get().revision() != expectedRevision) throw changed();
            String draftId;
            long revision;
            ZoneMatrixContent current;
            if (existing.isEmpty()) {
                Optional<Version> active = tx.active();
                current = active.map(this::content).orElse(ZoneMatrixContent.empty());
                draftId = UUID.randomUUID().toString();
                String json = current.canonicalJson(), sha = current.sha256();
                tx.insertDraft(draftId, json, sha, active.map(Version::versionId).orElse(null), actor, now);
                tx.appendRevision(draftId, 1, actor, now, "DRAFT_CREATE", null, sha, json, null);
                revision = 1;
            } else {
                draftId = existing.get().versionId();
                revision = existing.get().revision();
                current = content(existing.get());
            }
            ZoneMatrixContent next = change.apply(current);
            validate(next);
            String json = next.canonicalJson(), sha = next.sha256();
            tx.updateDraft(draftId, revision, revision + 1, json, sha, actor, now);
            tx.appendRevision(draftId, revision + 1, actor, now, kind, zoneId, sha, json, null);
            if (after != null) after.run(tx, draftId, revision + 1);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version_id", draftId);
            out.put("revision", revision + 1);
            out.put("content_sha256", sha);
            out.put("zone_id", zoneId);
            return out;
        });
    }

    private static ZoneMatrixContent.Zone zone(String zoneId, ZoneInput input) {
        if (input == null) throw new Refused(400, "INVALID_ZONE", "zone body missing");
        List<String> texts = input.blocks() == null ? List.of() : input.blocks();
        if (texts.size() > ZoneMatrixContent.MAX_BLOCKS_PER_ZONE)
            throw new Refused(400, "TOO_MANY_BLOCKS", "at most " + ZoneMatrixContent.MAX_BLOCKS_PER_ZONE + " blocks in a zone");
        List<ZoneBlock> blocks = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i) == null ? "" : texts.get(i).strip();
            try { blocks.add(ZoneBlock.parse(text)); }
            catch (IllegalArgumentException e) { errors.add(Map.of("index", i, "reason", e.getMessage())); }
        }
        if (!errors.isEmpty()) throw new Refused(400, "INVALID_BLOCKS", "some blocks are not canonical CIDR blocks", Map.of("errors", errors));
        String role = input.role() == null || input.role().isBlank() ? null : input.role();
        return new ZoneMatrixContent.Zone(zoneId, input.name(), input.description(), role, blocks);
    }

    private static ZonePartition validate(ZoneMatrixContent content) {
        try {
            return content.validate();
        } catch (ZoneMatrixContent.Invalid e) {
            throw new Refused(400, e.code, e.getMessage());
        } catch (ZonePartition.DuplicateBlock e) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("block", e.block);
            details.put("zones", List.of(name(content, e.firstZoneId), name(content, e.secondZoneId)));
            throw new Refused(409, "ZONE_BLOCK_DUPLICATE", "the same block is in two zones", details);
        }
    }

    // ---------------------------------------------------------------- publish

    public record PublishRequest(Long revision, String content_sha256, String rationale) {}

    /** Four-eyes publish (section 3.4; PO decision 2026-11-13 "Evet, şart"). */
    public Map<String, Object> publish(String actor, boolean replayViewer, PublishRequest request) {
        if (request == null || request.revision() == null || request.content_sha256() == null)
            throw new Refused(400, "INVALID_PUBLISH", "revision and content_sha256 of the reviewed draft are required");
        if (replayViewer || principals.isRoot(actor))
            throw new Refused(403, "PRINCIPAL_NOT_ELIGIBLE", "the local root administrator and aiview never publish");
        String rationale = request.rationale() == null || request.rationale().isBlank() ? null : request.rationale().strip();
        if (rationale != null && rationale.length() > 2000) throw new Refused(400, "INVALID_RATIONALE", "rationale is at most 2000 characters");
        Instant now = clock.instant();
        Map<String, Object> published = repository.locked(tx -> {
            Version draft = tx.draft().orElseThrow(() -> new Refused(404, "NO_DRAFT", "there is no draft"));
            if (draft.revision() != request.revision() || !draft.contentSha256().equals(request.content_sha256())) throw changed();
            Set<String> participants = tx.participants(draft.versionId());
            String refusal = fourEyes(actor, participants);
            if (refusal != null) throw refusal(refusal);
            Optional<Version> active = tx.active();
            ZoneMatrixContent next = content(draft);
            validate(next);
            Optional<ZoneMatrixContent> base = active.map(this::content);
            if (base.isPresent() && base.get().sha256().equals(next.sha256()))
                throw new Refused(409, "NOTHING_TO_PUBLISH", "the draft equals the active version");
            if (!loosening(base, next).isEmpty() && rationale == null)
                throw new Refused(409, "RATIONALE_REQUIRED", "this version loosens the segmentation policy; give a rationale");
            int number = tx.nextNumber();
            active.ifPresent(v -> tx.retire(v.versionId()));
            tx.activate(draft.versionId(), number, actor, now);
            tx.appendRevision(draft.versionId(), draft.revision() + 1, actor, now, "PUBLISH", null, draft.contentSha256(),
                    draft.content(), rationale);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version_id", draft.versionId());
            out.put("number", number);
            out.put("content_sha256", draft.contentSha256());
            return out;
        });
        if (onVersionPublished != null) {
            String publishedVersionId = (String) published.get("version_id");
            try {
                onVersionPublished.accept(publishedVersionId);
            } catch (RuntimeException e) {
                LOG.warn("zone_matrix_publish_trigger_failed version_id={} error_class={}",
                        publishedVersionId, e.getClass().getSimpleName());
            }
        }
        return published;
    }

    /** The four-eyes refusal code for this publisher, or null. Principals are compared in canonical form. */
    String fourEyes(String actor, Set<String> participants) {
        String me = canonical(actor);
        if (participants.stream().map(ZoneMatrixService::canonical).anyMatch(me::equals)) return "SELF_APPROVAL";
        if (!principals.holdsComplianceAdmin(actor) && participants.stream().noneMatch(principals::holdsComplianceAdmin))
            return "COMPLIANCE_ROLE_MISSING";
        return null;
    }

    private Map<String, Object> publishCheck(Version draft, Optional<Version> active, String actor, boolean replayViewer) {
        String reason = replayViewer || principals.isRoot(actor) ? "PRINCIPAL_NOT_ELIGIBLE"
                : active.isPresent() && content(active.get()).sha256().equals(content(draft).sha256()) ? "NOTHING_TO_PUBLISH"
                : fourEyes(actor, participants(draft.versionId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eligible", reason == null);
        out.put("reason", reason);
        return out;
    }

    /** The same set {@link ZoneMatrixRepository.DraftTransaction#participants} returns, read without the write lock. */
    private Set<String> participants(String versionId) {
        Set<String> actors = new LinkedHashSet<>();
        for (var r : repository.revisions(versionId)) if (!r.changeKind().equals("PUBLISH")) actors.add(r.actor());
        return actors;
    }

    private static boolean published(Version v) { return v.state().equals("ACTIVE") || v.state().equals("RETIRED"); }

    private static Refused refusal(String code) {
        return switch (code) {
            case "SELF_APPROVAL" -> new Refused(403, code, "you edited this draft; another person must publish it");
            case "COMPLIANCE_ROLE_MISSING" -> new Refused(403, code, "the publisher or an editor of this draft must hold compliance_admin");
            default -> new Refused(403, code, "not eligible to publish");
        };
    }

    static String canonical(String principal) { return principal == null ? "" : principal.strip().toLowerCase(Locale.ROOT); }

    private static Refused changed() {
        return new Refused(409, "REQUEST_CHANGED", "the draft changed since you loaded it; reload and try again");
    }

    // ---------------------------------------------------------------- projections

    private ZoneMatrixContent content(Version version) {
        String json = version.content() != null ? version.content()
                : repository.find(version.versionId()).map(Version::content).orElseThrow();
        return ZoneMatrixContent.parse(json, mapper);
    }

    private void record(String actor, String kind, String versionId) {
        try { repository.recordAccess(actor, kind, versionId); }
        catch (RuntimeException e) { throw new Refused(503, "ZONE_MATRIX_AUDIT_UNAVAILABLE", "the view was refused because its audit row could not be written"); }
    }

    private Map<String, Object> summary(Version v, ActorTokens actors) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version_id", v.versionId());
        out.put("number", v.number());
        out.put("state", v.state());
        out.put("revision", v.revision());
        out.put("content_sha256", v.contentSha256());
        out.put("created_at", text(v.createdAt()));
        out.put("updated_at", text(v.updatedAt()));
        out.put("published_by", v.publishedBy() == null ? null : actors.token(v.publishedBy()));
        out.put("published_at", text(v.publishedAt()));
        return out;
    }

    private Map<String, Object> detail(Version version, View view) {
        ZoneMatrixContent content = content(version);
        ZonePartition partition = content.partition();
        var sizes = partition.effectiveSizes();
        var names = new ZoneNames(view, content.zones().stream().map(ZoneMatrixContent.Zone::id).toList());
        var actors = new ActorTokens(view);
        Map<String, Object> out = summary(version, actors);
        List<Map<String, Object>> zones = new ArrayList<>();
        for (ZoneMatrixContent.Zone z : content.zones()) {
            Map<String, Object> zone = new LinkedHashMap<>();
            zone.put("zone_id", z.id());
            zone.put("name", names.of(z.id(), z.name()));
            zone.put("description", view == View.MASKED ? null : z.description());
            zone.put("role", z.role());
            zone.put("block_count", z.blocks().size());
            zone.put("effective_addresses", sizeView(sizes.get(z.id())));
            zone.put("blocks", view == View.BLOCKS ? z.blocks().stream().map(ZoneBlock::toString).toList() : null);
            zones.add(zone);
        }
        out.put("zones", zones);
        out.put("matrix", matrixView(content, names, actors, view));
        out.put("unassigned_addresses", sizeView(sizes.get(null)));
        out.put("redundant_blocks", view == View.BLOCKS ? partition.redundant().stream().map(r -> Map.of(
                "zone", names.of(r.zoneId(), name(content, r.zoneId())), "block", r.block(), "within", r.within())).toList()
                : partition.redundant().size());
        out.put("revisions", repository.revisions(version.versionId()).stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("revision", r.revision());
            row.put("actor", actors.token(r.actor()));
            row.put("occurred_at", text(r.occurredAt()));
            row.put("change_kind", r.changeKind());
            row.put("zone_id", r.zoneId());
            row.put("content_sha256", r.contentSha256());
            row.put("rationale_given", r.rationaleGiven());
            return row;
        }).toList());
        return out;
    }

    private Map<String, Object> diff(Optional<ZoneMatrixContent> base, ZoneMatrixContent after, View view) {
        ZoneMatrixContent before = base.orElse(ZoneMatrixContent.empty());
        Set<String> ids = new TreeSet<>();
        before.zones().forEach(z -> ids.add(z.id()));
        after.zones().forEach(z -> ids.add(z.id()));
        var names = new ZoneNames(view, List.copyOf(ids));
        var sizesBefore = before.partition().effectiveSizes();
        ZonePartition afterPartition = after.partition();
        var sizesAfter = afterPartition.effectiveSizes();
        List<Map<String, Object>> zones = new ArrayList<>();
        for (String id : ids) {
            Optional<ZoneMatrixContent.Zone> a = before.zone(id), b = after.zone(id);
            String change = a.isEmpty() ? "ADDED" : b.isEmpty() ? "REMOVED" : a.get().equals(b.get()) ? "UNCHANGED" : "CHANGED";
            if (change.equals("UNCHANGED")) continue;
            Set<String> blocksBefore = new TreeSet<>(a.map(z -> z.blocks().stream().map(ZoneBlock::toString).toList()).orElse(List.of()));
            Set<String> blocksAfter = new TreeSet<>(b.map(z -> z.blocks().stream().map(ZoneBlock::toString).toList()).orElse(List.of()));
            Set<String> added = new TreeSet<>(blocksAfter); added.removeAll(blocksBefore);
            Set<String> removed = new TreeSet<>(blocksBefore); removed.removeAll(blocksAfter);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("zone_id", id);
            row.put("change", change);
            row.put("name_before", a.map(z -> names.of(id, z.name())).orElse(null));
            row.put("name_after", b.map(z -> names.of(id, z.name())).orElse(null));
            row.put("role_before", a.map(ZoneMatrixContent.Zone::role).orElse(null));
            row.put("role_after", b.map(ZoneMatrixContent.Zone::role).orElse(null));
            row.put("blocks_added", view == View.BLOCKS ? List.copyOf(added) : added.size());
            row.put("blocks_removed", view == View.BLOCKS ? List.copyOf(removed) : removed.size());
            row.put("effective_before", sizeView(sizesBefore.get(id)));
            row.put("effective_after", sizeView(sizesAfter.get(id)));
            zones.add(row);
        }
        List<Map<String, Object>> moves = new ArrayList<>();
        for (ZonePartition.Move m : ZonePartition.moves(before.partition(), afterPartition)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", m.fromZoneId() == null ? "UNASSIGNED" : names.of(m.fromZoneId(), name(before, m.fromZoneId())));
            row.put("to", m.toZoneId() == null ? "UNASSIGNED" : names.of(m.toZoneId(), name(after, m.toZoneId())));
            row.put("from_zone_id", m.fromZoneId());
            row.put("to_zone_id", m.toZoneId());
            row.put("family", m.family().name());
            row.put("addresses", m.addresses().toString());
            row.put("leaves_a_zone", m.fromZoneId() != null);
            moves.add(row);
        }
        List<Loosening> loosening = loosening(base, after);
        Map<String, List<String>> looseningByPair = new HashMap<>();
        for (Loosening l : loosening) if (l.source() != null && l.family() == null)
            looseningByPair.computeIfAbsent(l.source() + "->" + l.destination(), k -> new ArrayList<>()).add(l.kind());
        Function<String, String> endpoint = id -> id.equals(ZoneMatrixContent.UNASSIGNED) ? "UNASSIGNED"
                : names.of(id, after.zone(id).or(() -> before.zone(id)).map(ZoneMatrixContent.Zone::name).orElse(id));
        Map<String, ZoneMatrixContent.Cell> cellsBefore = new TreeMap<>(), cellsAfter = new TreeMap<>();
        before.matrix().cells().forEach(c -> cellsBefore.put(c.pair(), c));
        after.matrix().cells().forEach(c -> cellsAfter.put(c.pair(), c));
        Set<String> pairs = new TreeSet<>(cellsBefore.keySet()); pairs.addAll(cellsAfter.keySet());
        var statementsBefore = before.statements();
        var statementsAfter = after.statements();
        List<Map<String, Object>> cells = new ArrayList<>();
        for (String pair : pairs) {
            ZoneMatrixContent.Cell a = cellsBefore.get(pair), b = cellsAfter.get(pair);
            if (a != null && a.equals(b) && before.expand(a.services()).equals(after.expand(b.services()))) continue;
            ZoneMatrixContent.Cell any = b != null ? b : a;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", any.source());
            row.put("destination", any.destination());
            row.put("source_name", endpoint.apply(any.source()));
            row.put("destination_name", endpoint.apply(any.destination()));
            row.put("change", a == null ? "ADDED" : b == null ? "REMOVED" : "CHANGED");
            row.put("before", a == null && !hasEndpoints(before, any) ? null : statementView(statementsBefore.of(any.source(), any.destination()), a));
            row.put("after", b == null && !hasEndpoints(after, any) ? null : statementView(statementsAfter.of(any.source(), any.destination()), b));
            row.put("loosening", looseningByPair.getOrDefault(pair, List.of()));
            cells.add(row);
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("statement_before", base.map(c -> c.matrix().defaultStatement()).orElse(null));
        defaults.put("statement_after", after.matrix().defaultStatement());
        defaults.put("severity_before", base.map(c -> c.matrix().defaultSeverity()).orElse(null));
        defaults.put("severity_after", after.matrix().defaultSeverity());
        List<Map<String, Object>> looseningRows = new ArrayList<>();
        for (Loosening l : loosening) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", l.kind());
            row.put("source", l.source());
            row.put("destination", l.destination());
            row.put("source_name", l.source() == null ? null : endpoint.apply(l.source()));
            row.put("destination_name", l.destination() == null ? null : endpoint.apply(l.destination()));
            row.put("family", l.family());
            row.put("addresses", l.addresses());
            looseningRows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zones", zones);
        out.put("moves", moves);
        out.put("cells", cells);
        out.put("default", defaults);
        out.put("loosening", looseningRows);
        out.put("rationale_required", !loosening.isEmpty());
        out.put("unassigned_before", sizeView(sizesBefore.get(null)));
        out.put("unassigned_after", sizeView(sizesAfter.get(null)));
        return out;
    }

    private static boolean hasEndpoints(ZoneMatrixContent content, ZoneMatrixContent.Cell cell) {
        return (cell.source().equals(ZoneMatrixContent.UNASSIGNED) || content.zone(cell.source()).isPresent())
                && (cell.destination().equals(ZoneMatrixContent.UNASSIGNED) || content.zone(cell.destination()).isPresent());
    }

    /** A pair's statement in a diff: effective services with sets expanded, so no set name is needed to read it. */
    private static Map<String, Object> statementView(ZoneMatrixContent.Effective effective, ZoneMatrixContent.Cell cell) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("explicit", effective.explicit());
        out.put("statement", effective.statement());
        out.put("services", effective.services());
        out.put("severity", effective.severity());
        out.put("reference_status", cell == null ? null : cell.referenceStatus());
        return out;
    }

    /**
     * One loosening change of the publish diff (section 3.5, ZM-36). {@code family} and {@code addresses} are set for
     * address moves only.
     */
    record Loosening(String kind, String source, String destination, String family, String addresses) {}

    /**
     * The loosening changes from the active version to {@code after}; each needs a rationale before publish (section
     * 3.5). Without an active version nothing is loosened: the first version is a definition, not a change.
     *
     * <ul>
     *   <li>{@code DEFAULT_CHANGED}: the version default statement changed, or its severity went down;</li>
     *   <li>{@code STATEMENT_RELAXED}, {@code SERVICES_ADDED}, {@code SEVERITY_DOWNGRADED}: per pair, for every pair
     *       with an explicit cell in either version and both endpoints in both versions (other pairs follow the
     *       default, covered by {@code DEFAULT_CHANGED}); services compare with sets expanded;</li>
     *   <li>{@code ADDRESSES_MOVED}: addresses leave a zone (section 3.2);</li>
     *   <li>{@code UNASSIGNED_SHRUNK}: addresses move from UNASSIGNED into a zone whose statements towards some endpoint,
     *       in either direction, are less strict than UNASSIGNED's were (section 3.3).</li>
     * </ul>
     */
    List<Loosening> loosening(Optional<ZoneMatrixContent> base, ZoneMatrixContent after) {
        if (base.isEmpty()) return List.of();
        ZoneMatrixContent before = base.get();
        List<Loosening> out = new ArrayList<>();
        var mb = before.matrix();
        var ma = after.matrix();
        if (!mb.defaultStatement().equals(ma.defaultStatement()) || severity(ma.defaultSeverity()) < severity(mb.defaultSeverity()))
            out.add(new Loosening("DEFAULT_CHANGED", null, null, null, null));
        Set<String> both = new HashSet<>(before.endpoints());
        both.retainAll(after.endpoints());
        var sb = before.statements();
        var sa = after.statements();
        Set<String> pairs = new TreeSet<>();
        List<String[]> ordered = new ArrayList<>();
        for (var c : mb.cells()) if (pairs.add(c.pair())) ordered.add(new String[]{c.source(), c.destination()});
        for (var c : ma.cells()) if (pairs.add(c.pair())) ordered.add(new String[]{c.source(), c.destination()});
        ordered.sort(Comparator.comparing((String[] p) -> p[0]).thenComparing(p -> p[1]));
        for (String[] p : ordered) {
            if (!both.contains(p[0]) || !both.contains(p[1])) continue;
            var eb = sb.of(p[0], p[1]);
            var ea = sa.of(p[0], p[1]);
            int rb = ZoneMatrixContent.STATEMENTS.indexOf(eb.statement()), ra = ZoneMatrixContent.STATEMENTS.indexOf(ea.statement());
            if (ra > rb) out.add(new Loosening("STATEMENT_RELAXED", p[0], p[1], null, null));
            else if (ra == rb && ea.statement().equals("ALLOW_LISTED") && ZoneMatrixContent.servicesAdded(eb.services(), ea.services()))
                out.add(new Loosening("SERVICES_ADDED", p[0], p[1], null, null));
            if (severity(ea.severity()) < severity(eb.severity())) out.add(new Loosening("SEVERITY_DOWNGRADED", p[0], p[1], null, null));
        }
        Map<String, Boolean> receivesLooser = new HashMap<>();
        for (ZonePartition.Move m : ZonePartition.moves(before.partition(), after.partition())) {
            String from = m.fromZoneId() == null ? ZoneMatrixContent.UNASSIGNED : m.fromZoneId();
            String to = m.toZoneId() == null ? ZoneMatrixContent.UNASSIGNED : m.toZoneId();
            if (m.fromZoneId() != null)
                out.add(new Loosening("ADDRESSES_MOVED", from, to, m.family().name(), m.addresses().toString()));
            else if (receivesLooser.computeIfAbsent(to, z -> looserThanUnassigned(before, sb, after, sa, z)))
                out.add(new Loosening("UNASSIGNED_SHRUNK", from, to, m.family().name(), m.addresses().toString()));
        }
        return out;
    }

    /** Whether zone {@code zone} of {@code after} is less strict, towards any endpoint, than UNASSIGNED was before. */
    private static boolean looserThanUnassigned(ZoneMatrixContent before, ZoneMatrixContent.Statements sb, ZoneMatrixContent after,
            ZoneMatrixContent.Statements sa, String zone) {
        String u = ZoneMatrixContent.UNASSIGNED;
        for (String x : after.endpoints()) {
            String xb = x.equals(zone) ? u : x;
            if (!xb.equals(u) && before.zone(xb).isEmpty()) continue;
            if (ZoneMatrixContent.looser(sb.of(u, xb), sa.of(zone, x)) || ZoneMatrixContent.looser(sb.of(xb, u), sa.of(x, zone))) return true;
        }
        return false;
    }

    private static int severity(String severity) { return ZoneMatrixContent.SEVERITIES.indexOf(severity); }

    /** The matrix of one version as the caller may see it (section 3.4 and 8.2). */
    private Map<String, Object> matrixView(ZoneMatrixContent content, ZoneNames names, ActorTokens actors, View view) {
        var m = content.matrix();
        Map<String, String> setNames = new HashMap<>();
        for (int i = 0; i < m.serviceSets().size(); i++)
            setNames.put(m.serviceSets().get(i).name(), view == View.MASKED ? "SET-" + (i + 1) : m.serviceSets().get(i).name());
        Function<String, String> endpoint = id -> id.equals(ZoneMatrixContent.UNASSIGNED) ? "UNASSIGNED"
                : names.of(id, name(content, id));
        Function<String, Map<String, Object>> entry = text -> {
            ServiceEntry parsed = ServiceEntry.parse(text);
            String reason = parsed.kind() == ServiceEntry.Kind.SET
                    ? m.set(parsed.token()).map(s -> s.entries().stream().map(e -> ServiceEntry.parse(e).notEvaluableReason())
                            .filter(Objects::nonNull).findFirst().orElse(null)).orElse("unknown service set")
                    : parsed.notEvaluableReason();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entry", parsed.kind() == ServiceEntry.Kind.SET ? "set:" + setNames.getOrDefault(parsed.token(), "SET-?") : text);
            row.put("evaluable", reason == null);
            row.put("not_evaluable_reason", reason);
            return row;
        };
        List<Map<String, Object>> sets = new ArrayList<>();
        for (var set : m.serviceSets()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", setNames.get(set.name()));
            row.put("entries", set.entries().stream().map(entry).toList());
            sets.add(row);
        }
        List<Map<String, Object>> cells = new ArrayList<>();
        int withReference = 0, confirmed = 0;
        for (var c : m.cells()) {
            if (!c.referenceStatus().equals("EMPTY")) withReference++;
            if (c.referenceStatus().equals("CONFIRMED")) confirmed++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", c.source());
            row.put("destination", c.destination());
            row.put("source_name", endpoint.apply(c.source()));
            row.put("destination_name", endpoint.apply(c.destination()));
            row.put("statement", c.statement());
            row.put("services", c.services().stream().map(entry).toList());
            row.put("severity", c.severity());
            row.put("rationale", view == View.MASKED ? null : c.rationale());
            row.put("regulation_reference", view == View.MASKED ? null : c.regulationReference());
            row.put("reference_status", c.referenceStatus());
            row.put("confirmed_by", c.confirmedBy() == null ? null : actors.token(c.confirmedBy()));
            row.put("confirmed_at", c.confirmedAt());
            row.put("cell_sha256", content.cellSha256(c));
            cells.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("default_statement", m.defaultStatement());
        out.put("default_severity", m.defaultSeverity());
        out.put("unassigned_to_unassigned", "DENY_ALL unless an explicit cell says otherwise");
        out.put("template_origin", m.templateOrigin() == null ? null
                : Map.of("name", m.templateOrigin().name(), "revision", m.templateOrigin().revision()));
        out.put("service_sets", sets);
        out.put("cells", cells);
        out.put("cells_total", m.cells().size());
        out.put("cells_with_reference", withReference);
        out.put("cells_confirmed", confirmed);
        return out;
    }

    private static Map<String, String> sizeView(Map<ZoneBlock.Family, BigInteger> sizes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ZoneBlock.Family f : ZoneBlock.Family.values())
            out.put(f.name(), (sizes == null ? BigInteger.ZERO : sizes.getOrDefault(f, BigInteger.ZERO)).toString());
        return out;
    }

    private static String name(ZoneMatrixContent content, String zoneId) {
        return content.zone(zoneId).map(ZoneMatrixContent.Zone::name).orElse(zoneId);
    }

    private static String text(Instant instant) { return instant == null ? null : instant.toString(); }

    /** Masked zone names: {@code ZONE-<n>} by the stable order of zone id (section 8.2). */
    private static final class ZoneNames {
        private final View view;
        private final Map<String, String> masked = new HashMap<>();
        ZoneNames(View view, List<String> ids) {
            this.view = view;
            List<String> sorted = new ArrayList<>(ids); Collections.sort(sorted);
            for (int i = 0; i < sorted.size(); i++) masked.put(sorted.get(i), "ZONE-" + (i + 1));
        }
        String of(String id, String name) { return view == View.MASKED ? masked.getOrDefault(id, "ZONE-?") : name; }
    }

    /** Actor fingerprints as shown: as stored, or {@code USER-<n>} tokens in the masked view. */
    // ---------------------------------------------------------------- step 3: stored-policy evaluation

    /**
     * Lists stored policies eligible for evaluation ({@code CP}, {@code PAN}) and explicitly lists non-evaluated
     * {@code ASA} / {@code FGT} snapshots and enrolled devices (§5.8: {@code not evaluated: policy not collected}).
     */
    public Map<String, Object> policies(View view, boolean hasPolicyRead) {
        boolean maskPolicy = view == View.MASKED || !hasPolicyRead;
        List<Map<String, Object>> catalog = policyCatalog == null ? List.of() : policyCatalog.catalogViews();
        List<Map<String, Object>> evaluable = new ArrayList<>();
        List<Map<String, Object>> notEvaluated = new ArrayList<>();
        Set<String> seenNonEvaluatedSources = new HashSet<>();

        for (Map<String, Object> item : catalog) {
            String id = str(item.get("id"));
            String vendor = str(item.get("vendor"));
            String sourceId = str(item.get("sourceId"));
            String rawSourceName = str(item.get("sourceName"));
            String rawContainerName = str(item.get("containerName"));
            String rawPolicyName = str(item.get("name"));
            String sourceName = maskPolicy ? maskSourceName(rawSourceName, sourceId) : rawSourceName;
            String containerName = maskPolicy ? maskDomainName(rawContainerName) : rawContainerName;
            String policyName = maskPolicy ? maskPolicyLabel("policy", rawPolicyName) : rawPolicyName;

            if ("CP".equals(vendor) || "PAN".equals(vendor)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("name", policyName);
                row.put("vendor", vendor);
                row.put("source_id", sourceId);
                row.put("source_name", sourceName);
                row.put("container_id", str(item.get("containerId")));
                row.put("container_name", containerName);
                row.put("collected_at", str(item.get("collectedAt")));
                row.put("rule_count", item.get("ruleCount") instanceof Number n ? n.intValue() : 0);
                row.put("target_count", item.get("targets") instanceof List<?> t ? t.size() : 0);
                evaluable.add(row);
            } else {
                if (sourceId != null && !sourceId.isBlank()) seenNonEvaluatedSources.add(sourceId);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("name", policyName);
                row.put("vendor", vendor);
                row.put("source_id", sourceId);
                row.put("source_name", sourceName);
                row.put("reason", "not evaluated: policy not collected");
                notEvaluated.add(row);
            }
        }

        if (devices != null) {
            for (DeviceSummaryRecord device : devices.listAll()) {
                if (device == null || device.deviceId() == null || seenNonEvaluatedSources.contains(device.deviceId())) {
                    continue;
                }
                String hint = device.vendorHint() == null ? "" : device.vendorHint().toLowerCase(Locale.ROOT);
                String vendor = hint.contains("asa") ? "ASA"
                        : (hint.contains("forti") ? "FGT" : null);
                if (vendor == null) continue;
                seenNonEvaluatedSources.add(device.deviceId());
                String rawHost = device.observedHostname().orElse(null);
                String display = maskPolicy
                        ? maskSourceName(rawHost, device.deviceId())
                        : (rawHost != null && !rawHost.isBlank() ? rawHost : device.deviceId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", "device:" + device.deviceId());
                row.put("name", display);
                row.put("vendor", vendor);
                row.put("source_id", device.deviceId());
                row.put("source_name", display);
                row.put("reason", "not evaluated: policy not collected");
                notEvaluated.add(row);
            }
        }

        evaluable.sort(Comparator
                .comparing((Map<String, Object> m) -> strOrEmpty(m.get("vendor")))
                .thenComparing(m -> strOrEmpty(m.get("source_name")), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(m -> strOrEmpty(m.get("container_name")), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(m -> strOrEmpty(m.get("name")), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(m -> strOrEmpty(m.get("id"))));
        notEvaluated.sort(Comparator
                .comparing((Map<String, Object> m) -> strOrEmpty(m.get("vendor")))
                .thenComparing(m -> strOrEmpty(m.get("name")), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(m -> strOrEmpty(m.get("id"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policies", evaluable);
        out.put("not_evaluated", notEvaluated);
        return out;
    }

    /**
     * Evaluates one stored policy snapshot on demand against the active (or selected published) zone-matrix version.
     *
     * <p>Enforces §3.4 RBAC and witness auditing ({@code WITNESS_VIEW} in {@code zone_matrix_access_log} and
     * {@code audit_log}), §3.4 {@code PolicyPrivacy.mask} rule-name projection when {@code !hasPolicyRead}, and §8.1
     * {@code ZM-10} safe logging (opaque ids, version number, sha256, duration and outcome counts only).</p>
     */
    public Map<String, Object> evaluate(
            String actor,
            View view,
            boolean replayViewer,
            boolean canViewBlocks,
            boolean hasPolicyRead,
            String policyId,
            String versionId,
            boolean includeWitnesses) {
        if (replayViewer) {
            throw new Refused(403, "READ_ONLY_PERSONA", "the masked persona cannot start an evaluation");
        }
        if (policyId == null || policyId.isBlank()) {
            throw new Refused(400, "INVALID_POLICY", "policy_id is required");
        }
        Version version;
        if (versionId == null || versionId.isBlank()) {
            version = repository.active().orElseThrow(() -> new Refused(
                    409, "NO_ACTIVE_VERSION", "publish a zone matrix version before evaluating policies"));
        } else {
            version = repository.find(versionId.strip())
                    .orElseThrow(() -> new Refused(404, "NOT_FOUND", "no such version"));
            if (!published(version)) {
                throw new Refused(409, "DRAFT_NOT_EVALUATED", "drafts are not evaluated; select a published version");
            }
        }

        if (includeWitnesses) {
            if (!canViewBlocks) {
                throw new Refused(403, "ZONE_BLOCKS_NOT_PERMITTED",
                        "viewing witness addresses needs zone_matrix_zones_read");
            }
            record(actor, "WITNESS_VIEW", version.versionId());
        }

        PolicySnapshot snapshot = (policyCatalog == null ? Optional.<PolicySnapshot>empty() : policyCatalog.find(policyId.strip()))
                .orElseThrow(() -> new Refused(404, "POLICY_NOT_FOUND", "no such stored policy"));

        ZoneMatrixContent parsedContent = content(version);
        ZoneMatrixEvaluator.CompiledVersion compiled = ZoneMatrixEvaluator.compile(
                version.versionId(), version.number(), parsedContent);
        ZoneMatrixEvaluator.EvaluationResult result = ZoneMatrixEvaluator.evaluate(
                snapshot, compiled, clock.instant());

        LOG.info("zone_matrix_evaluated policy_id={} vendor={} version_id={} version_number={} content_sha256={} coverage={} evaluated={} violation={} compliant={} not_evaluable={} unknown={} not_applicable={} gaps={} duration_ms={}",
                result.policyId(),
                result.vendor(),
                version.versionId(),
                version.number(),
                compiled.citation().contentSha256(),
                result.coverage(),
                result.evaluated(),
                result.outcomeCounts().getOrDefault(ZoneMatrixEvaluator.OUTCOME_VIOLATION, 0),
                result.outcomeCounts().getOrDefault(ZoneMatrixEvaluator.OUTCOME_COMPLIANT, 0),
                result.outcomeCounts().getOrDefault(ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE, 0),
                result.outcomeCounts().getOrDefault(ZoneMatrixEvaluator.OUTCOME_UNKNOWN, 0),
                result.notApplicable().total(),
                result.zoneDefinitionGaps().size(),
                result.durationMillis());

        boolean showWitnesses = includeWitnesses && canViewBlocks && view != View.MASKED;
        return projectEvaluation(result, parsedContent, view, showWitnesses, hasPolicyRead);
    }

    /**
     * Projects an {@link ZoneMatrixEvaluator.EvaluationResult} according to {@link View}, witness permission and
     * {@code policy_read} (§3.4, §8.2, {@code ZM-10}, {@code ZM-33}).
     */
    public Map<String, Object> projectEvaluation(
            ZoneMatrixEvaluator.EvaluationResult result,
            ZoneMatrixContent content,
            View view,
            boolean showWitnesses,
            boolean hasPolicyRead) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(view, "view");

        boolean maskPolicy = view == View.MASKED || !hasPolicyRead;
        boolean allowAddresses = showWitnesses && view != View.MASKED;
        var zoneNames = new ZoneNames(view, content.zones().stream().map(ZoneMatrixContent.Zone::id).toList());
        UnaryOperator<String> endpoint = id -> ZoneMatrixContent.UNASSIGNED.equals(id)
                ? ZoneMatrixContent.UNASSIGNED
                : zoneNames.of(id, name(content, id));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine_version", result.engineVersion());
        out.put("version_id", result.version().versionId());
        out.put("version_number", result.version().versionNumber());
        out.put("content_sha256", result.version().contentSha256());
        out.put("policy_id", result.policyId());
        out.put("policy_name", maskPolicy ? maskPolicyLabel("policy", result.policyName()) : result.policyName());
        out.put("source_id", result.sourceId());
        out.put("source_name", maskPolicy ? maskSourceName(result.sourceName(), result.sourceId()) : result.sourceName());
        out.put("container_id", result.containerId());
        out.put("container_name", maskPolicy ? maskDomainName(result.containerName()) : result.containerName());
        out.put("vendor", result.vendor());
        out.put("collected_at", result.collectedAt());
        out.put("witnesses_included", allowAddresses);

        List<Map<String, Object>> targets = new ArrayList<>(result.targets().size());
        for (PolicySnapshot.Target t : result.targets()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("device_id", t.deviceId());
            row.put("name", maskPolicy ? maskSourceName(t.name(), t.deviceId()) : t.name());
            row.put("context", maskPolicy && t.context() != null && !t.context().isBlank()
                    ? nameMasker.maskVirtualSystem(t.context(), null)
                    : t.context());
            row.put("sync_status", t.syncStatus());
            targets.add(row);
        }
        out.put("targets", targets);

        out.put("evaluated", result.evaluated());
        out.put("not_evaluated_reason", result.notEvaluatedReason());
        out.put("coverage", result.coverage());
        out.put("coverage_items", result.coverageItems().stream()
                .map(c -> Map.<String, Object>of("code", c.code(), "description", c.description()))
                .toList());
        out.put("failure_count", result.failureCount());
        out.put("outcome_counts", new LinkedHashMap<>(result.outcomeCounts()));

        var na = result.notApplicable();
        Map<String, Object> naMap = new LinkedHashMap<>();
        naMap.put("disabled", na.disabled());
        naMap.put("deny_type", na.denyType());
        naMap.put("outside_security_rulebase", na.outsideSecurityRulebase());
        naMap.put("apply_layer_decided_by_inline", na.applyLayerDecidedByInline());
        naMap.put("schedule_ended", na.scheduleEnded());
        naMap.put("total", na.total());
        naMap.put("ended_schedules", na.endedSchedules().stream().map(es -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rule_id", es.ruleId());
            row.put("rule_number", es.ruleNumber());
            row.put("rule_name", maskPolicy ? maskRuleName(es.ruleName(), es.ruleNumber()) : es.ruleName());
            row.put("detail", es.detail());
            return row;
        }).toList());
        out.put("not_applicable", naMap);

        var h = result.headline();
        Map<String, Object> headlineMap = new LinkedHashMap<>();
        headlineMap.put("distinct_violating_rules", h.distinctViolatingRules());
        headlineMap.put("violated_zone_pairs", h.violatedZonePairs());
        headlineMap.put("critical_rules", h.criticalRules());
        headlineMap.put("covered_by_earlier_rule", h.coveredByEarlierRule());
        headlineMap.put("conditional_rules", h.conditionalRules());
        headlineMap.put("outside_headline_ordered_layer", h.outsideHeadlineOrderedLayer());
        headlineMap.put("not_evaluable_rules", h.notEvaluableRules());
        headlineMap.put("unknown_rules", h.unknownRules());
        headlineMap.put("incomplete_policies", h.incompletePolicies());
        headlineMap.put("not_evaluated_policies", h.notEvaluatedPolicies());
        headlineMap.put("exact", h.exact());
        headlineMap.put("text", h.text());
        headlineMap.put("outside_headline_text", h.outsideHeadlineText());
        out.put("headline", headlineMap);

        List<Map<String, Object>> rules = new ArrayList<>(result.rules().size());
        for (ZoneMatrixEvaluator.RuleEvaluation r : result.rules()) {
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, Object> idMap = new LinkedHashMap<>();
            idMap.put("policy_id", r.identity().policyId());
            idMap.put("rule_uid", r.identity().ruleUid());
            idMap.put("inline_parent_path", r.identity().inlineParentPath());
            idMap.put("violated_pairs", r.identity().violatedPairs());
            row.put("finding_identity", idMap);
            row.put("rule_id", r.ruleId());
            row.put("rule_uid", r.ruleUid());
            row.put("rule_number", r.ruleNumber());
            row.put("rule_name", maskPolicy ? maskRuleName(r.ruleName(), r.ruleNumber()) : r.ruleName());
            row.put("section_id", r.sectionId());
            row.put("section_name", maskPolicy ? maskSectionName(r.sectionName()) : r.sectionName());
            row.put("inline_parent_path", r.inlineParentPath());
            row.put("target_device_ids", r.targetDeviceIds());
            row.put("outcome", r.outcome());
            row.put("reason", r.reason());
            row.put("highest_severity", r.highestSeverity());
            row.put("flags", List.copyOf(r.flags()));
            row.put("covering_rule_id", r.coveringRuleId());
            row.put("touched_pairs", r.touchedPairs());
            row.put("touched_pair_names", r.touchedPairs().stream().map(p -> formatPairName(p, endpoint)).toList());

            List<Map<String, Object>> violatedCells = new ArrayList<>(r.violatedCells().size());
            for (ZoneMatrixEvaluator.CellViolation cv : r.violatedCells()) {
                Map<String, Object> cellRow = new LinkedHashMap<>();
                cellRow.put("source", cv.sourceZone());
                cellRow.put("destination", cv.destinationZone());
                cellRow.put("source_name", endpoint.apply(cv.sourceZone()));
                cellRow.put("destination_name", endpoint.apply(cv.destinationZone()));
                cellRow.put("statement", cv.statement());
                cellRow.put("severity", cv.severity());
                cellRow.put("not_allowed_services", cv.notAllowedServices());
                cellRow.put("nat_view", cv.natView());
                if (allowAddresses && cv.witness() != null) {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("source_range", cv.witness().sourceRange());
                    w.put("destination_range", cv.witness().destinationRange());
                    w.put("service", cv.witness().service());
                    cellRow.put("witness", w);
                } else {
                    cellRow.put("witness", null);
                }
                violatedCells.add(cellRow);
            }
            row.put("violated_cells", violatedCells);
            row.put("undecided_parts", r.undecidedParts());
            row.put("nat_view", r.natViewSummary());
            rules.add(row);
        }
        out.put("rules", rules);

        List<Map<String, Object>> gaps = new ArrayList<>(result.zoneDefinitionGaps().size());
        for (ZoneMatrixEvaluator.ZoneDefinitionGap g : result.zoneDefinitionGaps()) {
            Map<String, Object> gapRow = new LinkedHashMap<>();
            gapRow.put("rule_id", g.ruleId());
            gapRow.put("rule_number", g.ruleNumber());
            gapRow.put("rule_name", maskPolicy ? maskRuleName(g.ruleName(), g.ruleNumber()) : g.ruleName());
            gapRow.put("section_name", maskPolicy ? maskSectionName(g.sectionName()) : g.sectionName());
            gapRow.put("range_count", g.unassignedPrivateRanges().size());
            gapRow.put("unassigned_private_ranges", allowAddresses ? g.unassignedPrivateRanges() : null);
            gaps.add(gapRow);
        }
        out.put("zone_definition_gaps", gaps);
        out.put("hygiene_budget_reached", result.hygieneBudgetReached());
        out.put("duration_ms", result.durationMillis());
        out.put("hygiene_ms", result.hygieneMillis());
        return out;
    }

    private String maskRuleName(String rawName, int ruleNumber) {
        if (rawName == null || rawName.isEmpty()) return rawName;
        if ("UNKNOWN".equals(rawName)) return "UNKNOWN";
        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) PolicyPrivacy.mask(
                Map.of("name", rawName, "number", ruleNumber), "", nameMasker, ipMasker);
        return String.valueOf(masked.get("name"));
    }

    private String maskSectionName(String rawSection) {
        if (rawSection == null || rawSection.isEmpty() || FIXED_SECTION_NAMES.contains(rawSection)) {
            return rawSection;
        }
        return nameMasker.maskPolicyName("section", rawSection);
    }

    private String maskPolicyLabel(String namespace, String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        return nameMasker.maskPolicyName(namespace, raw);
    }

    private String maskDomainName(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        return nameMasker.maskDomainName(raw);
    }

    private String maskSourceName(String rawSource, String sourceId) {
        if (rawSource != null && !rawSource.isBlank()) {
            String masked = nameMasker.maskDeviceName(rawSource, null, sourceId);
            if (masked != null && !TopologyNamePseudonymizer.isGap(masked)) {
                return masked;
            }
        }
        return sourceId != null && !sourceId.isBlank() ? nameMasker.maskUnknownHost(sourceId) : TopologyNamePseudonymizer.GAP;
    }

    private static String formatPairName(String pair, UnaryOperator<String> endpoint) {
        int arrow = pair.indexOf("->");
        if (arrow < 0) return endpoint.apply(pair);
        return endpoint.apply(pair.substring(0, arrow)) + " -> " + endpoint.apply(pair.substring(arrow + 2));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String strOrEmpty(Object o) { return o == null ? "" : String.valueOf(o); }

    private static final class ActorTokens {
        private final View view;
        private final Map<String, String> tokens = new HashMap<>();
        ActorTokens(View view) { this.view = view; }
        String token(String actor) {
            if (view != View.MASKED) return actor;
            return tokens.computeIfAbsent(actor, a -> "USER-" + (tokens.size() + 1));
        }
    }
}
