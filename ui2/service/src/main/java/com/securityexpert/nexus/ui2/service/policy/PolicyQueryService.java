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
    private final TransactionBoundary transactionsForSearch;
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
        this.transactionsForSearch = transactions;
        this.ips = ips;
        this.local = local;
        this.repository = new PolicySnapshotRepository(transactions);
        this.mapper = mapper;
        this.names = names;
    }
    /** Independent type gaps remain visible even when there are no policy packages. */
    public Optional<PolicyResponse> domainInventory(String source, String domain, String view, int page) {
        var states = new LinkedHashMap<String, Map<String, Object>>();
        var objects = new ArrayList<Map<String, Object>>();
        int[] total = {0};
        long offset = (long) page * 200;
        inventoryChunks(source, domain, inventory -> {
            boolean selected = switch (view) {
                case "unused" -> inventory.type().equals("unused-objects");
                case "gateways" -> inventory.type().equals("gateways-and-servers");
                default -> !inventory.type().equals("unused-objects") && !inventory.type().equals("gateways-and-servers");
            };
            if (!selected) return;
            var state = states.computeIfAbsent(inventory.type(), type -> new LinkedHashMap<>(Map.of(
                "type", type, "status", inventory.status(), "reason", inventory.reason(), "collectedAt", inventory.collectedAt(),
                "pages", inventory.pages(), "objects", 0, "seconds", inventory.seconds())));
            state.put("objects", (int) state.get("objects") + inventory.objects().size());
            for (var item : inventory.objects()) {
                if (total[0] >= offset && objects.size() < 200) objects.add(map(item));
                total[0]++;
            }
        });
        if (states.isEmpty()) return Optional.empty();
        return Optional.of(new PolicyResponse(Map.of("sourceId", source, "containerId", domain, "types", List.copyOf(states.values()),
            "objects", objects, "total", total[0])));
    }

    /** New inventories are parsed as bounded item batches; old JSON rows retain compatibility. */
    private void inventoryChunks(String source, String domain, java.util.function.Consumer<com.securityexpert.nexus.ui2.policy.CpObjectInventory> consume) {
        repository.streamInventoryChunks(source, domain, (metadata, reader) -> {
            try {
                if (metadata == null) {
                    consume.accept(mapper.readValue(reader, com.securityexpert.nexus.ui2.policy.CpObjectInventory.class));
                    return;
                }
                var header = read(metadata, com.securityexpert.nexus.ui2.policy.CpObjectInventory.class);
                var batch = new ArrayList<com.securityexpert.nexus.ui2.policy.CpObjectInventory.Item>();
                try (var parser = mapper.getFactory().createParser(reader)) {
                    if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT)
                        throw new IllegalStateException("POLICY_INVENTORY_INVALID");
                    while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                        if (parser.currentToken() != com.fasterxml.jackson.core.JsonToken.FIELD_NAME)
                            throw new IllegalStateException("POLICY_INVENTORY_INVALID");
                        String field = parser.currentName(); parser.nextToken();
                        if (!field.equals("objects")) { parser.skipChildren(); continue; }
                        if (parser.currentToken() != com.fasterxml.jackson.core.JsonToken.START_ARRAY)
                            throw new IllegalStateException("POLICY_INVENTORY_INVALID");
                        while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                            batch.add(mapper.readValue(parser, com.securityexpert.nexus.ui2.policy.CpObjectInventory.Item.class));
                            if (batch.size() == 200) { consume.accept(inventoryBatch(header, batch)); batch.clear(); }
                        }
                    }
                }
                // An empty terminal batch also exposes metadata for inventories with zero objects.
                consume.accept(inventoryBatch(header, batch));
            } catch (java.io.IOException invalid) { throw new IllegalStateException("POLICY_INVENTORY_INVALID"); }
        });
    }
    private static com.securityexpert.nexus.ui2.policy.CpObjectInventory inventoryBatch(
            com.securityexpert.nexus.ui2.policy.CpObjectInventory header, List<com.securityexpert.nexus.ui2.policy.CpObjectInventory.Item> items) {
        return new com.securityexpert.nexus.ui2.policy.CpObjectInventory(header.sourceId(), header.containerId(), header.type(),
            header.collectedAt(), header.status(), header.reason(), items, header.pages(), header.seconds());
    }

    private record CachedDomain(String revision, PolicyDomainIndex index) {}
    private final Map<String, CachedDomain> domainCache = new LinkedHashMap<>(16, 0.75f, true);

    // ponytail: one lock for the bounded 32-domain cache; split locks only if stored-read contention is measured.
    synchronized PolicyDomainIndex domainIndex(String source, String domain) {
        var inventories = repository.inventoryRevision(source, domain);
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
        var index = new PolicyDomainIndex(consume -> inventoryChunks(source, domain, consume),
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
            "collectedAt", i.collectedAt(), "objects", index.objectCounts.getOrDefault(i.type(), 0))).toList();
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

    /** Match bounded batches from the streamed index using the shared SQL address semantics. */
    public Map<String, Object> searchAddresses(String query, int limit, int offset, boolean masked) {
        record Scope(String source, String domain) {}
        var scopes = repositoryAddressScopes().stream().map(row -> new Scope(
            row.get("source_id", String.class), row.get("domain_ref", String.class))).toList();
        var hits = new ArrayList<Map<String, Object>>();
        for (var scope : scopes) {
            var index = domainIndex(scope.source(), scope.domain());
            var aliases = index.aliases;
            var objects = new LinkedHashMap<String, Map<String, Object>>();
            for (var object : index.objects) objects.put((String) object.get("uid"), object);
            var parents = new HashMap<String, Set<String>>();
            for (var entry : objects.entrySet()) {
                var object = entry.getValue();
                if (!Set.of("group", "address-group").contains(object.get("type"))
                        || object.containsKey("status") && !"RESOLVED".equals(object.get("status"))) continue;
                for (Object member : (List<?>) object.getOrDefault("members", List.of())) {
                    String id = aliases.getOrDefault((String) member, (String) member);
                    parents.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(entry.getKey());
                }
            }
            var direct = addressMatches(index.objects, query);
            var matching = containingGroups(direct, parents);
            for (String id : new TreeSet<>(matching)) {
                var object = objects.get(id);
                if (object == null) continue;
                String name = (String) object.get("name");
                if (masked) name = String.valueOf(searchable(object, true).get("name"));
                var hit = new LinkedHashMap<String, Object>();
                hit.put("id", object.get("id")); hit.put("name", name);
                hit.put("type", object.get("type")); hit.put("rule_count", index.rules.getOrDefault(id, List.of()).stream().map(r -> ref((String) r.get("policyId"), (String) r.get("ruleId"))).distinct().count());
                String policy = index.snapshots.stream().map(s -> s.metadata().id()).sorted().findFirst().orElse("");
                hit.put("href", "?screen=policy&tab=objects&source_id=" + searchUrl(scope.source())
                    + "&container_id=" + searchUrl(scope.domain()) + "&policy_id=" + searchUrl(policy));
                hits.add(hit);
            }
        }
        return Map.of("items", hits.stream().skip(offset).limit(limit).toList(), "total", hits.size());
    }
    private static String searchUrl(String id) {
        return java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8);
    }
    static Set<String> containingGroups(Set<String> leaves, Map<String, Set<String>> parents) {
        var matching = new LinkedHashSet<>(leaves);
        var queue = new ArrayDeque<>(leaves);
        while (!queue.isEmpty()) for (String parent : parents.getOrDefault(queue.removeFirst(), Set.of()))
            if (matching.add(parent)) queue.add(parent);
        return matching;
    }
    private List<org.jooq.Record> repositoryAddressScopes() {
        return transactionsForSearch.inTransaction(db -> new ArrayList<>(db.fetch("""
            select source_id, domain_ref from cp_policy_object_inventory
            where snapshot->>'status' = 'RESOLVED'
            union
            select metadata->>'sourceId', metadata->>'containerId' from policy_snapshot
            order by 1, 2
            """)));
    }
    private Set<String> addressMatches(List<Map<String, Object>> objects, String query) {
        var matching = new LinkedHashSet<String>();
        for (int offset = 0; offset < objects.size(); offset += 200) {
            String batch;
            try { batch = mapper.writeValueAsString(objects.subList(offset, Math.min(offset + 200, objects.size()))); }
            catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("POLICY_INVENTORY_INVALID"); }
            matching.addAll(transactionsForSearch.inTransaction(db -> db.fetch("""
                select o->>'uid' as uid from jsonb_array_elements({0}::jsonb) o
                where search_policy_address(o, {1}::inet)
                """, batch, query).map(row -> row.get("uid", String.class))));
        }
        return matching;
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
