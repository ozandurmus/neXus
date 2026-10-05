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

    private record CachedDomain(String revision, PolicyDomainIndex index) {}
    private final Map<String, CachedDomain> domainCache = new LinkedHashMap<>(16, 0.75f, true);

    // ponytail: one lock for the bounded 32-domain cache; split locks only if stored-read contention is measured.
    synchronized PolicyDomainIndex domainIndex(String source, String domain) {
        var inventories = repository.inventories(source, domain);
        var snapshots = repository.domainSnapshots(source, domain);
        String revision;
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (var rows : List.of(inventories, snapshots)) for (String json : rows) {
                digest.update(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte) 0);
            }
            revision = HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        String key = ref(source, domain);
        var cached = domainCache.get(key);
        if (cached != null && cached.revision().equals(revision)) return cached.index();
        var index = new PolicyDomainIndex(inventories.stream().map(json -> read(json, com.securityexpert.nexus.ui2.policy.CpObjectInventory.class)).toList(),
            snapshots.stream().map(json -> read(json, PolicySnapshot.class)).toList());
        domainCache.put(key, new CachedDomain(revision, index));
        if (domainCache.size() > 32) domainCache.remove(domainCache.keySet().iterator().next());
        return index;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> searchable(Map<String, Object> row, boolean masked) {
        return masked ? (Map<String, Object>) PolicyPrivacy.mask(row, "", names, ips) : row;
    }
    private boolean matches(Map<String, Object> row, String q, boolean masked) {
        var view = searchable(row, masked);
        return (view.getOrDefault("name", "") + " " + view.getOrDefault("policyName", "") + " " + view.getOrDefault("values", ""))
            .toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));
    }
    private List<Map<String, Object>> typeStates(PolicyDomainIndex index) {
        return index.inventories.stream().map(i -> Map.<String, Object>of("type", i.type(), "status", i.status(),
            "collectedAt", i.collectedAt(), "objects", i.objects().size())).toList();
    }
    public Optional<PolicyResponse> domainObjects(String source, String domain, int page, String q, String type, String hygiene, boolean masked) {
        var index = domainIndex(source, domain);
        if (index.inventories.isEmpty() && index.snapshots.isEmpty()) return Optional.empty();
        var items = index.objects.stream().filter(o -> type.isEmpty() || objectType(String.valueOf(o.get("type"))).equals(type))
            .filter(o -> switch (hygiene) {
                case "unused" -> Boolean.TRUE.equals(o.get("unused"));
                case "duplicates" -> o.get("duplicateId") != null;
                case "empty" -> Boolean.TRUE.equals(o.get("emptyGroup"));
                case "single" -> Boolean.TRUE.equals(o.get("singleMember"));
                default -> true;
            }).filter(o -> matches(o, q, masked)).sorted(Comparator.comparing(o -> hygiene.equals("duplicates")
                ? String.valueOf(o.get("duplicateId")) + o.get("id") : String.valueOf(searchable(o, masked).get("name")) + o.get("id"))).toList();
        return Optional.of(new PolicyResponse(Map.of("objects", items.stream().skip((long) page * 200).limit(200).toList(),
            "total", items.size(), "page", page, "pageSize", 200, "types", typeStates(index))));
    }
    private static String objectType(String type) {
        if (type.startsWith("service-") && !type.equals("service-group")) return "service";
        return switch (type) {
            case "address-range" -> "range"; case "group-with-exclusion", "address-group" -> "group";
            case "time-group" -> "time"; case "dynamic-object" -> "dynamic"; case "security-zone" -> "zone";
            default -> type;
        };
    }
    public Optional<PolicyResponse> objectUsage(String source, String domain, String uid, int page) {
        var index = domainIndex(source, domain);
        var object = index.objects.stream().filter(o -> o.get("uid").equals(uid)).findFirst();
        if (object.isEmpty()) return Optional.empty();
        var rules = index.rules.getOrDefault(uid, List.of()); var groups = index.groups.getOrDefault(uid, List.of());
        return Optional.of(new PolicyResponse(Map.of("object", object.get(), "rules", rules.stream().skip((long) page * 200).limit(200).toList(),
            "groups", groups.stream().skip((long) page * 200).limit(200).toList(), "ruleCount", rules.size(), "groupCount", groups.size(), "page", page, "pageSize", 200)));
    }
    public Optional<PolicyResponse> objectDuplicates(String source, String domain, int page, String q, boolean masked) {
        var index = domainIndex(source, domain);
        if (index.inventories.isEmpty()) return Optional.empty();
        var matchingIds = index.objects.stream().filter(o -> matches(o, q, masked)).map(o -> o.get("id")).collect(java.util.stream.Collectors.toSet());
        var groups = index.duplicates.stream().filter(group -> ((List<?>) group.get("objects")).stream()
            .anyMatch(o -> matchingIds.contains(((Map<?, ?>) o).get("id")))).toList();
        var boundedGroups = groups.stream().skip((long) page * 200).limit(200).map(group -> {
            var members = (List<?>) group.get("objects");
            return Map.of("id", group.get("id"), "objects", members.stream().limit(200).toList(), "total", members.size());
        }).toList();
        return Optional.of(new PolicyResponse(Map.of("duplicates", boundedGroups, "total", groups.size(), "page", page, "pageSize", 200)));
    }
    public Optional<PolicyResponse> installations(String source, String domain, int page, String q, String policy, boolean masked) {
        var index = domainIndex(source, domain);
        if (index.snapshots.isEmpty()) return Optional.empty();
        var links = repository.inventoryLinks(source, domain);
        var gateways = index.inventories.stream().filter(i -> i.type().equals("gateways-and-servers"))
            .flatMap(i -> i.objects().stream()).toList();
        var rows = new ArrayList<Map<String, Object>>();
        for (var snapshot : index.snapshots) {
            var metadata = snapshot.metadata();
            if (!policy.isEmpty() && !metadata.id().equals(policy)) continue;
            boolean all = metadata.targets().stream().anyMatch(t -> t.name().equals("ALL"));
            var seen = new HashSet<String>();
            for (var gateway : gateways) {
                var matchingLinks = links.stream().filter(l -> (!l.uid().isEmpty() && l.uid().equals(gateway.uid())) || (!l.name().isEmpty() && l.name().equals(gateway.name()))).toList();
                var target = metadata.targets().stream().filter(t -> (!t.name().isEmpty() && t.name().equals(gateway.name()))
                    || matchingLinks.stream().anyMatch(l -> l.deviceId().equals(t.deviceId()))).findFirst();
                var observed = gateway.policyInstallations().stream().filter(i -> i.policyName().equals(metadata.name())).toList();
                var installationStates = observed.stream().map(com.securityexpert.nexus.ui2.policy.CpObjectInventory.Installation::installed).distinct().toList();
                boolean allGateway = all && Set.of("simple-gateway", "simple-cluster", "cluster", "gateway", "vsx-cluster", "vsx-gateway", "vsx-cluster-member",
                    "CpmiGatewayCluster", "CpmiVsClusterNetobj", "CpmiVsxClusterNetobj", "CpmiVsxNetobj", "CpmiVsxClusterMember").contains(gateway.type());
                if (!allGateway && target.isEmpty() && observed.isEmpty()) continue;
                var uidMatches = matchingLinks.stream().filter(l -> l.uid().equals(gateway.uid())).toList();
                var ids = (uidMatches.isEmpty() ? matchingLinks : uidMatches).stream()
                    .map(PolicySnapshotRepository.InventoryLink::deviceId).distinct().toList();
                String deviceId = ids.size() == 1 ? ids.get(0) : "";
                var row = new LinkedHashMap<String, Object>();
                row.put("policyId", metadata.id()); row.put("policyName", metadata.name()); row.put("id", gateway.id());
                row.put("name", gateway.name()); row.put("type", gateway.type()); row.put("deviceId", deviceId);
                row.put("targeted", allGateway || target.isPresent()); row.put("allTargets", allGateway);
                row.put("installed", installationStates.size() == 1 ? installationStates.get(0) : null);
                rows.add(row); target.ifPresent(t -> seen.add(t.deviceId()));
            }
            for (var target : metadata.targets()) {
                if (seen.contains(target.deviceId()) && !target.name().equals("ALL")) continue;
                if (target.name().equals("ALL") && rows.stream().anyMatch(r -> r.get("policyId").equals(metadata.id()) && Boolean.TRUE.equals(r.get("allTargets")))) continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("policyId", metadata.id()); row.put("policyName", metadata.name()); row.put("id", target.deviceId());
                row.put("name", target.name().equals("ALL") ? "" : target.name()); row.put("deviceId", links.stream().anyMatch(l -> l.deviceId().equals(target.deviceId())) ? target.deviceId() : "");
                row.put("targeted", true); row.put("allTargets", target.name().equals("ALL")); row.put("installed", null);
                rows.add(row);
            }
            if (metadata.targets().isEmpty() && rows.stream().noneMatch(r -> r.get("policyId").equals(metadata.id()))) {
                var row = new LinkedHashMap<String, Object>();
                row.put("policyId", metadata.id()); row.put("policyName", metadata.name()); row.put("id", metadata.id());
                row.put("name", ""); row.put("deviceId", ""); row.put("targeted", null); row.put("allTargets", false); row.put("installed", null); rows.add(row);
            }
        }
        var matches = rows.stream().filter(r -> matches(r, q, masked)).toList();
        return Optional.of(new PolicyResponse(Map.of("installations", matches.stream().skip((long) page * 200).limit(200).toList(),
            "total", matches.size(), "page", page, "pageSize", 200, "types", typeStates(index))));
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
        return page(snapshot, page, query, masked, "", 90);
    }
    public PolicyResponse page(PolicySnapshot snapshot, int page, String query, boolean masked, String hitFilter, int days) {
        PolicySnapshot searchable = snapshot;
        if (masked) searchable = mapper.convertValue(PolicyPrivacy.mask(map(snapshot), "", names,
                ips), PolicySnapshot.class);
        var predicate = PolicyRuleQuery.compile(query, searchable.objects(), java.time.Instant.now(), snapshot.metadata().vendor());
        Set<String> matches = searchable.sections().stream().flatMap(s -> s.rules().stream()).filter(predicate)
                .map(Rule::id).collect(java.util.stream.Collectors.toSet());
        var now = java.time.Instant.now();
        List<Rule> matching = snapshot.sections().stream().flatMap(s -> s.rules().stream()).filter(r -> matches.contains(r.id()))
            .filter(r -> hitMatches(r, hitFilter, days, now)).toList();
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
    static boolean hitMatches(Rule rule, String filter, int days, java.time.Instant now) {
        if (filter.isEmpty()) return true;
        var hits = rule.hitCounts();
        if (hits == null) return false;
        if (Long.valueOf(0).equals(hits.hits()) && hits.firstHit() == null && hits.lastHit() == null) return true;
        if (filter.equals("never") || hits.lastHit() == null) return false;
        try { return !java.time.Instant.parse(hits.lastHit()).isAfter(now.minus(java.time.Duration.ofDays(days))); }
        catch (RuntimeException invalid) { return false; }
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
