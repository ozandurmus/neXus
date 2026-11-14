package com.securityexpert.nexus.ui2.service.zonematrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;

/**
 * Zone matrix delivery step 1 (ZONE_MATRIX_CONTRACT.md section 10.1 step 1, FROZEN 2026-11-13): the zone model, the
 * draft and its revisions, four-eyes publishing, the version views and the audited address lookup. No statement, cell
 * or evaluation exists yet (steps 2 to 4).
 */
public final class ZoneMatrixService {
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

    private final ZoneMatrixRepository repository;
    private final Principals principals;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ZoneMatrixService(ZoneMatrixRepository repository, Principals principals, ObjectMapper mapper, Clock clock) {
        this.repository = repository; this.principals = principals; this.mapper = mapper; this.clock = clock;
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
        ZoneMatrixContent before = active.map(this::content).orElse(ZoneMatrixContent.empty());
        out.put("diff", diff(before, content(draft.get()), view));
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
            return new ZoneMatrixContent(zones);
        });
    }

    public Map<String, Object> updateZone(String actor, Long expectedRevision, String zoneId, ZoneInput input) {
        return edit(actor, expectedRevision, "ZONE_UPDATE", zoneId, content -> {
            if (content.zone(zoneId).isEmpty()) throw new Refused(404, "ZONE_NOT_FOUND", "no such zone in the draft");
            return new ZoneMatrixContent(content.zones().stream().map(z -> z.id().equals(zoneId) ? zone(zoneId, input) : z).toList());
        });
    }

    public Map<String, Object> deleteZone(String actor, Long expectedRevision, String zoneId) {
        return edit(actor, expectedRevision, "ZONE_DELETE", zoneId, content -> {
            if (content.zone(zoneId).isEmpty()) throw new Refused(404, "ZONE_NOT_FOUND", "no such zone in the draft");
            return new ZoneMatrixContent(content.zones().stream().filter(z -> !z.id().equals(zoneId)).toList());
        });
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

    private Map<String, Object> edit(String actor, Long expectedRevision, String kind, String zoneId, UnaryOperator<ZoneMatrixContent> change) {
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
        return repository.locked(tx -> {
            Version draft = tx.draft().orElseThrow(() -> new Refused(404, "NO_DRAFT", "there is no draft"));
            if (draft.revision() != request.revision() || !draft.contentSha256().equals(request.content_sha256())) throw changed();
            Set<String> participants = tx.participants(draft.versionId());
            String refusal = fourEyes(actor, participants);
            if (refusal != null) throw refusal(refusal);
            Optional<Version> active = tx.active();
            ZoneMatrixContent next = content(draft);
            ZonePartition after = validate(next);
            if (active.isPresent() && active.get().contentSha256().equals(draft.contentSha256()))
                throw new Refused(409, "NOTHING_TO_PUBLISH", "the draft equals the active version");
            ZonePartition before = active.map(v -> content(v).partition()).orElse(ZoneMatrixContent.empty().partition());
            boolean leaving = ZonePartition.moves(before, after).stream().anyMatch(m -> m.fromZoneId() != null);
            if (leaving && rationale == null)
                throw new Refused(409, "RATIONALE_REQUIRED", "addresses leave a zone in this version; give a rationale");
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
                : active.isPresent() && active.get().contentSha256().equals(draft.contentSha256()) ? "NOTHING_TO_PUBLISH"
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

    private Map<String, Object> diff(ZoneMatrixContent before, ZoneMatrixContent after, View view) {
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zones", zones);
        out.put("moves", moves);
        out.put("rationale_required", moves.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("leaves_a_zone"))));
        out.put("unassigned_before", sizeView(sizesBefore.get(null)));
        out.put("unassigned_after", sizeView(sizesAfter.get(null)));
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
