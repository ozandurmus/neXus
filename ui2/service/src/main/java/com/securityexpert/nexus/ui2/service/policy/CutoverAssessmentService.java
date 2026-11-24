package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.CutoverRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Segments and pre-cutover impact assessments (ACI segmentation design section 4, step 1). Resolves the target's policy
 * from what neXus already stores; makes no device or API contact.
 */
@Service
public class CutoverAssessmentService {
    /** Vendor hints of enrolled devices whose policy neXus evaluates through {@link PolicyLookup}. */
    static final Map<String, String> LOOKUP_VENDOR_HINTS = Map.of("check_point", "CP", "palo_alto", "PAN",
        "cisco_asa", "ASA", "fortinet", "FGT");
    private final CutoverRepository repository;
    private final PolicyQueryService policies;
    private final ObjectMapper json;

    @Autowired
    public CutoverAssessmentService(TransactionBoundary transactions, PolicyQueryService policies, ObjectMapper json) {
        this(new CutoverRepository(transactions), policies, json);
    }
    public CutoverAssessmentService(CutoverRepository repository, PolicyQueryService policies, ObjectMapper json) {
        this.repository = repository; this.policies = policies; this.json = json;
    }

    // ---- segments -------------------------------------------------------------------------------------------

    public record SegmentInput(String name, List<String> blocks, List<String> peerBlocks) {
        public SegmentInput {
            if (name == null || name.isBlank() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("SEGMENT_NAME: 1 to 64 characters, no control characters");
            var scope = new CutoverAssessment.Scope(blocks, peerBlocks);
            blocks = scope.segment(); peerBlocks = scope.peers();
        }
    }

    public String createSegment(SegmentInput input, String actor) {
        String id = UUID.randomUUID().toString();
        if (!repository.createSegment(id, input.name(), write(input.blocks()), write(input.peerBlocks()), actor))
            throw new IllegalArgumentException("SEGMENT_NAME_TAKEN: another segment has this name");
        return id;
    }
    public CutoverRepository.UpdateOutcome updateSegment(String id, int revision, SegmentInput input, String actor) {
        return repository.updateSegment(id, revision, input.name(), write(input.blocks()), write(input.peerBlocks()), actor);
    }
    public boolean deleteSegment(String id, String actor) { return repository.deleteSegment(id, actor); }
    public List<Map<String, Object>> segments() { return repository.listSegments().stream().map(this::read).toList(); }
    public Optional<Map<String, Object>> segment(String id) { return repository.findSegment(id).map(this::read); }

    // ---- assessments -----------------------------------------------------------------------------------------

    /**
     * An operator's explicit choice of the target firewall, used instead of path computation and labelled
     * {@code OPERATOR_OVERRIDE} on every result (PO 2026-11-14: the target comes from routes and, later, contracts).
     */
    public record TargetOverride(String deviceId, String policyId, String context, String segmentZone, String peerZone) {
        public TargetOverride {
            deviceId = Spec.id(deviceId, "targetOverride.deviceId", true);
            policyId = Spec.id(policyId, "targetOverride.policyId", false);
            context = Spec.text(context); segmentZone = Spec.text(segmentZone); peerZone = Spec.text(peerZone);
        }
    }

    /** The assessment request; without {@code targetOverride} every flow's target comes from its computed path. */
    public record Spec(TargetOverride targetOverride, String oldPolicyId, String oldDeviceId, String oldContext,
            List<Map<String, String>> mappings) {
        public Spec {
            oldPolicyId = id(oldPolicyId, "oldPolicyId", false); oldDeviceId = id(oldDeviceId, "oldDeviceId", false);
            oldContext = text(oldContext);
            if (mappings == null || mappings.isEmpty() || mappings.size() > CutoverAssessment.MAX_UPLOADS)
                throw new IllegalArgumentException("CSV_MAPPING: one mapping per uploaded file, 1 to 8 files");
            mappings = mappings.stream().map(m -> {
                if (m == null) throw new IllegalArgumentException("CSV_MAPPING: one mapping per uploaded file, 1 to 8 files");
                return Map.copyOf(m);
            }).toList();
        }
        static String id(String value, String field, boolean required) {
            if (value == null || value.isEmpty()) {
                if (required) throw new IllegalArgumentException("SPEC_INVALID: " + field + " is required");
                return null;
            }
            if (value.length() > 200 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("SPEC_INVALID: " + field + " is not an identifier");
            return value;
        }
        static String text(String value) {
            if (value == null) return "";
            if (value.length() > 200 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("SPEC_INVALID: zone or context too long");
            return value;
        }
    }

    /** A prepared assessment: the engine run plus the provenance that is stored with its result. */
    public record Prepared(String segmentId, int segmentRevision, CutoverAssessment.Run run, Map<String, Object> provenance,
            String targetSource, String targetDeviceId, String topologySnapshotId,
            String targetPolicyId, String targetCollectedAt, String oldPolicyId, String oldCollectedAt) {}

    /**
     * @param routed the routed-path policy over the topology held now (empty when none is held); used unless the spec
     *               names an operator override
     */
    public Prepared prepare(String segmentId, Spec spec, Optional<? extends CutoverAssessment.FlowPolicy> routed, String topologySnapshotId) {
        Map<String, Object> segment = segment(segmentId).orElseThrow(() -> new NoSuchElementException("SEGMENT_NOT_FOUND"));
        @SuppressWarnings("unchecked") var scope = new CutoverAssessment.Scope((List<String>) segment.get("blocks"), (List<String>) segment.get("peerBlocks"));
        var provenance = new LinkedHashMap<String, Object>();
        CutoverAssessment.Target target; String policyId = null, collectedAt = null, deviceId = null, topologyId = null, source;
        TargetOverride override = spec.targetOverride();
        if (override == null) {
            var path = routed.orElseThrow(() -> new IllegalStateException("TOPOLOGY_NOT_BUILT"));
            target = new CutoverAssessment.Target(path, "", "", false, false);
            source = CutoverAssessment.SOURCE_PATH; topologyId = topologySnapshotId;
            provenance.put("topologySnapshotId", topologySnapshotId);
        } else {
            source = CutoverAssessment.SOURCE_OVERRIDE; deviceId = override.deviceId();
            String hint = repository.deviceVendor(override.deviceId()).orElseThrow(() -> new NoSuchElementException("DEVICE_NOT_FOUND"));
            provenance.put("targetDeviceId", override.deviceId()); provenance.put("segmentZone", override.segmentZone()); provenance.put("peerZone", override.peerZone());
            if (!LOOKUP_VENDOR_HINTS.containsKey(hint)) {
                // Enrolled, but neXus holds no evaluable policy model for this vendor (Check Point, Palo Alto, Cisco ASA and FortiGate are evaluated).
                if (override.policyId() != null) throw new IllegalArgumentException("POLICY_NOT_FOR_TARGET: no stored policy is evaluable for this device's vendor");
                target = new CutoverAssessment.Target(CutoverAssessment.notEvaluable(CutoverAssessment.VENDOR_POLICY_NOT_COLLECTED),
                    override.segmentZone(), override.peerZone(), false, false);
                provenance.put("targetVendor", "");
            } else {
                if (override.policyId() == null) throw new IllegalArgumentException("POLICY_REQUIRED: choose the stored policy installed on the target");
                PolicySnapshot snapshot = policies.find(override.policyId()).orElseThrow(() -> new NoSuchElementException("POLICY_NOT_FOUND"));
                if (!snapshot.metadata().vendor().equals(LOOKUP_VENDOR_HINTS.get(hint)) || snapshot.metadata().targets().stream()
                        .noneMatch(t -> t.deviceId().equals(override.deviceId()) && (override.context().isEmpty() || t.context().equals(override.context()))))
                    throw new IllegalArgumentException("POLICY_NOT_FOR_TARGET: the policy does not name this device and context as a target");
                target = new CutoverAssessment.Target(CutoverAssessment.stored(snapshot, override.deviceId(), override.context()),
                    override.segmentZone(), override.peerZone(), !snapshot.failures().isEmpty(), CutoverAssessment.natPresent(snapshot));
                policyId = snapshot.metadata().id(); collectedAt = snapshot.metadata().collectedAt();
                provenance.put("targetVendor", snapshot.metadata().vendor());
            }
        }
        CutoverAssessment.FlowPolicy old = null; String oldId = null, oldCollected = null;
        if (spec.oldPolicyId() != null) {
            PolicySnapshot snapshot = policies.find(spec.oldPolicyId()).orElseThrow(() -> new NoSuchElementException("POLICY_NOT_FOUND"));
            old = CutoverAssessment.stored(snapshot, spec.oldDeviceId(), spec.oldContext());
            oldId = snapshot.metadata().id(); oldCollected = snapshot.metadata().collectedAt();
            if (spec.oldDeviceId() != null) provenance.put("oldDeviceId", spec.oldDeviceId());
        }
        int revision = ((Number) segment.get("revision")).intValue();
        return new Prepared(segmentId, revision, CutoverAssessment.start(scope, target, old), provenance, source, deviceId, topologyId,
            policyId, collectedAt, oldId, oldCollected);
    }

    public String save(Prepared prepared, CutoverAssessment.Result result, String actor) {
        String id = UUID.randomUUID().toString();
        var summary = new LinkedHashMap<>(result.summary()); summary.putAll(prepared.provenance());
        List<String> rows = new ArrayList<>(result.flows().size() + result.missingRules().size());
        for (int i = 0; i < result.flows().size(); i++) {
            var flow = result.flows().get(i);
            rows.add(write(Map.of("kind", "FLOW", "ordinal", i, "decision", flow.get("decision"), "flow", flow)));
        }
        for (int i = 0; i < result.missingRules().size(); i++)
            rows.add(write(Map.of("kind", "MISSING_RULE", "ordinal", i, "decision", "MISSING_RULE", "flow", result.missingRules().get(i))));
        repository.saveAssessment(new CutoverRepository.Assessment(id, prepared.segmentId(), prepared.segmentRevision(), actor,
            prepared.targetSource(), prepared.targetDeviceId(), prepared.topologySnapshotId(), prepared.targetPolicyId(), prepared.targetCollectedAt(), prepared.oldPolicyId(),
            prepared.oldCollectedAt(), CutoverAssessment.ENGINE_VERSION, result.status(), result.readiness(), write(summary), rows));
        return id;
    }

    public List<Map<String, Object>> assessments(String segmentId) { return repository.listAssessments(segmentId).stream().map(this::read).toList(); }

    /**
     * The stored assessment with its staleness (ACI-8): the segment was edited, a cited policy has a newer snapshot, or
     * (routed-path targets) the topology it was computed on is no longer the one held.
     */
    public Optional<Map<String, Object>> assessment(String id, Optional<String> currentTopology) {
        return repository.findAssessment(id).map(this::read).map(found -> {
            var out = new LinkedHashMap<>(found);
            List<String> reasons = new ArrayList<>();
            var segment = segment((String) found.get("segmentId"));
            if (segment.isEmpty() || !Objects.equals(((Number) segment.get().get("revision")).intValue(), ((Number) found.get("segmentRevision")).intValue()))
                reasons.add("SEGMENT_CHANGED");
            policyChanged((String) found.get("targetPolicyId"), (String) found.get("targetCollectedAt"), "TARGET_POLICY_CHANGED", reasons);
            policyChanged((String) found.get("oldPolicyId"), (String) found.get("oldCollectedAt"), "OLD_POLICY_CHANGED", reasons);
            if (found.get("topologySnapshotId") instanceof String topology && !currentTopology.map(topology::equals).orElse(false))
                reasons.add("TOPOLOGY_CHANGED");
            out.put("stale", !reasons.isEmpty()); out.put("staleReasons", List.copyOf(new LinkedHashSet<>(reasons)));
            return out;
        });
    }
    private void policyChanged(String policyId, String collectedAt, String reason, List<String> reasons) {
        if (policyId == null) return;
        var snapshot = policies.find(policyId);
        if (snapshot.isEmpty()) reasons.add("POLICY_NOT_FOUND");
        else if (!Objects.equals(snapshot.get().metadata().collectedAt(), collectedAt)) reasons.add(reason);
    }

    /** Existence only; never loads a policy snapshot (flow pages are read often). */
    public boolean assessmentExists(String id) { return repository.findAssessment(id).isPresent(); }

    public Map<String, Object> flows(String id, String kind, String decision, int page) {
        var out = new LinkedHashMap<String, Object>();
        out.put("assessmentId", id); out.put("kind", kind); out.put("page", page);
        out.put("total", repository.flowCount(id, kind, decision));
        out.put("items", repository.flows(id, kind, decision, page).stream().map(this::read).toList());
        return out;
    }
    public boolean deleteAssessment(String id, String actor) { return repository.deleteAssessment(id, actor); }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("CUTOVER_RESULT_INVALID"); }
    }
    private Map<String, Object> read(String value) {
        try { return json.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("CUTOVER_RESULT_INVALID"); }
    }
}
