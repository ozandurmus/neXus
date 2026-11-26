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
    /** Rule extras whose values are object references the viewer must be able to resolve (NAT translations included). */
    private static final List<String> REF_EXTRAS = List.of("time", "schedule", "install-on", "vpn", "content",
            "translated-source", "translated-destination", "translated-service", "translated-source-fallback", "translated-destination-dynamic");
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
    /**
     * The source device's hostnames by device ID: a stored snapshot names its source "Panorama &lt;id&gt;" or "MDS &lt;id&gt;"
     * while the collection sources name it by the device's hostname, so the Policy tree and the container table
     * showed the same Panorama under two pseudonyms (UI review 2026-10-18, F-2). Every policy view now carries
     * {@link PolicySources#displayName} of the device's own hostname. Absent in unit tests that do not set it.
     */
    private volatile com.securityexpert.nexus.ui2.persistence.device.DeviceRepository devices;
    private volatile Map<String, String> sourceHostnames;
    private volatile java.time.Instant sourceHostnamesAt;
    /** Firewalls Panorama names by serial, shown by hostname (PO 2026-10-22); absent in unit tests that set no inventory. */
    private volatile PolicyFirewallNames firewalls;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDeviceRepository(com.securityexpert.nexus.ui2.persistence.device.DeviceRepository devices) {
        this.devices = devices;
        this.sourceHostnamesAt = null;
        this.firewalls = devices == null ? null : new PolicyFirewallNames(devices);
    }

    /** The stored zone names of each Palo Alto firewall virtual system (V229); absent in unit tests that set none: every zone universe is then unknown. */
    private volatile com.securityexpert.nexus.ui2.persistence.device.PanVsysZoneRepository zoneSets;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPanVsysZoneRepository(com.securityexpert.nexus.ui2.persistence.device.PanVsysZoneRepository zoneSets) {
        this.zoneSets = zoneSets;
    }

    /**
     * The zones the snapshot's rules reach ({@link ZoneUniverse}), or {@link ZoneUniverse#UNKNOWN} for another vendor, a
     * target without a fresh stored zone set, or any failure reading them: a missing universe only leaves a verdict undecided.
     */
    ZoneUniverse zoneUniverse(PolicySnapshot snapshot, java.time.Instant now) {
        var repository = zoneSets;
        if (repository == null || !"PAN".equals(snapshot.metadata().vendor()) || snapshot.metadata().targets().isEmpty()) return ZoneUniverse.UNKNOWN;
        try {
            var ids = snapshot.metadata().targets().stream().map(Target::deviceId).distinct().toList();
            return ZoneUniverse.of(snapshot, repository.findForDevices(ids), now);
        } catch (RuntimeException unreadable) {
            return ZoneUniverse.UNKNOWN;
        }
    }

    private Map<String, String> sourceHostnames() {
        var repository = devices;
        if (repository == null) return null;
        var at = sourceHostnamesAt;
        var now = java.time.Instant.now();
        if (at != null && at.plusSeconds(30).isAfter(now)) return sourceHostnames;
        Map<String, String> names = new HashMap<>();
        repository.listNameIdentities().forEach(d -> d.hostname().filter(h -> !h.isBlank()).ifPresent(h -> names.put(d.deviceId(), h)));
        sourceHostnames = Map.copyOf(names);
        sourceHostnamesAt = now;
        return sourceHostnames;
    }

    private Metadata canonical(Metadata metadata, Map<String, String> hostnames) {
        if (hostnames == null || metadata.sourceId() == null) return metadata;
        String name = PolicySources.displayName(Optional.ofNullable(hostnames.get(metadata.sourceId())));
        var targets = firewallTargets(metadata);
        return name.equals(metadata.sourceName()) && targets == metadata.targets() ? metadata : new Metadata(metadata.id(), metadata.sourceId(), name,
            metadata.vendor(), metadata.containerId(), metadata.containerName(), metadata.name(), metadata.collectedAt(),
            metadata.artefactRef(), targets, metadata.inventoryProvenance());
    }

    /** A PAN policy's firewall targets named by hostname, not by the serial Panorama lists them under. */
    private List<Target> firewallTargets(Metadata metadata) {
        var names = firewalls;
        if (names == null || !"PAN".equals(metadata.vendor())) return metadata.targets();
        boolean changed = false;
        var out = new ArrayList<Target>();
        for (var target : metadata.targets()) {
            String name = names.targetName(target.deviceId(), target.name());
            changed |= !name.equals(target.name());
            out.add(name.equals(target.name()) ? target : new Target(target.deviceId(), name, target.context(), target.syncStatus()));
        }
        return changed ? List.copyOf(out) : metadata.targets();
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

    /** The derived-on-read policies of one source and domain (container). */
    static List<PolicySnapshot> localDomainSnapshots(List<PolicySnapshot> all, String source, String domain) {
        return all.stream().filter(s -> s.metadata().sourceId().equals(source) && s.metadata().containerId().equals(domain)).toList();
    }

    private record CachedDomain(String revision, PolicyDomainIndex index) {}
    private final Map<String, CachedDomain> domainCache = new LinkedHashMap<>(16, 0.75f, true);

    // ponytail: one lock for the bounded 32-domain cache; split locks only if stored-read contention is measured.
    synchronized PolicyDomainIndex domainIndex(String source, String domain) {
        var inventories = repository.inventoryRevision(source, domain);
        // Snapshot rows change only with a strictly newer collected_at (see PolicySnapshotRepository.save), so the
        // revision check no longer reads and hashes every full snapshot document on each request.
        var snapshotRevisions = repository.domainSnapshotRevisions(source, domain);
        // Policies derived on read (Cisco ASA, FortiGate, a Palo Alto firewall without Panorama) are never stored in
        // policy_snapshot, so a domain with no stored row is looked up among them; their ids never collide with a stored domain.
        List<PolicySnapshot> derived = List.of();
        if (inventories.isEmpty() && snapshotRevisions.isEmpty() && local != null) {
            derived = localDomainSnapshots(local.snapshots(), source, domain);
            snapshotRevisions = derived.stream().map(s -> s.metadata().id() + "|" + s.metadata().collectedAt() + "|" + s.metadata().artefactRef()).toList();
        }
        String revision;
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (var rows : List.of(inventories, snapshotRevisions)) for (String json : rows) {
                digest.update(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte) 0);
            }
            revision = HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        String key = ref(source, domain);
        var cached = domainCache.get(key);
        if (cached != null && cached.revision().equals(revision)) return cached.index();
        var index = new PolicyDomainIndex(consume -> inventoryChunks(source, domain, consume),
            derived.isEmpty() ? repository.domainSnapshots(source, domain).stream().map(json -> readSnapshot(json)).toList() : derived);
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
    static final Set<String> RULE_SORT_COLUMNS = Set.of("number", "name", "rule", "source", "destination", "service",
            "application", "action", "usage", "hits", "last_hit", "lastHit", "permissiveness", "hygiene", "flags");
    static final Set<String> OBJECT_SORT_COLUMNS = Set.of("name", "type", "value", "values", "members", "hygiene",
            "where_used", "whereUsed", "usage");
    static final Set<String> INSTALLATION_SORT_COLUMNS = Set.of("policy", "policyName", "name", "gateway", "targeted", "installed");
    static final Set<String> USAGE_SORT_COLUMNS = Set.of("policy", "policyName", "package", "layer", "layerName", "number");
    private static final Comparator<String> STRING_ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    public static boolean validRuleSort(String sort, String order) { return validSort(sort, order, RULE_SORT_COLUMNS); }
    public static boolean validObjectSort(String sort, String order) { return validSort(sort, order, OBJECT_SORT_COLUMNS); }
    public static boolean validInstallationSort(String sort, String order) { return validSort(sort, order, INSTALLATION_SORT_COLUMNS); }
    public static boolean validUsageSort(String sort, String order) { return validSort(sort, order, USAGE_SORT_COLUMNS); }

    static boolean validSort(String sort, String order, Set<String> allowList) {
        String s = sort == null ? "" : sort.strip();
        String o = order == null ? "" : order.strip();
        if (s.isEmpty()) return o.isEmpty();
        if (!allowList.contains(s)) return false;
        if (o.isEmpty()) return true;
        String dir = o.toLowerCase(Locale.ROOT);
        return dir.equals("asc") || dir.equals("desc");
    }

    private static <T, K> Comparator<T> sortComparator(java.util.function.Function<T, K> extractor,
            Comparator<K> keyComparator, String order, Comparator<T> tiebreak) {
        boolean desc = "desc".equalsIgnoreCase(order == null ? "" : order.strip());
        Comparator<K> nonNull = desc ? keyComparator.reversed() : keyComparator;
        return Comparator.comparing(extractor, Comparator.nullsLast(nonNull)).thenComparing(tiebreak);
    }

    private static String blankToNull(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    public Optional<PolicyResponse> domainObjects(String source, String domain, int page, String q, String type, String hygiene, boolean masked) {
        return domainObjects(source, domain, page, q, type, hygiene, masked, "", "");
    }
    public Optional<PolicyResponse> domainObjects(String source, String domain, int page, String q, String type, String hygiene, boolean masked, String sort, String order) {
        if (!validObjectSort(sort, order)) throw new IllegalArgumentException("Invalid object sort");
        var index = domainIndex(source, domain);
        if (index.inventories.isEmpty() && index.snapshots.isEmpty()) return Optional.empty();
        var views = views(index, masked);
        String needle = q.toLowerCase(Locale.ROOT);
        var objects = index.objects;
        String sortKey = sort == null ? "" : sort.strip();
        Comparator<Integer> comparator = sortKey.isEmpty()
            ? Comparator.comparing(i -> hygiene.equals("duplicates")
                ? String.valueOf(objects.get(i).get("duplicateId")) + objects.get(i).get("id") : views.sortKey()[i])
            : objectComparator(objects, views, sortKey, order);
        // The masked search/sort projection is built once per index; per request only filtering and sorting remain.
        var items = java.util.stream.IntStream.range(0, objects.size())
            .filter(i -> type.isEmpty() || objectType(String.valueOf(objects.get(i).get("type"))).equals(type))
            .filter(i -> switch (hygiene) {
                case "unused" -> Boolean.TRUE.equals(objects.get(i).get("unused"));
                case "duplicates" -> objects.get(i).get("duplicateId") != null;
                case "empty" -> Boolean.TRUE.equals(objects.get(i).get("emptyGroup"));
                case "single" -> Boolean.TRUE.equals(objects.get(i).get("singleMember"));
                default -> true;
            }).filter(i -> needle.isEmpty() || views.text()[i].contains(needle)).boxed()
            .sorted(comparator).toList();
        return Optional.of(new PolicyResponse(Map.of("objects", items.stream().skip((long) page * 200).limit(200).map(objects::get).toList(),
            "total", items.size(), "page", page, "pageSize", 200, "types", typeStates(index), "usageGaps", index.usageGaps)));
    }
    private static Comparator<Integer> objectComparator(List<Map<String, Object>> objects, PolicyDomainIndex.Views views, String sort, String order) {
        Comparator<Integer> tiebreak = Comparator.comparing(i -> String.valueOf(objects.get(i).get("id")));
        return switch (sort) {
            case "name" -> sortComparator(i -> {
                String id = String.valueOf(objects.get(i).get("id"));
                String full = views.sortKey()[i];
                String name = full.endsWith(id) ? full.substring(0, full.length() - id.length()) : full;
                return blankToNull("null".equals(name) ? null : name);
            }, STRING_ORDER, order, tiebreak);
            case "type" -> sortComparator(i -> blankToNull(objects.get(i).get("type")), STRING_ORDER, order, tiebreak);
            case "value", "values" -> sortComparator(i -> {
                var row = objects.get(i);
                var vals = (List<?>) row.getOrDefault("values", List.of());
                if (!vals.isEmpty()) return blankToNull(vals.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("; ")));
                return blankToNull(row.get("schedule"));
            }, STRING_ORDER, order, tiebreak);
            case "members" -> sortComparator(i -> {
                var members = (List<?>) objects.get(i).get("members");
                return members == null ? null : members.size();
            }, Integer::compareTo, order, tiebreak);
            case "hygiene" -> sortComparator(i -> {
                var row = objects.get(i);
                int flags = (Boolean.TRUE.equals(row.get("unused")) ? 4 : 0)
                        + (Boolean.TRUE.equals(row.get("emptyGroup")) ? 2 : 0)
                        + (Boolean.TRUE.equals(row.get("singleMember")) ? 1 : 0)
                        + (row.get("duplicateId") != null ? 1 : 0);
                if (flags == 0 && row.get("unused") == null) return null;
                return flags;
            }, Integer::compareTo, order, tiebreak);
            case "where_used", "whereUsed", "usage" -> sortComparator(i -> {
                var row = objects.get(i);
                int count = ((Number) row.getOrDefault("ruleCount", 0)).intValue() + ((Number) row.getOrDefault("groupCount", 0)).intValue();
                if (count == 0 && !Boolean.TRUE.equals(row.get("usageComplete"))) return null;
                return count;
            }, Integer::compareTo, order, tiebreak);
            default -> throw new IllegalArgumentException("Unknown object sort: " + sort);
        };
    }
    private PolicyDomainIndex.Views views(PolicyDomainIndex index, boolean masked) {
        // Only the searched/sorted fields are projected; name masking depends on "type", never on the other row fields.
        return index.views(masked, row -> {
            var fields = new LinkedHashMap<String, Object>();
            for (String key : List.of("name", "type", "values")) if (row.containsKey(key)) fields.put(key, row.get(key));
            return searchable(fields, masked);
        });
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
        return objectUsage(source, domain, uid, page, "", "");
    }
    public Optional<PolicyResponse> objectUsage(String source, String domain, String uid, int page, String sort, String order) {
        if (!validUsageSort(sort, order)) throw new IllegalArgumentException("Invalid usage sort");
        var index = domainIndex(source, domain);
        var object = index.objects.stream().filter(o -> o.get("uid").equals(uid)).findFirst();
        if (object.isEmpty()) return Optional.empty();
        var rules = index.rules.getOrDefault(uid, List.of()); var groups = index.groups.getOrDefault(uid, List.of());
        String sortKey = sort == null ? "" : sort.strip();
        if (!sortKey.isEmpty()) {
            Comparator<Map<String, Object>> tiebreak = Comparator.comparing(r -> r.get("policyId") + ":" + r.get("layerRef") + ":" + r.get("ruleId"));
            Comparator<Map<String, Object>> cmp = switch (sortKey) {
                case "policy", "policyName", "package" -> sortComparator(r -> blankToNull(r.get("policyName")), STRING_ORDER, order, tiebreak);
                case "layer", "layerName" -> sortComparator(r -> blankToNull(r.get("layerName")), STRING_ORDER, order, tiebreak);
                case "number" -> sortComparator(r -> {
                    Number n = (Number) r.get("number");
                    return n == null || n.intValue() <= 0 ? null : n.intValue();
                }, Integer::compareTo, order, tiebreak);
                default -> throw new IllegalArgumentException("Unknown usage sort: " + sortKey);
            };
            rules = rules.stream().sorted(cmp).toList();
        }
        return Optional.of(new PolicyResponse(Map.of("object", object.get(), "rules", rules.stream().skip((long) page * 200).limit(200).toList(),
            "groups", groups.stream().skip((long) page * 200).limit(200).toList(), "ruleCount", rules.size(), "groupCount", groups.size(), "page", page, "pageSize", 200,
            "usageComplete", object.get().get("usageComplete"), "managementInUse", object.get().get("managementInUse"),
            "usageReasons", object.get().get("usageReasons"))));
    }
    public Optional<PolicyResponse> objectDuplicates(String source, String domain, int page, String q, boolean masked) {
        var index = domainIndex(source, domain);
        if (index.inventories.isEmpty() && index.snapshots.isEmpty()) return Optional.empty();
        var views = views(index, masked);
        String needle = q.toLowerCase(Locale.ROOT);
        var matchingIds = java.util.stream.IntStream.range(0, index.objects.size()).filter(i -> needle.isEmpty() || views.text()[i].contains(needle))
            .mapToObj(i -> index.objects.get(i).get("id")).collect(java.util.stream.Collectors.toSet());
        var groups = index.duplicates.stream().filter(group -> ((List<?>) group.get("objects")).stream()
            .anyMatch(o -> matchingIds.contains(((Map<?, ?>) o).get("id")))).toList();
        var boundedGroups = groups.stream().skip((long) page * 200).limit(200).map(group -> {
            var members = (List<?>) group.get("objects");
            return Map.of("id", group.get("id"), "objects", members.stream().limit(200).toList(), "total", members.size());
        }).toList();
        return Optional.of(new PolicyResponse(Map.of("duplicates", boundedGroups, "total", groups.size(), "page", page, "pageSize", 200)));
    }
    public Optional<PolicyResponse> installations(String source, String domain, int page, String q, String policy, boolean masked) {
        return installations(source, domain, page, q, policy, masked, "", "");
    }
    public Optional<PolicyResponse> installations(String source, String domain, int page, String q, String policy, boolean masked, String sort, String order) {
        if (!validInstallationSort(sort, order)) throw new IllegalArgumentException("Invalid installation sort");
        var index = domainIndex(source, domain);
        if (index.snapshots.isEmpty()) return Optional.empty();
        var links = repository.inventoryLinks(source, domain);
        var gateways = index.inventories.stream().filter(i -> i.type().equals("gateways-and-servers"))
            .flatMap(i -> i.objects().stream()).toList();
        var rows = new ArrayList<Map<String, Object>>();
        for (var snapshot : index.snapshots) {
            var metadata = snapshot.metadata();
            var targets = firewallTargets(metadata);
            if (!policy.isEmpty() && !metadata.id().equals(policy)) continue;
            boolean all = targets.stream().anyMatch(t -> t.name().equals("ALL"));
            var seen = new HashSet<String>();
            for (var gateway : gateways) {
                var matchingLinks = links.stream().filter(l -> (!l.uid().isEmpty() && l.uid().equals(gateway.uid())) || (!l.name().isEmpty() && l.name().equals(gateway.name()))).toList();
                var target = targets.stream().filter(t -> (!t.name().isEmpty() && t.name().equals(gateway.name()))
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
            for (var target : targets) {
                if (seen.contains(target.deviceId()) && !target.name().equals("ALL")) continue;
                if (target.name().equals("ALL") && rows.stream().anyMatch(r -> r.get("policyId").equals(metadata.id()) && Boolean.TRUE.equals(r.get("allTargets")))) continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("policyId", metadata.id()); row.put("policyName", metadata.name()); row.put("id", target.deviceId());
                row.put("name", target.name().equals("ALL") ? "" : target.name()); row.put("deviceId", links.stream().anyMatch(l -> l.deviceId().equals(target.deviceId())) ? target.deviceId() : "");
                row.put("targeted", true); row.put("allTargets", target.name().equals("ALL")); row.put("installed", null);
                rows.add(row);
            }
            if (targets.isEmpty() && rows.stream().noneMatch(r -> r.get("policyId").equals(metadata.id()))) {
                var row = new LinkedHashMap<String, Object>();
                row.put("policyId", metadata.id()); row.put("policyName", metadata.name()); row.put("id", metadata.id());
                row.put("name", ""); row.put("deviceId", ""); row.put("targeted", null); row.put("allTargets", false); row.put("installed", null); rows.add(row);
            }
        }
        var matches = rows.stream().filter(r -> matches(r, q, masked)).toList();
        String sortKey = sort == null ? "" : sort.strip();
        if (!sortKey.isEmpty()) {
            Comparator<Map<String, Object>> tiebreak = Comparator.comparing(r -> r.get("policyId") + ":" + r.get("id"));
            Comparator<Map<String, Object>> cmp = switch (sortKey) {
                case "policy", "policyName" -> sortComparator(r -> blankToNull(searchable(r, masked).get("policyName")), STRING_ORDER, order, tiebreak);
                case "name", "gateway" -> sortComparator(r -> blankToNull(searchable(r, masked).get("name")), STRING_ORDER, order, tiebreak);
                case "targeted" -> sortComparator(r -> Boolean.TRUE.equals(r.get("allTargets")) ? 2 : r.get("targeted") == null ? null : Boolean.TRUE.equals(r.get("targeted")) ? 1 : 0, Integer::compareTo, order, tiebreak);
                case "installed" -> sortComparator(r -> (Boolean) r.get("installed"), Boolean::compare, order, tiebreak);
                default -> throw new IllegalArgumentException("Unknown installation sort: " + sortKey);
            };
            matches = matches.stream().sorted(cmp).toList();
        }
        return Optional.of(new PolicyResponse(Map.of("installations", matches.stream().skip((long) page * 200).limit(200).toList(),
            "total", matches.size(), "page", page, "pageSize", 200, "types", typeStates(index))));
    }

    public Optional<PolicyResponse> domainHits(String source, String domain, int page) {
        var snapshots = repository.domainSnapshots(source, domain).stream().map(json -> readSnapshot(json)).toList();
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

    record AddressScope(String source, String domain) {}
    private static final Comparator<AddressScope> ADDRESS_SCOPE_ORDER = Comparator
        .comparing(AddressScope::source, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(AddressScope::domain, Comparator.nullsLast(Comparator.naturalOrder()));

    /** Scopes to search; {@code stored} are SQL-backed (failure is fatal), the rest are local-only; {@code localListFailed} if local.snapshots() threw. */
    record AddressScopeSet(List<AddressScope> scopes, Set<AddressScope> stored, boolean localListFailed) {}

    List<AddressScope> addressScopes() { return addressScopeSet().scopes(); }

    AddressScopeSet addressScopeSet() {
        var scopes = new TreeSet<>(ADDRESS_SCOPE_ORDER);
        for (var row : repositoryAddressScopes()) {
            scopes.add(new AddressScope(row.get("source_id", String.class), row.get("domain_ref", String.class)));
        }
        var stored = Set.copyOf(scopes);
        boolean localListFailed = false;
        if (local != null) {
            try {
                for (var snapshot : Objects.requireNonNullElse(local.snapshots(), List.<PolicySnapshot>of())) {
                    var metadata = snapshot.metadata();
                    if (metadata != null && metadata.sourceId() != null && metadata.containerId() != null) {
                        scopes.add(new AddressScope(metadata.sourceId(), metadata.containerId()));
                    }
                }
            } catch (RuntimeException unavailable) {
                localListFailed = true;
                System.getLogger(PolicyQueryService.class.getName()).log(System.Logger.Level.WARNING,
                    "POLICY_LOCAL_ADDRESS_SCOPES_UNAVAILABLE " + unavailable.getClass().getSimpleName());
            }
        }
        return new AddressScopeSet(List.copyOf(scopes), stored, localListFailed);
    }

    /**
     * Match bounded batches from the streamed index using the shared SQL address semantics.
     * A stored (SQL-backed) scope that cannot be read fails the whole search. An unreadable local snapshot scope is
     * skipped, but the result says so: {@code partial=true} and {@code unavailableScopes} (a count, no identities),
     * so a missing hit is never presented as "not present".
     */
    public Map<String, Object> searchAddresses(String query, int limit, int offset, boolean masked) {
        var scopeSet = addressScopeSet();
        int unavailableScopes = scopeSet.localListFailed() ? 1 : 0;
        var hits = new ArrayList<Map<String, Object>>();
        for (var scope : scopeSet.scopes()) {
            PolicyDomainIndex index;
            try {
                index = domainIndex(scope.source(), scope.domain());
            } catch (RuntimeException unavailable) {
                if (scopeSet.stored().contains(scope)) throw unavailable;
                unavailableScopes++;
                System.getLogger(PolicyQueryService.class.getName()).log(System.Logger.Level.WARNING,
                    "POLICY_ADDRESS_SCOPE_UNAVAILABLE " + unavailable.getClass().getSimpleName());
                continue;
            }
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
        return Map.of("items", hits.stream().skip(offset).limit(limit).toList(), "total", hits.size(),
            "partial", unavailableScopes > 0, "unavailableScopes", unavailableScopes);
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
        var hostnames = sourceHostnames();
        return hostnames == null ? catalog : new ArrayList<>(catalog.stream().map(m -> canonical(m, hostnames)).toList());
    }
    public List<Map<String, Object>> catalogViews() {
        var hostnames = sourceHostnames();
        var views = new ArrayList<>(repository.catalogEntries().stream().map(entry -> {
            var view = metadata(canonical(read(entry.metadataJson(), Metadata.class), hostnames));
            view.put("ruleCount", entry.ruleCount());
            return view;
        }).toList());
        if (local != null) local.snapshots().forEach(snapshot -> {
            var view = metadata(canonical(snapshot.metadata(), hostnames));
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

    /**
     * One policy for the viewer. The same instance is returned for as long as the stored row (or local artefact) and the
     * source naming are unchanged, so callers and {@link #analysis} can tell "same policy" by identity.
     */
    public Optional<PolicySnapshot> find(String id) {
        var stored = storedSnapshot(id);
        var found = stored.isPresent() || local == null ? stored : local.snapshots().stream().filter(s -> s.metadata().id().equals(id)).findFirst();
        var hostnames = found.isPresent() ? sourceHostnames() : null;
        return hostnames == null ? found : found.map(s -> withCanonicalSource(id, s, hostnames));
    }
    /** Existence only; never reads or parses the snapshot document (history needs no more than this). */
    public boolean exists(String id) {
        return repository.exists(id) || local != null && local.snapshots().stream().anyMatch(s -> s.metadata().id().equals(id));
    }

    /** A source-named view of the cached snapshot, itself kept so repeated requests receive the same instance. */
    private record Canonical(java.lang.ref.WeakReference<PolicySnapshot> base, PolicySnapshot view) {}
    private final Map<String, Canonical> canonicalViews = new LinkedHashMap<>(16, 0.75f, true);
    private PolicySnapshot withCanonicalSource(String id, PolicySnapshot s, Map<String, String> hostnames) {
        var metadata = canonical(s.metadata(), hostnames);
        if (metadata == s.metadata()) return s;
        synchronized (canonicalViews) {
            var memo = canonicalViews.get(id);
            if (memo != null && memo.base().get() == s && memo.view().metadata().equals(metadata)) return memo.view();
            var view = new PolicySnapshot(metadata, s.sections(), s.objects(), s.failures());
            canonicalViews.put(id, new Canonical(new java.lang.ref.WeakReference<>(s), view));
            // The views share their sections with the cached parse; entries whose parse was evicted are released here.
            canonicalViews.values().removeIf(c -> c.base().get() == null);
            while (canonicalViews.size() > 64) canonicalViews.remove(canonicalViews.keySet().iterator().next());
            return view;
        }
    }

    /** Parse cache for stored snapshots, keyed by policy id and valid for one row version (collected_at and size). */
    private volatile VersionedWeightedCache<PolicySnapshot> snapshotCache = new VersionedWeightedCache<>(DEFAULT_SNAPSHOT_CACHE_WEIGHT);
    static final long DEFAULT_SNAPSHOT_CACHE_WEIGHT = 200_000;
    private final Map<String, java.util.concurrent.CompletableFuture<PolicySnapshot>> snapshotLoads = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong snapshotParses = new java.util.concurrent.atomic.AtomicLong();
    /**
     * Total weight (rules plus objects) of parsed snapshots kept for reuse; about 200 000 holds a few large policies.
     * A single policy heavier than this is parsed on every request and never kept.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureSnapshotCache(@org.springframework.beans.factory.annotation.Value("${ui2.policy.snapshot-cache.max-weight:200000}") long maxWeight) {
        this.snapshotCache = new VersionedWeightedCache<>(Math.max(1, maxWeight));
    }
    /** Number of full snapshot parses so far (cache misses and domain reads), for tests and diagnostics. */
    long snapshotParseCount() { return snapshotParses.get(); }
    VersionedWeightedCache<PolicySnapshot> snapshotCache() { return snapshotCache; }

    private Optional<PolicySnapshot> storedSnapshot(String id) {
        var cache = snapshotCache;
        var version = repository.version(id);
        if (version.isEmpty()) { cache.invalidate(id); return Optional.empty(); }
        var hit = cache.get(id, version.get());
        if (hit != null) return Optional.of(hit);
        // Single flight: concurrent first requests for one policy share one read and one parse.
        var mine = new java.util.concurrent.CompletableFuture<PolicySnapshot>();
        var running = snapshotLoads.putIfAbsent(id, mine);
        if (running != null) {
            try { return Optional.ofNullable(running.join()); }
            catch (java.util.concurrent.CompletionException failed) {
                if (failed.getCause() instanceof RuntimeException cause) throw cause;
                if (failed.getCause() instanceof Error cause) throw cause;
                throw failed;
            }
        }
        try {
            var row = repository.findVersioned(id);
            if (row.isEmpty()) { cache.invalidate(id); mine.complete(null); return Optional.empty(); }
            var parsed = readSnapshot(row.get().snapshotJson());
            long weight = parsed.objects().size();
            for (var section : parsed.sections()) weight += section.rules().size();
            cache.put(id, row.get().version(), parsed, weight);
            mine.complete(parsed);
            return Optional.of(parsed);
        } catch (RuntimeException | Error failed) {
            mine.completeExceptionally(failed);
            throw failed;
        } finally { snapshotLoads.remove(id, mine); }
    }
    private PolicySnapshot readSnapshot(String json) {
        snapshotParses.incrementAndGet();
        return read(json, PolicySnapshot.class).withPageSplitsMerged().withHitLevels();
    }
    private <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (Exception e) { throw new IllegalStateException("Stored policy is invalid"); }
    }
    public Map<String, Object> metadata(Metadata metadata) {
        var view = map(metadata);
        // The recorded serial is the operators' secondary field beside the hostname.
        if (firewalls != null && "PAN".equals(metadata.vendor())) view.put("targets", firewalls.targets(metadata.vendor(), view.get("targets")));
        return view;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return mapper.convertValue(value, Map.class); }

    private record CachedHygiene(PolicySnapshot snapshot, int days, java.time.Instant at, java.util.concurrent.CompletableFuture<PolicyHygiene.Result> result) {}
    private final Map<String, CachedHygiene> hygieneCache = new LinkedHashMap<>(8, 0.75f, true);
    /** At most two analyses run at once (each is single-threaded and CPU bound; the service pod has 4 CPUs). */
    private final java.util.concurrent.Semaphore hygieneSlots = new java.util.concurrent.Semaphore(2, true);
    @FunctionalInterface
    interface HygieneComputer {
        PolicyHygiene.Result compute(PolicySnapshot snapshot, int days, java.time.Instant now);
    }

    private volatile HygieneComputer hygieneComputer = this::compute;

    void setHygieneComputer(HygieneComputer hygieneComputer) {
        this.hygieneComputer = Objects.requireNonNull(hygieneComputer);
    }

    /**
     * Single flight per snapshot: the first request for a snapshot (and threshold) computes, concurrent requests for the
     * same one wait for that result, and other snapshots compute independently. The cache lock only guards the 4-entry
     * map, never the computation. A finished result is reused for 30 seconds; a failed one is dropped and rethrown.
     * The snapshot's identity is its version: {@link #find} returns the same instance until the stored row (or the source
     * naming) changes, so a replaced row arrives as a different instance and recomputes, and no whole-snapshot
     * comparison is needed on the hot path.
     */
    PolicyHygiene.Result analysis(PolicySnapshot snapshot, int days) {
        var now = java.time.Instant.now(); String id = snapshot.metadata().id();
        CachedHygiene entry; boolean compute = false;
        synchronized (hygieneCache) {
            entry = hygieneCache.get(id);
            if (entry == null || entry.days() != days || entry.result().isDone() && !entry.at().plusSeconds(30).isAfter(now)
                    || entry.snapshot() != snapshot) {
                entry = new CachedHygiene(snapshot, days, now, new java.util.concurrent.CompletableFuture<>()); compute = true;
                hygieneCache.put(id, entry);
                if (hygieneCache.size() > 4) hygieneCache.remove(hygieneCache.keySet().iterator().next());
            }
        }
        if (compute) {
            hygieneSlots.acquireUninterruptibly();
            try {
                entry.result().complete(hygieneComputer.compute(snapshot, days, now));
            } catch (RuntimeException | Error failed) {
                entry.result().completeExceptionally(failed);
                synchronized (hygieneCache) { hygieneCache.remove(id, entry); }
            } finally { hygieneSlots.release(); }
        }
        try { return entry.result().join(); }
        catch (java.util.concurrent.CompletionException failed) {
            if (failed.getCause() instanceof RuntimeException cause) throw cause;
            if (failed.getCause() instanceof Error cause) throw cause;
            throw failed;
        }
    }
    PolicyHygiene.Result compute(PolicySnapshot snapshot, int days, java.time.Instant now) {
        return PolicyHygiene.analyze(snapshot, days, now, PolicyHygiene.TOTAL_WORK, breadthObjects(snapshot, inventoryIndex(snapshot)), zoneUniverse(snapshot, now));
    }
    /**
     * The per-class rule counts of one snapshot for the fleet summary: the same analysis the hygiene page runs, but computed
     * outside the four-entry interactive cache (a background pass must not evict what an operator is looking at) and counted
     * the way {@link #hygiene} counts ("rules carrying at least one finding of the class"). Takes one analysis slot, so it
     * never runs beside more than one interactive analysis.
     */
    Map<String, Integer> hygieneCounts(PolicySnapshot snapshot, int days) { return hygieneReport(snapshot, days).classes(); }
    /** The reason key of an UNKNOWN shadowing verdict (or of a blocker) that carries no reason literal. */
    static final String NO_REASON = "unknown reason";
    /**
     * What one snapshot's analysis says besides the per-class counts: how many rules are UNKNOWN for each shadowing reason,
     * and how many rules sit behind a named blocker per the blocker's own reason. Keys are the engine's fixed reason
     * literals ({@link PolicyHygiene#REASONS}) or {@link #NO_REASON}, never rule, object or address names.
     */
    record HygieneReport(Map<String, Integer> classes, Map<String, Integer> unknownReasons, Map<String, Integer> blockerReasons,
            int hitUnused, int hitOnlyUnknown) {
        HygieneReport(Map<String, Integer> classes, Map<String, Integer> unknownReasons, Map<String, Integer> blockerReasons) {
            this(classes, unknownReasons, blockerReasons, 0, 0);
        }
    }
    /** {@link #hygieneCounts} plus the UNKNOWN reasons, from the same single analysis (one slot, no second pass over the snapshot). */
    HygieneReport hygieneReport(PolicySnapshot snapshot, int days) {
        hygieneSlots.acquireUninterruptibly();
        PolicyHygiene.Result result;
        try { result = compute(snapshot, days, java.time.Instant.now()); }
        finally { hygieneSlots.release(); }
        Map<String, Integer> counts = new TreeMap<>(); PolicyHygiene.CLASSES.forEach(c -> counts.put(c, 0));
        Map<String, Integer> unknown = new TreeMap<>(), blockers = new TreeMap<>();
        int hitUnused = 0, hitOnlyUnknown = 0;
        for (var a : result.rules().values()) {
            // Member-specific (hit counter) evidence, kept apart so an HA pair never presents one member's counters as the pair's.
            if (a.findings().stream().anyMatch(f -> "unused".equals(f.findingClass()) && PolicyHygiene.HIT_EVIDENCE.contains(f.evidence()))) hitUnused++;
            // A rule with any hit-counter UNKNOWN finding is member-specific for that evidence, even if it also has a content UNKNOWN.
            if (a.findings().stream().anyMatch(f -> "unknown".equals(f.findingClass()) && PolicyHygiene.HIT_EVIDENCE.contains(f.evidence()))) hitOnlyUnknown++;
            a.findings().stream().map(PolicyHygiene.Finding::findingClass).distinct().forEach(c -> counts.merge(c, 1, Integer::sum));
            if (!"UNKNOWN".equals(a.shadowStatus())) continue;
            unknown.merge(reasonKey(a.shadowReason()), 1, Integer::sum);
            if (PolicyHygiene.EARLIER_UNSUPPORTED.equals(a.shadowReason()) && a.blockingRuleId() != null) {
                var blocker = result.rules().get(a.blockingRuleId());
                blockers.merge(reasonKey(blocker == null ? null : blocker.shadowReason()), 1, Integer::sum);
            }
        }
        return new HygieneReport(counts, unknown, blockers, hitUnused, hitOnlyUnknown);
    }
    private static String reasonKey(String reason) { return reason == null || reason.isBlank() ? NO_REASON : reason; }
    private Map<String, Object> ruleView(Rule r, PolicySnapshot snapshot, PolicyHygiene.Result analysis) {
        var rule = map(r); rule.put("identityFallback", r.uuid() == null || r.uuid().isEmpty());
        var assessment = analysis.rules().get(r.id());
        rule.put("hygiene", map(assessment));
        rule.put("timeStatus", assessment.timeStatus()); rule.put("expiring", assessment.expiring());
        rule.put("schedules", PolicyRuleMetrics.time(r, snapshot.objects(), java.time.Instant.now(), snapshot.metadata().vendor()).schedules().stream().map(this::map).toList());
        rule.put("permissiveness", assessment.permissiveness());
        // PAN target selectors are firewall serials; each carries the enrolled firewall's hostname when one matches exactly.
        var selectors = firewalls == null ? List.<Map<String, Object>>of() : firewalls.ruleSelectors(r.extras());
        if (!selectors.isEmpty()) rule.put("targetDevices", selectors);
        return rule;
    }
    /** The objects the rules' cells and reference extras name, as the rule viewer shows them: id, name, type and status, never members or values. */
    private List<Map<String, Object>> referencedObjects(PolicySnapshot snapshot, List<Rule> rules) {
        var refs = new LinkedHashSet<String>();
        for (var rule : rules) {
            for (var cell : List.of(rule.source(), rule.destination(), rule.service(), rule.application())) refs.addAll(cell.refs());
            for (String key : REF_EXTRAS) refs.addAll(rule.extras().getOrDefault(key, List.of()));
        }
        return refs.stream().filter(snapshot.objects()::containsKey).map(ref -> {
            var o = snapshot.objects().get(ref);
            return map(new PolicyObject(o.id(), o.name(), o.type(), List.of(), List.of(), o.status(), o.schedule()));
        }).toList();
    }
    public Optional<PolicyResponse> rule(PolicySnapshot snapshot, String id, int days) {
        for (var section : snapshot.sections()) for (var rule : section.rules()) if (rule.id().equals(id)) {
            var objects = referencedObjects(snapshot, List.of(rule));
            var sectionView = map(new Section(section.id(), section.name(), section.source(), section.parentRuleId(), List.of()));
            sectionView.put("total", section.rules().size());
            return Optional.of(new PolicyResponse(Map.of("metadata", metadata(snapshot.metadata()), "rule", ruleView(rule, snapshot, analysis(snapshot, days)),
                "section", sectionView, "objects", objects)));
        }
        return Optional.empty();
    }
    /** The {@code unknown} evidence {@link PolicyHygiene} gives a rule whose hit counters were not stored at all (a collection gap, not an analysis limit). */
    static final Set<String> HITS_MISSING_EVIDENCE = Set.of("Hit counts were not collected or are invalid.", "Hit counts are missing for a target.");
    private static boolean unknownFinding(PolicyHygiene.Finding f) { return f.findingClass().equals("unknown"); }
    /**
     * The hygiene page plus the policy-wide coverage the page cannot show: how many rules the shadow analysis decided
     * and how many carry hit counters (two separate questions -- missing counters never make a proven shadow result
     * "not proven"), and the not-provable reasons counted over every rule, not the 200 on the page. The default view
     * (no class filter) lists only rules with a finding; a rule whose only result is "unknown" is a coverage gap, not
     * a finding, and is listed under the {@code unknown} class. Everything added here is numbers or fixed evidence
     * strings, so the AIView projection passes it unchanged.
     */
    public PolicyResponse hygiene(PolicySnapshot snapshot, int page, String cls, String severity, int days) {
        var analysis = analysis(snapshot, days);
        Map<String, Integer> counts = new TreeMap<>(); PolicyHygiene.CLASSES.forEach(c -> counts.put(c, 0));
        Map<String, Integer> reasons = new LinkedHashMap<>();
        int rules = 0, shadowProven = 0, hitsCollected = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, int[]> blocked = new HashMap<>(); Map<String, Integer> blockerNumbers = new HashMap<>();
        for (var section : snapshot.sections()) for (var rule : section.rules()) {
            var assessment = analysis.rules().get(rule.id());
            rules++;
            if (assessment.blockingRuleId() != null) {
                blocked.computeIfAbsent(assessment.blockingRuleId(), k -> new int[1])[0]++;
                blockerNumbers.putIfAbsent(assessment.blockingRuleId(), assessment.blockingRuleNumber());
            }
            if (!"UNKNOWN".equals(assessment.shadowStatus())) shadowProven++;
            if (assessment.findings().stream().noneMatch(f -> unknownFinding(f) && HITS_MISSING_EVIDENCE.contains(f.evidence()))) hitsCollected++;
            assessment.findings().stream().filter(PolicyQueryService::unknownFinding).map(PolicyHygiene.Finding::evidence).distinct()
                .forEach(e -> reasons.merge(e, 1, Integer::sum));
            assessment.findings().stream().map(PolicyHygiene.Finding::findingClass).distinct().forEach(c -> counts.merge(c, 1, Integer::sum));
            var findings = assessment.findings().stream().filter(f -> cls.isEmpty() || f.findingClass().equals(cls))
                .filter(f -> severity.isEmpty() || f.severity().equals(severity)).toList();
            if (findings.isEmpty()) continue;
            if (cls.isEmpty() && findings.stream().allMatch(PolicyQueryService::unknownFinding)) continue;
            rows.add(Map.of("ruleId", rule.id(), "number", rule.number(), "name", rule.name(), "sectionId", section.id(),
                "findings", findings.stream().map(this::map).toList(), "hygiene", map(assessment)));
        }
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("rules", rules); coverage.put("shadowProven", shadowProven); coverage.put("shadowUnknown", rules - shadowProven);
        coverage.put("hitsCollected", hitsCollected); coverage.put("hitsMissing", rules - hitsCollected);
        List<Map<String, Object>> unknownReasons = reasons.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .map(e -> Map.<String, Object>of("evidence", e.getKey(), "rules", e.getValue())).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", rows.stream().skip((long) page * 200).limit(200).toList()); body.put("total", rows.size());
        body.put("page", page); body.put("pageSize", 200); body.put("counts", counts); body.put("budgetReached", analysis.budgetReached()); body.put("days", days);
        body.put("coverage", coverage); body.put("unknownReasons", unknownReasons);
        body.put("blockers", blockersWithSplit(snapshot, blocked, blockerNumbers));
        return new PolicyResponse(body);
    }
    /**
     * Policy Insights phase P1 (POLICY_INSIGHTS_DESIGN_2026_11_24.md): the package overview (section 3.2 counts, grade and
     * type totals) and one page of the insight list, Check Point only. Another vendor answers {@code supported=false}, never
     * an empty list that reads as "nothing found". Built from the same cached hygiene analysis and the domain index the
     * Hygiene and Objects tabs read; nothing is stored and no device is contacted.
     */
    public PolicyResponse insights(PolicySnapshot snapshot, int page, String type, String grade, int days) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vendor", snapshot.metadata().vendor());
        if (!"CP".equals(snapshot.metadata().vendor())) { body.put("supported", false); return new PolicyResponse(body); }
        var report = insightReport(snapshot, days);
        var rows = report.insights().stream().filter(i -> type.isEmpty() || i.type().equals(type)).filter(i -> grade.isEmpty() || i.grade().equals(grade)).toList();
        body.put("supported", true); body.put("days", days); body.put("page", page); body.put("pageSize", 200); body.put("total", rows.size());
        body.put("collectedAt", report.collectedAt()); body.put("complete", report.complete());
        body.put("counts", map(report.counts())); body.put("byType", report.byType()); body.put("byGrade", report.byGrade());
        body.put("unionCover", map(report.unionCover())); body.put("partialShadow", map(report.partialShadow()));
        body.put("merge", map(report.merge())); body.put("safeReorder", map(report.safeReorder()));
        body.put("insights", rows.stream().skip((long) page * 200).limit(200).map(this::insightRow).toList());
        return new PolicyResponse(body);
    }
    /**
     * One insight with the rules and objects behind it, for the detail view; empty when the id is not one of this package's
     * insights. {@code objects} are the insight's own objects (I11, I12); {@code ruleObjects} are the objects the involved
     * rules' cells name, so the view can compare the rules cell by cell.
     */
    public Optional<PolicyResponse> insightDetail(PolicySnapshot snapshot, String insightId, int days) {
        if (!"CP".equals(snapshot.metadata().vendor())) return Optional.empty();
        var report = insightReport(snapshot, days);
        var found = report.insights().stream().filter(i -> i.id().equals(insightId)).findFirst();
        if (found.isEmpty()) return Optional.empty();
        var insight = found.get();
        var analysis = analysis(snapshot, days);
        Map<String, Rule> byId = new HashMap<>();
        for (var section : snapshot.sections()) for (var rule : section.rules()) byId.put(rule.id(), rule);
        var involved = insight.rules().stream().map(r -> byId.get(r.ruleId())).filter(Objects::nonNull).toList();
        var rules = involved.stream().map(r -> ruleView(r, snapshot, analysis)).toList();
        var index = inventoryIndex(snapshot);
        var wanted = new HashSet<>(insight.objectIds());
        var objects = index == null ? List.<Map<String, Object>>of() : index.objects.stream().filter(o -> wanted.contains(String.valueOf(o.get("uid")))).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("insight", insightRow(insight)); body.put("rules", rules); body.put("objects", objects);
        // The comparison view names the objects in each rule's cells; the same projection the rule detail carries.
        body.put("ruleObjects", referencedObjects(snapshot, involved));
        body.put("days", days); body.put("collectedAt", report.collectedAt()); body.put("complete", report.complete());
        return Optional.of(new PolicyResponse(body));
    }
    private PolicyInsights.Report insightReport(PolicySnapshot snapshot, int days) {
        var hygiene = analysis(snapshot, days);
        var union = unionCover(snapshot, hygiene);
        var partial = partialShadow(snapshot, hygiene, union);
        return PolicyInsights.analyze(snapshot, hygiene, inventoryIndex(snapshot), Map.of(), days, union, partial, merge(snapshot), reorder(snapshot, partial));
    }
    private record CachedMerge(PolicySnapshot snapshot, PolicyMerge.Result result) {}
    private final Map<String, CachedMerge> mergeCache = new LinkedHashMap<>(8, 0.75f, true);
    /**
     * The I5 pass ({@link PolicyMerge}) of one snapshot instance, cached exactly like {@link #reorder}: it reads only the
     * snapshot, which does not depend on the unused threshold or the clock.
     */
    private PolicyMerge.Result merge(PolicySnapshot snapshot) {
        String id = snapshot.metadata().id();
        synchronized (mergeCache) {
            var cached = mergeCache.get(id);
            if (cached != null && cached.snapshot() == snapshot) return cached.result();
        }
        PolicyMerge.Result result;
        hygieneSlots.acquireUninterruptibly();
        try { result = PolicyMerge.analyze(snapshot); } finally { hygieneSlots.release(); }
        synchronized (mergeCache) {
            mergeCache.put(id, new CachedMerge(snapshot, result));
            if (mergeCache.size() > 4) mergeCache.remove(mergeCache.keySet().iterator().next());
        }
        return result;
    }
    private record CachedReorder(PolicySnapshot snapshot, PolicyReorder.Result result) {}
    private final Map<String, CachedReorder> reorderCache = new LinkedHashMap<>(8, 0.75f, true);
    /**
     * The I7 pass ({@link PolicyReorder}) of one snapshot instance, cached exactly like {@link #unionCover} and
     * {@link #partialShadow}: it reads only the snapshot and the I4 result, neither of which depends on the unused threshold
     * or the clock.
     */
    private PolicyReorder.Result reorder(PolicySnapshot snapshot, PolicyPartialShadow.Result partial) {
        String id = snapshot.metadata().id();
        synchronized (reorderCache) {
            var cached = reorderCache.get(id);
            if (cached != null && cached.snapshot() == snapshot) return cached.result();
        }
        PolicyReorder.Result result;
        hygieneSlots.acquireUninterruptibly();
        try { result = PolicyReorder.analyze(snapshot, partial); } finally { hygieneSlots.release(); }
        synchronized (reorderCache) {
            reorderCache.put(id, new CachedReorder(snapshot, result));
            if (reorderCache.size() > 4) reorderCache.remove(reorderCache.keySet().iterator().next());
        }
        return result;
    }
    private record CachedPartialShadow(PolicySnapshot snapshot, PolicyPartialShadow.Result result) {}
    private final Map<String, CachedPartialShadow> partialShadowCache = new LinkedHashMap<>(8, 0.75f, true);
    /**
     * The I4 pass ({@link PolicyPartialShadow}) of one snapshot instance, cached exactly like {@link #unionCover}: it reads
     * only the shadow verdicts and the I3 result, neither of which depends on the unused threshold or the clock.
     */
    private PolicyPartialShadow.Result partialShadow(PolicySnapshot snapshot, PolicyHygiene.Result hygiene, PolicyUnionCover.Result union) {
        String id = snapshot.metadata().id();
        synchronized (partialShadowCache) {
            var cached = partialShadowCache.get(id);
            if (cached != null && cached.snapshot() == snapshot) return cached.result();
        }
        PolicyPartialShadow.Result result;
        hygieneSlots.acquireUninterruptibly();
        try { result = PolicyPartialShadow.analyze(snapshot, hygiene, union); } finally { hygieneSlots.release(); }
        synchronized (partialShadowCache) {
            partialShadowCache.put(id, new CachedPartialShadow(snapshot, result));
            if (partialShadowCache.size() > 4) partialShadowCache.remove(partialShadowCache.keySet().iterator().next());
        }
        return result;
    }
    private record CachedUnionCover(PolicySnapshot snapshot, PolicyUnionCover.Result result) {}
    private final Map<String, CachedUnionCover> unionCoverCache = new LinkedHashMap<>(8, 0.75f, true);
    /**
     * The I3 pass ({@link PolicyUnionCover}) of one snapshot instance, kept for the same four snapshots the hygiene cache
     * holds. It reads only which rules the shadow engine found REDUNDANT or CONFLICT, which does not depend on the unused
     * threshold or the clock, so the snapshot instance (its version, see {@link #analysis}) is the whole key. Computed in
     * one of the analysis slots: it is CPU bound like the hygiene analysis.
     */
    private PolicyUnionCover.Result unionCover(PolicySnapshot snapshot, PolicyHygiene.Result hygiene) {
        String id = snapshot.metadata().id();
        synchronized (unionCoverCache) {
            var cached = unionCoverCache.get(id);
            if (cached != null && cached.snapshot() == snapshot) return cached.result();
        }
        PolicyUnionCover.Result result;
        hygieneSlots.acquireUninterruptibly();
        try { result = PolicyUnionCover.analyze(snapshot, hygiene); } finally { hygieneSlots.release(); }
        synchronized (unionCoverCache) {
            unionCoverCache.put(id, new CachedUnionCover(snapshot, result));
            if (unionCoverCache.size() > 4) unionCoverCache.remove(unionCoverCache.keySet().iterator().next());
        }
        return result;
    }
    private Map<String, Object> insightRow(PolicyInsights.Insight i) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("insightId", i.id()); row.put("insightType", i.type()); row.put("evidenceGrade", i.grade()); row.put("insightScope", i.scope());
        row.put("rules", i.rules().stream().map(this::map).toList()); row.put("objectIds", i.objectIds());
        row.put("raiseTo", Map.of("evidenceGrade", i.raiseGrade(), "insightNeeds", i.needs())); row.put("insightCaveats", i.caveats());
        row.put("evidence", i.evidence()); row.put("counter", i.counter());
        return row;
    }
    /** How many rules each earlier rule keeps from being proven ("earlier rule has unsupported constraints"): the distinct count and the five biggest, ids, rule numbers and counts only. */
    static final int TOP_BLOCKERS = 5;
    private static Map<String, Object> blockers(Map<String, int[]> blocked, Map<String, Integer> numbers) {
        List<Map<String, Object>> top = blocked.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> -e.getValue()[0])
                .thenComparing(e -> numbers.getOrDefault(e.getKey(), Integer.MAX_VALUE)).thenComparing(Map.Entry::getKey))
            .limit(TOP_BLOCKERS).map(e -> Map.<String, Object>of("ruleId", e.getKey(), "number", numbers.getOrDefault(e.getKey(), 0), "blocks", e.getValue()[0])).toList();
        return Map.of("distinct", blocked.size(), "top", top);
    }
    /** {@link #blockers} plus the counters-only split by blocking class (X0); a failure of the diagnostic never fails the page. */
    private static Map<String, Object> blockersWithSplit(PolicySnapshot snapshot, Map<String, int[]> blocked, Map<String, Integer> numbers) {
        Map<String, Object> out = new LinkedHashMap<>(blockers(blocked, numbers));
        try { out.put("split", PolicyBlockerSplit.split(snapshot, blocked)); } catch (RuntimeException diagnosticFailed) { /* split is optional */ }
        return out;
    }
    public byte[] hygieneCsv(PolicySnapshot snapshot, String cls, String severity, int days, boolean masked) {
        var analysis = analysis(snapshot, days);
        StringBuilder csv = new StringBuilder("Rule,Number,Class,Severity,Evidence,Covering rule,Counter window,Hit source,Collected at\r\n");
        for (var section : snapshot.sections()) for (var rule : section.rules()) {
            var assessment = analysis.rules().get(rule.id());
            var identity = searchable(Map.of("name", rule.name(), "number", rule.number()), masked);
            for (var finding : assessment.findings()) {
                if ((!cls.isEmpty() && !cls.equals(finding.findingClass())) || (!severity.isEmpty() && !severity.equals(finding.severity()))) continue;
                var safe = searchable(map(assessment), masked);
                var fields = Arrays.asList(identity.get("name"), rule.number(), finding.findingClass(), finding.severity(), finding.evidence(),
                    assessment.coveringRuleId(), safe.get("counterWindow"), safe.get("hitSource"), safe.get("hitsCollectedAt"));
                csv.append(fields.stream().map(PolicyQueryService::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
            }
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.matches("(?s)^[\\s]*[=+@-].*")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    private final PolicyProposalView proposals = new PolicyProposalView(hygieneSlots);
    /** Policy Insights phase P2 step c-2c: the read-only reorder proposal of one Check Point package (cached per snapshot). */
    public PolicyResponse proposal(PolicySnapshot snapshot, int days) { return proposals.response(snapshot, days); }

    public PolicyResponse page(PolicySnapshot snapshot, int page, String query, boolean masked) {
        return page(snapshot, page, query, masked, "", 90, "", "");
    }
    public PolicyResponse page(PolicySnapshot snapshot, int page, String query, boolean masked, String hitFilter, int days) {
        return page(snapshot, page, query, masked, hitFilter, days, "", "");
    }
    public PolicyResponse page(PolicySnapshot snapshot, int page, String query, boolean masked, String hitFilter, int days, String sort, String order) {
        if (!validRuleSort(sort, order)) throw new IllegalArgumentException("Invalid rule sort");
        var hygiene = analysis(snapshot, days);
        PolicySnapshot searchable = snapshot;
        if (masked) searchable = mapper.convertValue(PolicyPrivacy.mask(map(snapshot), "", names,
                ips), PolicySnapshot.class);
        var predicate = PolicyRuleQuery.compile(query, searchable.objects(), java.time.Instant.now(), snapshot.metadata().vendor());
        Set<String> matches = searchable.sections().stream().flatMap(s -> s.rules().stream()).filter(predicate)
                .map(Rule::id).collect(java.util.stream.Collectors.toSet());
        List<Rule> matching = snapshot.sections().stream().flatMap(s -> s.rules().stream()).filter(r -> matches.contains(r.id()))
            .filter(r -> hitMatches(r, hitFilter, days)).toList();
        String sortKey = sort == null ? "" : sort.strip();
        List<Map<String, Object>> sections = new ArrayList<>();
        Set<String> objectIds = new LinkedHashSet<>();
        if (sortKey.isEmpty()) {
            Set<String> ids = new HashSet<>();
            matching.stream().skip((long) page * 200).limit(200).forEach(r -> ids.add(r.id()));
            for (Section section : snapshot.sections()) {
                List<Rule> rules = section.rules().stream().filter(r -> ids.contains(r.id())).toList();
                collectRuleObjectIds(rules, objectIds);
                Map<String, Object> view = map(new Section(section.id(), section.name(), section.source(), section.parentRuleId(), rules));
                view.put("rules", rules.stream().map(r -> ruleView(r, snapshot, hygiene)).toList());
                view.put("total", section.rules().size());
                sections.add(view);
            }
        } else {
            Map<String, Rule> searchableRules = new HashMap<>();
            for (Section s : searchable.sections()) for (Rule r : s.rules()) searchableRules.put(r.id(), r);
            Map<String, Section> ruleSections = new HashMap<>();
            for (Section s : snapshot.sections()) for (Rule r : s.rules()) ruleSections.put(r.id(), s);
            List<Rule> sortedPage = matching.stream()
                .sorted(ruleComparator(searchableRules, searchable.objects(), hygiene, sortKey, order))
                .skip((long) page * 200).limit(200).toList();
            collectRuleObjectIds(sortedPage, objectIds);
            if (sortedPage.isEmpty()) {
                for (Section section : snapshot.sections()) {
                    Map<String, Object> view = map(new Section(section.id(), section.name(), section.source(), section.parentRuleId(), List.of()));
                    view.put("rules", List.of());
                    view.put("total", section.rules().size());
                    sections.add(view);
                }
            } else {
                Set<String> seenSectionIds = new HashSet<>();
                Section currentSection = null;
                List<Rule> currentRules = new ArrayList<>();
                for (Rule rule : sortedPage) {
                    Section section = ruleSections.get(rule.id());
                    if (currentSection != null && !currentSection.id().equals(section.id())) {
                        sections.add(sortedSectionView(currentSection, currentRules, snapshot, hygiene, seenSectionIds, sections.size()));
                        currentRules = new ArrayList<>();
                    }
                    currentSection = section;
                    currentRules.add(rule);
                }
                if (currentSection != null) {
                    sections.add(sortedSectionView(currentSection, currentRules, snapshot, hygiene, seenSectionIds, sections.size()));
                }
            }
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
    private static void collectRuleObjectIds(List<Rule> rules, Set<String> objectIds) {
        for (Rule rule : rules) for (Cell cell : List.of(rule.source(), rule.destination(), rule.service(), rule.application()))
            objectIds.addAll(cell.refs());
        for (Rule rule : rules) for (String key : REF_EXTRAS)
            objectIds.addAll(rule.extras().getOrDefault(key, List.of()));
    }
    private Map<String, Object> sortedSectionView(Section section, List<Rule> rules, PolicySnapshot snapshot,
            PolicyHygiene.Result hygiene, Set<String> seenSectionIds, int index) {
        String sectionId = seenSectionIds.add(section.id()) ? section.id() : section.id() + ":" + index;
        Map<String, Object> view = map(new Section(sectionId, section.name(), section.source(), section.parentRuleId(), rules));
        view.put("rules", rules.stream().map(r -> ruleView(r, snapshot, hygiene)).toList());
        view.put("total", section.rules().size());
        return view;
    }
    private static Comparator<Rule> ruleComparator(Map<String, Rule> searchableRules, Map<String, PolicyObject> objects,
            PolicyHygiene.Result hygiene, String sort, String order) {
        Comparator<Rule> tiebreak = Comparator.comparing(Rule::id);
        return switch (sort) {
            case "number" -> sortComparator(r -> r.number() <= 0 ? null : r.number(), Integer::compareTo, order, tiebreak);
            case "name", "rule" -> sortComparator(r -> blankToNull(searchableRules.getOrDefault(r.id(), r).name()), STRING_ORDER, order, tiebreak);
            case "source" -> sortComparator(r -> cellSortKey(searchableRules.getOrDefault(r.id(), r).source(), objects), STRING_ORDER, order, tiebreak);
            case "destination" -> sortComparator(r -> cellSortKey(searchableRules.getOrDefault(r.id(), r).destination(), objects), STRING_ORDER, order, tiebreak);
            case "service" -> sortComparator(r -> cellSortKey(searchableRules.getOrDefault(r.id(), r).service(), objects), STRING_ORDER, order, tiebreak);
            case "application" -> sortComparator(r -> cellSortKey(searchableRules.getOrDefault(r.id(), r).application(), objects), STRING_ORDER, order, tiebreak);
            case "action" -> sortComparator(r -> blankToNull(r.action()), STRING_ORDER, order, tiebreak);
            case "usage", "hits" -> sortComparator(r -> r.hitCounts() == null ? null : r.hitCounts().hits(), Long::compareTo, order, tiebreak);
            case "last_hit", "lastHit" -> sortComparator(r -> r.hitCounts() == null ? null : blankToNull(r.hitCounts().lastHit()), STRING_ORDER, order, tiebreak);
            case "permissiveness" -> sortComparator(r -> {
                var a = hygiene.rules().get(r.id());
                if (a == null || a.permissiveness() == null) return null;
                String level = String.valueOf(a.permissiveness().getOrDefault("level", "Unknown"));
                if ("Unknown".equalsIgnoreCase(level)) return null;
                Number score = (Number) a.permissiveness().get("score");
                return score == null ? null : score.intValue();
            }, Integer::compareTo, order, tiebreak);
            case "hygiene", "flags" -> sortComparator(r -> ruleFlagsSortKey(r, hygiene.rules().get(r.id())), STRING_ORDER, order, tiebreak);
            default -> throw new IllegalArgumentException("Unknown rule sort: " + sort);
        };
    }
    private static String cellSortKey(Cell cell, Map<String, PolicyObject> objects) {
        if (cell == null || cell.refs().isEmpty()) return null;
        String joined = cell.refs().stream().map(ref -> {
            PolicyObject o = objects.get(ref);
            return o != null && o.name() != null && !o.name().isBlank() ? o.name() : ref;
        }).collect(java.util.stream.Collectors.joining(", "));
        if (joined.isBlank()) return null;
        return cell.negated() ? "! " + joined : joined;
    }
    private static String ruleFlagsSortKey(Rule r, PolicyHygiene.Assessment assessment) {
        List<String> flags = new ArrayList<>();
        if (Boolean.FALSE.equals(r.enabled())) flags.add("disabled");
        if (r.uuid() == null || r.uuid().isEmpty()) flags.add("no-uuid");
        if (assessment != null) {
            if ("expired".equals(assessment.timeStatus())) flags.add("expired");
            if (assessment.expiring()) flags.add("expiring");
            for (var f : assessment.findings()) {
                if (!"disabled".equals(f.findingClass()) && !"expired".equals(f.findingClass())) {
                    flags.add(f.findingClass());
                }
            }
        }
        return flags.isEmpty() ? null : String.join(",", flags);
    }
    /**
     * The usage presets of the rules page. "Unused N days" is judged at the counters' collection time, exactly as
     * {@link PolicyHygiene} judges the {@code unused} finding: a rule hit the day before a month-old collection was in
     * use when the evidence was taken, and the wall clock since then is snapshot age, not silence. Counters without a
     * readable collection time cannot anchor the window and never match (fail-closed).
     */
    static boolean hitMatches(Rule rule, String filter, int days) {
        if (filter.isEmpty()) return true;
        var hits = rule.hitCounts();
        if (hits == null) return false;
        if (Long.valueOf(0).equals(hits.hits()) && hits.firstHit() == null && hits.lastHit() == null) return true;
        if (filter.equals("never") || hits.lastHit() == null) return false;
        try {
            var collected = java.time.Instant.parse(hits.collectedAt());
            return !java.time.Instant.parse(hits.lastHit()).isAfter(collected.minus(java.time.Duration.ofDays(days)));
        } catch (RuntimeException invalid) { return false; }
    }
    public PolicyResponse history(String policy, String rule, int page) {
        return new PolicyResponse(Map.of("revisions", repository.history(policy, rule, page).stream()
                .map(json -> withTargetDevices(read(json, Map.class))).toList(), "page", page));
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> withTargetDevices(Map<String, Object> revision) {
        if (firewalls == null) return revision;
        var selectors = firewalls.changeSelectors(revision.get("changes"));
        if (selectors.isEmpty()) return revision;
        var out = new LinkedHashMap<>(revision);
        out.put("targetDevices", selectors);
        return out;
    }
    public PolicyResponse object(PolicySnapshot snapshot, String id) {
        return object(snapshot, id, inventoryIndex(snapshot));
    }
    PolicyResponse object(PolicySnapshot snapshot, String id, PolicyDomainIndex index) {
        return new PolicyResponse(Map.of("object", expand(snapshot, index, id, new HashSet<>(), new int[]{500}, 0)));
    }
    /**
     * The objects the permissiveness verdict reads. For Check Point, objects the rulebase dictionary left UNRESOLVED (group
     * members the dictionary did not carry) are joined from the same domain's stored object inventory, exactly as the
     * object viewer resolves them, and their members are followed within that inventory. Inventory rows are classified
     * with the same native-type table as the package mapper; a reference absent from both stays UNRESOLVED. Shadowing
     * does not read this map. Without an index (PAN, no repository, inventory unavailable) the snapshot objects are used.
     * A RESOLVED dictionary object of an address, service or group class that carries neither values nor members (for
     * example an address range stored before the mapper read ipv4-address-first/-last, or an empty-looking group) takes
     * the values/members of the same object (same uid) from the inventory when that row has the same viewer class and
     * is not itself empty; otherwise it is left unchanged. A Check Point service row without collected fields keeps its
     * protocol class ("protocol: other"), as the package mapper records it.
     */
    static Map<String, PolicyObject> breadthObjects(PolicySnapshot snapshot, PolicyDomainIndex index) {
        if (index == null) return snapshot.objects();
        Map<String, PolicyObject> out = new HashMap<>(snapshot.objects());
        var pending = new ArrayDeque<String>();
        snapshot.objects().forEach((id, object) -> { if ("UNRESOLVED".equals(object.status()) || valueless(object)) pending.add(id); });
        boolean changed = false;
        int visits = 0;
        while (!pending.isEmpty() && visits++ < 200_000) {
            String id = pending.removeFirst();
            PolicyObject current = out.get(id);
            boolean fill = current != null && valueless(current);
            if (current != null && !"UNRESOLVED".equals(current.status()) && !fill) continue;
            var row = current == null || fill ? null : index.inventoryObject(current.name());
            if (row == null) row = index.inventoryObject(id);
            if (row == null) continue;
            String nativeType = String.valueOf(row.get("type"));
            @SuppressWarnings("unchecked") List<String> uids = (List<String>) row.getOrDefault("members", List.of());
            @SuppressWarnings("unchecked") List<String> rowValues = (List<String>) row.getOrDefault("values", List.of());
            List<String> values = rowValues;
            if (values.isEmpty() && nativeType.startsWith("service-") && !nativeType.equals("service-group"))
                values = List.of("protocol: " + nativeType.substring("service-".length()));
            if (fill && (!com.securityexpert.nexus.ui2.policy.CpObjectTypes.type(nativeType).equals(current.type())
                    || !"RESOLVED".equals(com.securityexpert.nexus.ui2.policy.CpObjectTypes.status(nativeType))
                    || uids.isEmpty() && values.isEmpty())) continue;
            List<String> members = new ArrayList<>();
            for (String uid : uids) {
                String memberId = ref(snapshot.metadata().id(), "object", uid);
                members.add(memberId);
                if (!out.containsKey(memberId)) {
                    out.put(memberId, new PolicyObject(memberId, uid, "unresolved", List.of(), List.of(), "UNRESOLVED"));
                    pending.add(memberId);
                }
            }
            String type = com.securityexpert.nexus.ui2.policy.CpObjectTypes.type(nativeType);
            String name = fill ? current.name() : type.equals("any") ? "ANY" : String.valueOf(row.getOrDefault("name", id));
            out.put(id, new PolicyObject(id, name, type, members, values,
                com.securityexpert.nexus.ui2.policy.CpObjectTypes.status(nativeType)));
            changed = true;
        }
        return changed ? Collections.unmodifiableMap(out) : snapshot.objects();
    }
    private static final Set<String> FILLABLE = Set.of("address", "group", "service", "service-group");
    private static boolean valueless(PolicyObject object) {
        return "RESOLVED".equals(object.status()) && FILLABLE.contains(object.type()) && object.values().isEmpty() && object.members().isEmpty();
    }
    /** CP group members missing from the rulebase dictionary are resolved against the same domain's stored inventory. */
    private PolicyDomainIndex inventoryIndex(PolicySnapshot snapshot) {
        if (!"CP".equals(snapshot.metadata().vendor()) || repository == null) return null;
        try { return domainIndex(snapshot.metadata().sourceId(), snapshot.metadata().containerId()); }
        catch (RuntimeException unavailable) {
            // The snapshot-only expansion stays available; members then remain UNRESOLVED as before.
            System.getLogger(PolicyQueryService.class.getName()).log(System.Logger.Level.WARNING,
                "POLICY_OBJECT_INVENTORY_UNAVAILABLE " + unavailable.getClass().getSimpleName());
            return null;
        }
    }
    private Map<String, Object> expand(PolicySnapshot snapshot, PolicyDomainIndex index, String id, Set<String> path, int[] budget, int depth) {
        PolicyObject object = snapshot.objects().get(id);
        if ((object == null || "UNRESOLVED".equals(object.status())) && index != null) {
            var row = index.inventoryObject(object == null ? id : object.name());
            if (row == null && object != null) row = index.inventoryObject(id);
            if (row != null) return expandInventory(index, row, path, budget, depth);
        }
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
            children.add(expand(snapshot, index, member, path, budget, depth + 1));
        }
        path.remove(id);
        out.put("children", children);
        return out;
    }
    /** Expansion of a domain-inventory row; members are vendor uids resolved within the same inventory. */
    private Map<String, Object> expandInventory(PolicyDomainIndex index, Map<String, Object> row, Set<String> path, int[] budget, int depth) {
        String id = (String) row.get("id");
        Map<String, Object> out = new LinkedHashMap<>();
        for (String field : List.of("id", "name", "type", "values", "status", "schedule")) if (row.get(field) != null) out.put(field, row.get(field));
        out.put("resolution", "DOMAIN_INVENTORY");
        @SuppressWarnings("unchecked") List<String> members = (List<String>) row.getOrDefault("members", List.of());
        out.put("members", members.size() > 500 ? members.subList(0, 500) : members);
        if (members.size() > 500) out.put("totalMembers", members.size());
        if (path.contains(id)) { out.put("status", "CYCLE"); return out; }
        if (depth >= 32 || budget[0]-- <= 0) { out.put("status", "LIMIT"); return out; }
        path.add(id);
        List<Map<String, Object>> children = new ArrayList<>();
        for (String member : members) {
            if (budget[0] <= 0) { out.put("status", "LIMIT"); break; }
            var child = index.inventoryObject(member);
            children.add(child == null ? Map.of("id", member, "status", "UNRESOLVED") : expandInventory(index, child, path, budget, depth + 1));
        }
        path.remove(id);
        out.put("children", children);
        return out;
    }
}
