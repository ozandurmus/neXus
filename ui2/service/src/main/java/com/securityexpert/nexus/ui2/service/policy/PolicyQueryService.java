package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.PolicySnapshotRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public final class PolicyQueryService {
    private final PolicySnapshotRepository repository;
    private final ObjectMapper mapper;
    private final TopologyNamePseudonymizer names;
    public PolicyQueryService(TransactionBoundary transactions, ObjectMapper mapper, TopologyNamePseudonymizer names) {
        this.repository = new PolicySnapshotRepository(transactions);
        this.mapper = mapper;
        this.names = names;
    }
    public List<Metadata> catalog() {
        return repository.catalog().stream().map(json -> read(json, Metadata.class)).toList();
    }
    public Optional<PolicySnapshot> find(String id) { return repository.find(id).map(json -> read(json, PolicySnapshot.class)); }
    private <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (Exception e) { throw new IllegalStateException("Stored policy is invalid"); }
    }
    public Map<String, Object> metadata(Metadata metadata) { return map(metadata); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return mapper.convertValue(value, Map.class); }

    public PolicyResponse page(PolicySnapshot snapshot, int page, String query, boolean masked) {
        String term = query.toLowerCase(Locale.ROOT);
        List<Rule> matching = snapshot.sections().stream().flatMap(s -> s.rules().stream()).filter(r -> {
            String name = masked ? names.maskPolicyName("name", r.name()) : r.name();
            String comment = masked ? "" : r.comment();
            return (name + " " + comment).toLowerCase(Locale.ROOT).contains(term);
        }).toList();
        Set<String> ids = new HashSet<>();
        matching.stream().skip((long) page * 200).limit(200).forEach(r -> ids.add(r.id()));
        List<Map<String, Object>> sections = new ArrayList<>();
        Set<String> objectIds = new LinkedHashSet<>();
        for (Section section : snapshot.sections()) {
            List<Rule> rules = section.rules().stream().filter(r -> ids.contains(r.id())).toList();
            for (Rule rule : rules) for (Cell cell : List.of(rule.source(), rule.destination(), rule.service(), rule.application()))
                objectIds.addAll(cell.refs());
            Map<String, Object> view = map(new Section(section.id(), section.name(), section.source(), section.parentRuleId(), rules));
            view.put("total", section.rules().size());
            sections.add(view);
        }
        List<Map<String, Object>> objects = objectIds.stream().filter(snapshot.objects()::containsKey)
                .map(id -> { var object = snapshot.objects().get(id);
                    return map(new PolicyObject(object.id(), object.name(), object.type(), List.of(), List.of(), object.status())); }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metadata", metadata(snapshot.metadata())); body.put("sections", sections); body.put("objects", objects);
        body.put("page", page); body.put("pageSize", 200); body.put("total", matching.size());
        return new PolicyResponse(body);
    }
    public PolicyResponse object(PolicySnapshot snapshot, String id) {
        return new PolicyResponse(Map.of("object", expand(snapshot, id, new HashSet<>(), new int[]{500}, 0)));
    }
    private Map<String, Object> expand(PolicySnapshot snapshot, String id, Set<String> path, int[] budget, int depth) {
        PolicyObject object = snapshot.objects().get(id);
        if (object == null) return Map.of("id", id, "status", "UNRESOLVED");
        Map<String, Object> out = map(object);
        if (object.members().size() > 500) {
            out.put("members", object.members().subList(0, 500));
            out.put("totalMembers", object.members().size());
        }
        if (path.contains(id)) { out.put("status", "CYCLE"); return out; }
        if (depth >= 32 || budget[0]-- <= 0) { out.put("status", "LIMIT"); return out; }
        path.add(id);
        List<Map<String, Object>> children = new ArrayList<>();
        for (String member : object.members()) {
            if (budget[0] <= 0) { out.put("status", "LIMIT"); break; }
            children.add(expand(snapshot, member, path, budget, depth + 1));
        }
        path.remove(id);
        out.put("children", children);
        return out;
    }
}
