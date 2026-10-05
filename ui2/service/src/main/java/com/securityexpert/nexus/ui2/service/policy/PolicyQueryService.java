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
    private final LocalFirewallPolicyService local;
    private final com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker ips;
    public PolicyQueryService(TransactionBoundary transactions, ObjectMapper mapper, TopologyNamePseudonymizer names) {
        this(transactions, mapper, names, null, new com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker(new byte[32]));
    }
    public PolicyQueryService(TransactionBoundary transactions, ObjectMapper mapper, TopologyNamePseudonymizer names,
            LocalFirewallPolicyService local) {
        this(transactions, mapper, names, local, new com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker(new byte[32]));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PolicyQueryService(TransactionBoundary transactions, ObjectMapper mapper, TopologyNamePseudonymizer names,
            LocalFirewallPolicyService local, com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker ips) {
        this.ips = ips;
        this.local = local;
        this.repository = new PolicySnapshotRepository(transactions);
        this.mapper = mapper;
        this.names = names;
    }
    /** Independent type gaps remain visible even when there are no policy packages. */
    public Optional<PolicyResponse> domainInventory(String source, String domain, String view, int page) {
        var inventories = repository.inventories(source, domain).stream()
            .map(json -> read(json, com.securityexpert.nexus.ui2.policy.CpObjectInventory.class)).toList();
        var selected = inventories.stream().filter(i -> switch (view) {
            case "unused" -> i.type().equals("unused-objects");
            case "gateways" -> i.type().equals("gateways-and-servers");
            default -> !i.type().equals("unused-objects") && !i.type().equals("gateways-and-servers");
        }).toList();
        if (selected.isEmpty()) return Optional.empty();
        var items = selected.stream().flatMap(i -> i.objects().stream()).toList();
        var states = selected.stream().map(i -> Map.of("type", i.type(), "status", i.status(), "reason", i.reason(),
            "collectedAt", i.collectedAt(), "pages", i.pages(), "objects", i.objects().size(), "seconds", i.seconds())).toList();
        return Optional.of(new PolicyResponse(Map.of("sourceId", source, "containerId", domain, "types", states,
            "objects", items.stream().skip((long) page * 200).limit(200).map(this::map).toList(), "total", items.size())));
    }

    public Optional<PolicyResponse> domainHits(String source, String domain, int page) {
        var snapshots = repository.domainSnapshots(source, domain).stream().map(json -> read(json, PolicySnapshot.class)).toList();
        if (snapshots.isEmpty()) return Optional.empty();
        List<Map<String, Object>> hits = new ArrayList<>();
        for (var snapshot : snapshots) for (var section : snapshot.sections()) for (var rule : section.rules()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rule.id()); row.put("ruleId", rule.id()); row.put("policyId", snapshot.metadata().id());
            row.put("hitCounts", rule.hitCounts() == null ? null : map(rule.hitCounts())); hits.add(row);
        }
        return Optional.of(new PolicyResponse(Map.of("sourceId", source, "containerId", domain,
            "hits", hits.stream().skip((long) page * 200).limit(200).toList(), "total", hits.size())));
    }

    public List<Metadata> catalog() {
        var catalog = new ArrayList<>(repository.catalog().stream().map(json -> read(json, Metadata.class)).toList());
        if (local != null) local.snapshots().forEach(snapshot -> catalog.add(snapshot.metadata()));
        return catalog;
    }
    public List<Map<String, Object>> catalogViews() {
        var views = new ArrayList<>(repository.catalogEntries().stream().map(entry -> {
            var view = metadata(read(entry.metadataJson(), Metadata.class));
            view.put("ruleCount", entry.ruleCount());
            return view;
        }).toList());
        if (local != null) local.snapshots().forEach(snapshot -> {
            var view = metadata(snapshot.metadata());
            view.put("ruleCount", snapshot.sections().stream().mapToInt(s -> s.rules().size()).sum());
            views.add(view);
        });
        return views;
    }
    public PolicyResponse tree(String source, String container, String device) {
        var selected = catalog().stream().filter(p -> device.isEmpty() || p.targets().stream().anyMatch(t -> t.deviceId().equals(device)))
            .filter(p -> source.isEmpty() || p.sourceId().equals(source)).toList();
        if (!container.isEmpty()) return new PolicyResponse(Map.of("policies", selected.stream().filter(p -> p.containerId().equals(container)).map(this::metadata).toList()));
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (var p : selected) {
            String id = source.isEmpty() ? p.sourceId() : p.containerId();
            nodes.putIfAbsent(id, source.isEmpty()
                ? Map.of("sourceId", id, "sourceName", p.sourceName(), "vendor", p.vendor())
                : Map.of("containerId", id, "containerName", p.containerName()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(source.isEmpty() ? "sources" : "containers", List.copyOf(nodes.values()));
        if (source.isEmpty()) {
            Map<String, Target> devices = new LinkedHashMap<>();
            selected.forEach(p -> p.targets().forEach(target -> devices.putIfAbsent(target.deviceId(), target)));
            body.put("devices", devices.values().stream().map(this::map).toList());
        }
        return new PolicyResponse(body);
    }

    public Optional<PolicySnapshot> find(String id) {
        var stored = repository.find(id).map(json -> read(json, PolicySnapshot.class));
        return stored.isPresent() || local == null ? stored : local.snapshots().stream().filter(s -> s.metadata().id().equals(id)).findFirst();
    }
    private <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (Exception e) { throw new IllegalStateException("Stored policy is invalid"); }
    }
    public Map<String, Object> metadata(Metadata metadata) { return map(metadata); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return mapper.convertValue(value, Map.class); }

    public PolicyResponse page(PolicySnapshot snapshot, int page, String query, boolean masked) {
        PolicySnapshot searchable = snapshot;
        if (masked) searchable = mapper.convertValue(PolicyPrivacy.mask(map(snapshot), "", names,
                ips), PolicySnapshot.class);
        var predicate = PolicyRuleQuery.compile(query, searchable.objects(), java.time.Instant.now(), snapshot.metadata().vendor());
        Set<String> matches = searchable.sections().stream().flatMap(s -> s.rules().stream()).filter(predicate)
                .map(Rule::id).collect(java.util.stream.Collectors.toSet());
        List<Rule> matching = snapshot.sections().stream().flatMap(s -> s.rules().stream()).filter(r -> matches.contains(r.id())).toList();
        Set<String> ids = new HashSet<>();
        matching.stream().skip((long) page * 200).limit(200).forEach(r -> ids.add(r.id()));
        List<Map<String, Object>> sections = new ArrayList<>();
        Set<String> objectIds = new LinkedHashSet<>();
        for (Section section : snapshot.sections()) {
            List<Rule> rules = section.rules().stream().filter(r -> ids.contains(r.id())).toList();
            for (Rule rule : rules) for (Cell cell : List.of(rule.source(), rule.destination(), rule.service(), rule.application()))
                objectIds.addAll(cell.refs());
            for (Rule rule : rules) for (String key : List.of("time", "schedule", "install-on", "vpn", "content"))
                objectIds.addAll(rule.extras().getOrDefault(key, List.of()));
            Map<String, Object> view = map(new Section(section.id(), section.name(), section.source(), section.parentRuleId(), rules));
            view.put("rules", rules.stream().map(r -> {
                var rule = map(r); rule.put("identityFallback", r.uuid().isEmpty());
                var time = PolicyRuleMetrics.time(r, snapshot.objects(), java.time.Instant.now(), snapshot.metadata().vendor());
                rule.put("timeStatus", time.status()); rule.put("schedules", time.schedules().stream().map(this::map).toList()); rule.put("expiring", time.expiring());
                rule.put("permissiveness", PolicyRuleMetrics.permissiveness(r, snapshot.objects()));
                return rule;
            }).toList());
            view.put("total", section.rules().size());
            sections.add(view);
        }
        List<Map<String, Object>> objects = objectIds.stream().filter(snapshot.objects()::containsKey)
                .map(id -> { var object = snapshot.objects().get(id);
                    return map(new PolicyObject(object.id(), object.name(), object.type(), List.of(), List.of(), object.status(), object.schedule())); }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metadata", metadata(snapshot.metadata()));
        body.put("policyKind", snapshot.sections().stream().anyMatch(s -> s.name().equals("Local rules")) ? "LOCAL_FIREWALL" : "MANAGEMENT");
        body.put("failures", snapshot.failures().stream().map(this::map).toList());
        body.put("sections", sections); body.put("objects", objects);
        body.put("page", page); body.put("pageSize", 200); body.put("total", matching.size());
        return new PolicyResponse(body);
    }
    public PolicyResponse history(String policy, String rule, int page) {
        return new PolicyResponse(Map.of("revisions", repository.history(policy, rule, page).stream()
                .map(json -> read(json, Map.class)).toList(), "page", page));
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
