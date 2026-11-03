package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.util.*;

/**
 * Seeded synthetic policy snapshots for the shadowing differential test and the size benchmark. Documentation address
 * blocks only (192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24) and masked-style names; nothing here is estate data.
 */
final class SyntheticPolicies {
    private SyntheticPolicies() {}
    static final String[] BLOCKS = {"192.0.2.", "198.51.100.", "203.0.113."};
    static final List<String> ZONES = List.of("ZONE-ALPHA-01", "ZONE-BRAVO-02", "ZONE-CHARLIE-03", "ZONE-DELTA-04");

    /** A small, deliberately messy snapshot: every branch of normalization, scoping and coverage is reachable. */
    static PolicySnapshot random(long seed, int size) {
        var random = new Random(seed);
        String vendor = random.nextInt(50) == 0 ? "OTHER" : random.nextBoolean() ? "PAN" : "CP";
        boolean cp = vendor.equals("CP");
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("any", object("any", "any"));
        int hosts = 4 + random.nextInt(8), nets = 2 + random.nextInt(4), services = 3 + random.nextInt(5);
        for (int i = 0; i < hosts; i++) objects.put("h" + i, object("h" + i, cp ? "host" : "address", "ipv4-address: " + BLOCKS[random.nextInt(2)] + (random.nextInt(64) + 1)));
        for (int i = 0; i < nets; i++) {
            String block = BLOCKS[random.nextInt(2)]; int mask = 25 + random.nextInt(5), base = random.nextInt(4) * 32;
            objects.put("n" + i, switch (random.nextInt(4)) {
                case 0 -> object("n" + i, "network", "subnet4: " + block + base, "mask-length4: " + mask);
                case 1 -> object("n" + i, "address-range", "ipv4-address-first: " + block + base, "ipv4-address-last: " + block + (base + 1 + random.nextInt(40)));
                case 2 -> object("n" + i, "address", "ip-range: " + block + base + "-" + block + (base + 1 + random.nextInt(60)));
                default -> object("n" + i, cp ? "network" : "address", "ip-netmask: " + block + base + "/" + mask);
            });
        }
        String addressGroup = cp ? "group" : "address-group";
        int groups = 2 + random.nextInt(3);
        for (int i = 0; i < groups; i++) {
            List<String> members = new ArrayList<>();
            for (int m = 0, n = 1 + random.nextInt(3); m < n; m++) members.add(pick(random, "h" + random.nextInt(hosts), "n" + random.nextInt(nets), i > 0 ? "g" + random.nextInt(i) : "any"));
            objects.put("g" + i, new PolicyObject("g" + i, "OBJ-GROUP-" + i, addressGroup, members, List.of(), "RESOLVED"));
        }
        objects.put("cycle", new PolicyObject("cycle", "OBJ-GROUP-CYCLE", addressGroup, List.of("cycle"), List.of(), "RESOLVED"));
        objects.put("unres", new PolicyObject("unres", "OBJ-UNRESOLVED", "host", List.of(), List.of(), "UNRESOLVED"));
        objects.put("dyn", object("dyn", "dynamic-object"));
        for (int i = 0; i < services; i++) {
            int port = random.nextInt(8) * 100 + 1; String protocol = random.nextInt(4) == 0 ? "udp" : "tcp";
            objects.put("s" + i, switch (random.nextInt(4)) {
                case 0 -> object("s" + i, "service-" + protocol, "protocol/" + protocol + "/port: " + port + "-" + (port + random.nextInt(300)));
                case 1 -> object("s" + i, "service", "protocol: " + protocol, "port: " + port + "," + (port + 50));
                case 2 -> object("s" + i, "service", "protocol: icmp", "port: 8");
                default -> object("s" + i, "service-" + protocol, "protocol: " + protocol, "port: " + port);
            });
        }
        objects.put("sg", new PolicyObject("sg", "OBJ-SVC-GROUP", "service-group", List.of("s0", "s1"), List.of(), "RESOLVED"));
        objects.put("ag", new PolicyObject("ag", "OBJ-APP-GROUP", "application-group", List.of("any"), List.of(), "RESOLVED"));
        objects.put("app", object("app", "application"));
        for (String gw : List.of("gw-a", "gw-b", "gw-c")) objects.put(gw, object(gw, "simple-gateway"));
        objects.put("gw-grp", new PolicyObject("gw-grp", "FW-TANGO-GROUP", "group", List.of("gw-a", "gw-b"), List.of(), "RESOLVED"));
        objects.put("gw-unres", new PolicyObject("gw-unres", "FW-JULIET-06", "simple-gateway", List.of(), List.of(), "UNRESOLVED"));
        objects.put("sched", object("sched", "time"));
        List<String> addresses = new ArrayList<>(List.of("any", "unres", "dyn", "cycle"));
        objects.keySet().stream().filter(k -> k.matches("[hng][0-9]+")).forEach(addresses::add);
        List<String> serviceRefs = new ArrayList<>(List.of("any", "sg", "unres"));
        objects.keySet().stream().filter(k -> k.matches("s[0-9]+")).forEach(serviceRefs::add);

        String id = "p" + seed;
        var metadata = new Metadata(id, "src-" + seed, "MGR-BRAVO-01", vendor, "ctr-" + seed, "DOM-TANGO-01", "POL-ALPHA-01",
            "2026-10-09T12:00:00Z", "a1", List.of());
        List<Section> sections = new ArrayList<>();
        int sectionCount = 1 + random.nextInt(3), next = 0, number = 0;
        var phases = List.of("pre-rulebase", "rulebase", "post-rulebase");
        int phase = 0; String layer = "LAYER-ALPHA";
        for (int s = 0; s < sectionCount; s++) {
            int rulesHere = s == sectionCount - 1 ? size - next : random.nextInt(size - next + 1);
            if (random.nextInt(6) == 0) layer = "LAYER-" + (char) ('A' + random.nextInt(3));
            if (random.nextInt(6) == 0) number = 0;
            List<Rule> rules = new ArrayList<>();
            for (int r = 0; r < rulesHere; r++) {
                number += 1 + random.nextInt(2);
                rules.add(rule(random, "r" + next++, number, cp, addresses, serviceRefs, layer));
            }
            if (cp) {
                String source = random.nextInt(15) == 0 ? "CP NAT rulebase" : "CP access layer";
                String parent = random.nextInt(15) == 0 ? "r0" : null;
                sections.add(new Section("sec-" + s, "OBJ-SECTION-" + s, source, parent, rules));
            } else {
                if (random.nextInt(5) != 0) phase = Math.min(2, phase + random.nextInt(2)); else phase = random.nextInt(3);
                String source = random.nextInt(4) == 0 ? "Shared" : "DG-TANGO-04";
                String name = random.nextInt(15) == 0 ? PAN_PRE_NAT : "OBJ-SECTION-" + s;
                String sectionId = random.nextInt(20) == 0 ? "bogus-" + s : ref(id, source, phases.get(phase));
                sections.add(new Section(sectionId, name, source, null, rules));
            }
        }
        List<CollectionFailure> failures = random.nextInt(20) == 0 ? List.of(new CollectionFailure("sec-0", "TIMEOUT")) : List.of();
        return new PolicySnapshot(metadata, sections, objects, failures);
    }
    private static Rule rule(Random random, String id, int number, boolean cp, List<String> addresses, List<String> services, String layer) {
        Boolean enabled = random.nextInt(12) == 0 ? Boolean.FALSE : random.nextInt(25) == 0 ? null : Boolean.TRUE;
        String action = random.nextInt(40) == 0 ? (cp ? "Apply Layer" : "ask")
            : cp ? pick(random, "Accept", "Accept", "Drop", "Reject") : pick(random, "allow", "allow", "deny", "drop", "reset-client");
        Map<String, List<String>> extras = new HashMap<>();
        if (cp) {
            int install = random.nextInt(14);
            if (install != 0) extras.put("install-on", install < 6 ? List.of("any") : install < 8 ? List.of("gw-a") : install < 10 ? List.of("gw-b")
                : install < 11 ? List.of("gw-a", "gw-b") : install < 13 ? List.of("gw-grp") : List.of("gw-unres"));
            int time = random.nextInt(25);
            if (time != 0) extras.put("time", List.of(time == 1 ? "sched" : "any"));
            extras.put("layer-name", List.of(layer));
        } else {
            if (random.nextInt(40) != 0) extras.put("from", zones(random));
            if (random.nextInt(40) != 0) extras.put("to", zones(random));
            if (random.nextInt(30) == 0) extras.put("source-user", List.of(random.nextBoolean() ? "any" : "OBJ-USER-01"));
            if (random.nextInt(10) == 0) extras.put("tag", List.of("OBJ-TAG-01"));
        }
        if (random.nextInt(60) == 0) extras.put("unexpected-field", List.of("x"));
        Cell application = cp && random.nextInt(4) != 0 ? new Cell(List.of(), false) : cell(random, List.of("any", "any", "any", "ag", "app"), 0);
        return new Rule(id, "uuid-" + id, number, "OBJ-RULE-" + id, enabled, cell(random, addresses, 25), cell(random, addresses, 25),
            cell(random, services, 30), application, action, "Log", "", extras);
    }
    private static List<String> zones(Random random) {
        int pick = random.nextInt(8);
        if (pick < 3) return List.of("any");
        if (pick < 6) return List.of(ZONES.get(random.nextInt(2)));
        if (pick == 6) return List.of(ZONES.get(0), ZONES.get(1 + random.nextInt(3)));
        return random.nextBoolean() ? List.of("any", ZONES.get(2)) : List.of(ZONES.get(random.nextInt(ZONES.size())));
    }
    private static Cell cell(Random random, List<String> pool, int negateOneIn) {
        if (random.nextInt(100) == 0) return new Cell(List.of(), false);
        // Bias towards the first few entries so equal and nested selectors recur and real coverage happens.
        List<String> refs = new ArrayList<>();
        for (int i = 0, n = random.nextInt(5) == 0 ? 2 : 1; i < n; i++)
            refs.add(random.nextInt(3) == 0 ? "any" : pool.get(Math.min(pool.size() - 1, (int) Math.abs(random.nextGaussian() * pool.size() / 2.5))));
        return new Cell(refs, negateOneIn > 0 && random.nextInt(negateOneIn) == 0);
    }
    @SafeVarargs private static <T> T pick(Random random, T... values) { return values[random.nextInt(values.length)]; }
    static PolicyObject object(String id, String type, String... values) {
        return new PolicyObject(id, "OBJ-TEST-" + id, type, List.of(), List.of(values), "RESOLVED");
    }

    /**
     * A large, fully resolvable single-layer policy shaped like the estate's worst case: one placement key for Check
     * Point (every rule installed on the same target) or a handful of zone pairs for Palo Alto, mostly distinct
     * host/network/service selectors so nearly every rule must be compared against every earlier one.
     */
    static PolicySnapshot large(String vendor, int size, long seed) {
        var random = new Random(seed); boolean cp = vendor.equals("CP");
        Map<String, PolicyObject> objects = new HashMap<>();
        objects.put("any", object("any", "any"));
        List<String> addresses = new ArrayList<>();
        for (String block : BLOCKS) for (int i = 1; i < 255; i++) { objects.put(block + i, object(block + i, cp ? "host" : "address", "ipv4-address: " + block + i)); addresses.add(block + i); }
        for (String block : BLOCKS) for (int base = 0; base < 256; base += 16) {
            String key = block + base + "/28"; objects.put(key, object(key, cp ? "network" : "address", "ip-netmask: " + key)); addresses.add(key);
        }
        for (int g = 0; g < 200; g++) {
            List<String> members = new ArrayList<>();
            for (int m = 0; m < 2 + random.nextInt(20); m++) members.add(addresses.get(random.nextInt(addresses.size())));
            objects.put("grp" + g, new PolicyObject("grp" + g, "OBJ-GROUP-" + g, cp ? "group" : "address-group", members, List.of(), "RESOLVED"));
        }
        for (int g = 0; g < 200; g++) addresses.add("grp" + g);
        List<String> services = new ArrayList<>();
        for (int p = 0; p < 400; p++) { String key = "svc" + p; objects.put(key, object(key, "service-tcp", "protocol: tcp", "port: " + (1000 + p * 7))); services.add(key); }
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Map<String, List<String>> extras = new HashMap<>();
            if (cp) { extras.put("install-on", List.of("any")); extras.put("time", List.of("any")); extras.put("layer-name", List.of("LAYER-ALPHA")); }
            else { extras.put("from", List.of(ZONES.get(random.nextInt(2)))); extras.put("to", List.of(ZONES.get(2 + random.nextInt(2)))); }
            Cell application = cp ? new Cell(List.of(), false) : new Cell(List.of("any"), false);
            rules.add(new Rule("r" + i, "uuid-r" + i, i + 1, "OBJ-RULE-" + i, true,
                new Cell(List.of(addresses.get(random.nextInt(addresses.size()))), false), new Cell(List.of(addresses.get(random.nextInt(addresses.size()))), false),
                new Cell(List.of(services.get(random.nextInt(services.size()))), false), application, cp ? (i % 5 == 0 ? "Drop" : "Accept") : (i % 5 == 0 ? "deny" : "allow"),
                "Log", "", extras));
        }
        String id = "large-" + vendor + "-" + size;
        var metadata = new Metadata(id, "src-large", "MGR-BRAVO-01", vendor, "ctr-large", "DOM-TANGO-01", "POL-ALPHA-01", "2026-10-09T12:00:00Z", "a1", List.of());
        var section = cp ? new Section("sec-large", "OBJ-SECTION-01", "CP access layer", null, rules)
            : new Section(ref(id, "DG-TANGO-04", "rulebase"), "OBJ-SECTION-01", "DG-TANGO-04", null, rules);
        return new PolicySnapshot(metadata, List.of(section), objects);
    }
}
