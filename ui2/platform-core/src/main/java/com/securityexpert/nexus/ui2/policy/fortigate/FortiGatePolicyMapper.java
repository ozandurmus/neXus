package com.securityexpert.nexus.ui2.policy.fortigate;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.Address6Object;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.AddressGroup;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.AddressGroup6;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.AddressObject;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.CentralSnatRule;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.FirewallPolicy;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.Interface;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.IpPool;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.NonEvaluatedPolicy;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.PortRange;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.SdwanZone;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.ServiceGroup;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.ServiceObject;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.VdomConfig;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.VipGroup;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.VipObject;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.Zone;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.ref;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps a parsed {@link FortiGatePolicyConfig} into normalized {@link PolicySnapshot}s (one per VDOM).
 *
 * <p>Applies the cited FortiOS defaults table ({@link FortiGateDefaults}) for settings omitted by
 * top-level {@code show}. Any absent setting without a cited default for the unit's minor version
 * leaves the affected rule or object {@code UNKNOWN} (design section 4.3). Never logs configuration
 * names, addresses, or rule text (AGENTS.md raw-evidence and masking laws).
 */
public final class FortiGatePolicyMapper {

    private FortiGatePolicyMapper() {}

    /**
     * Maps all VDOMs of {@code config} in configuration order, returning one {@link PolicySnapshot} per VDOM.
     */
    public static List<PolicySnapshot> map(
            String deviceId,
            String deviceName,
            String collectedAt,
            String artefactRef,
            FortiGatePolicyConfig config) {
        List<PolicySnapshot> snapshots = new ArrayList<>(config.vdoms().size());
        for (VdomConfig vdom : config.vdoms().values()) {
            snapshots.add(mapVdom(
                    deviceId,
                    deviceName,
                    collectedAt,
                    artefactRef,
                    config.minorVersion(),
                    vdom,
                    config.globalInterfaces()));
        }
        return List.copyOf(snapshots);
    }

    /**
     * Maps a single VDOM's parsed configuration into a {@link PolicySnapshot}.
     */
    public static PolicySnapshot mapVdom(
            String deviceId,
            String deviceName,
            String collectedAt,
            String artefactRef,
            String minorVersion,
            VdomConfig vdom,
            Map<String, Interface> globalInterfaces) {
        String vdomName = vdom.name();
        String snapshotId = ref("fgt", deviceId, vdomName);
        Metadata metadata = new Metadata(
                snapshotId,
                deviceId,
                deviceName,
                "FGT",
                ref(deviceId, vdomName),
                vdomName,
                "FGT firewall policy",
                collectedAt,
                artefactRef,
                List.of(new Target(deviceId, deviceName, vdomName, "IN_SYNC")));

        // A0-FG-15 (NGFW policy-based mode) and transparent mode are refused at the VDOM level.
        if ("transparent".equals(vdom.settings().opmode())
                || "policy-based".equals(vdom.settings().ngfwMode())) {
            String modeLayer = "transparent".equals(vdom.settings().opmode())
                    ? "opmode:transparent"
                    : "ngfw-mode:policy-based";
            return new PolicySnapshot(
                    metadata,
                    List.of(),
                    Map.of(),
                    List.of(new CollectionFailure(snapshotId, "FGT_UNSUPPORTED_MODE", modeLayer, 0)));
        }

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        List<CollectionFailure> failures = new ArrayList<>();
        int[] missingDefaultCount = {0};

        boolean defaultsProven = FortiGateDefaults.isSupportedMinor(minorVersion);
        if (!defaultsProven) {
            String layer = (minorVersion == null || minorVersion.isEmpty()) ? "unknown" : minorVersion;
            failures.add(new CollectionFailure(snapshotId, "FGT_DEFAULTS_UNPROVEN", layer, 0));
        }

        // Pre-populate defined addresses, VIPs, address groups, IPv6 addresses/groups, services, service groups, and IP pools.
        for (String name : vdom.addresses().keySet()) {
            ensureAddressObject(snapshotId, name, minorVersion, vdom, globalInterfaces, objects, missingDefaultCount);
        }
        for (String name : vdom.vips().keySet()) {
            ensureVipObject(snapshotId, name, vdom, objects);
        }
        for (String name : vdom.vipGroups().keySet()) {
            ensureVipGroup(snapshotId, name, vdom, objects);
        }
        for (String name : vdom.addressGroups().keySet()) {
            ensureAddressGroup(snapshotId, name, minorVersion, vdom, globalInterfaces, objects, new HashSet<>(), missingDefaultCount);
        }
        for (String name : vdom.addresses6().keySet()) {
            ensureAddress6Object(snapshotId, name, vdom, objects);
        }
        for (String name : vdom.addressGroups6().keySet()) {
            ensureAddressGroup6(snapshotId, name, vdom, objects, new HashSet<>());
        }
        for (String name : vdom.services().keySet()) {
            ensureServiceObject(snapshotId, name, minorVersion, vdom, objects, missingDefaultCount);
        }
        for (String name : vdom.serviceGroups().keySet()) {
            ensureServiceGroup(snapshotId, name, minorVersion, vdom, objects, new HashSet<>(), missingDefaultCount);
        }
        for (String name : vdom.ipPools().keySet()) {
            ensureIpPoolObject(snapshotId, name, vdom, objects);
        }

        List<Section> sections = new ArrayList<>();

        // 1. Main firewall policy section in printed (sequence) order (A0-FG-1)
        String policySectionId = ref(snapshotId, "firewall-policy");
        List<Rule> policyRules = mapFirewallPolicies(
                deviceId,
                snapshotId,
                minorVersion,
                vdom,
                globalInterfaces,
                objects,
                missingDefaultCount);
        sections.add(new Section(
                policySectionId,
                vdomName + " policy",
                "FGT firewall policy",
                null,
                policyRules));

        // 2. Implicit section: zone intrazone allow rules (A0-FG-10) followed by implicit deny (A0-FG-3)
        sections.add(buildImplicitSection(snapshotId, minorVersion, vdom, objects, missingDefaultCount));

        // 3. VIP NAT section when VIPs or VIP groups exist (outside security evaluation, A0-FG-17)
        if (!vdom.vips().isEmpty() || !vdom.vipGroups().isEmpty()) {
            sections.add(buildVipSection(snapshotId, vdom, objects));
        }

        // 4. Central SNAT section when central-snat-map entries or central-nat enable are present (A0-FG-21)
        if (!vdom.centralSnatRules().isEmpty() || "enable".equals(vdom.settings().centralNat())) {
            sections.add(buildCentralSnatSection(snapshotId, minorVersion, vdom, globalInterfaces, objects, missingDefaultCount));
        }

        // 5. Non-evaluated policy tables (local-in, proxy, security, multicast, DoS, policy6; A0-FG-13/14/15)
        if (!vdom.nonEvaluatedPolicies().isEmpty()) {
            sections.add(buildNotEvaluatedSection(snapshotId, vdom));
        }

        // Record VDOM interfaces, zones, SD-WAN zones, and missing-default count in metadata objects.
        recordInterfacesAndZonesObjects(snapshotId, minorVersion, vdom, objects, missingDefaultCount);

        return new PolicySnapshot(metadata, sections, objects, failures);
    }

    private static List<Rule> mapFirewallPolicies(
            String deviceId,
            String snapshotId,
            String minorVersion,
            VdomConfig vdom,
            Map<String, Interface> globalInterfaces,
            Map<String, PolicyObject> objects,
            int[] missingDefaultCount) {
        List<FirewallPolicy> policies = vdom.policies();
        List<Rule> rules = new ArrayList<>(policies.size());
        Set<String> seenRuleIds = new HashSet<>();
        Cell emptyApp = new Cell(List.of(), false);

        for (int i = 0; i < policies.size(); i++) {
            FirewallPolicy pol = policies.get(i);
            int number = i + 1;
            String baseRuleId = ref(deviceId, vdom.name(), pol.policyId());
            String ruleId = seenRuleIds.add(baseRuleId)
                    ? baseRuleId
                    : ref(deviceId, vdom.name(), pol.policyId(), String.valueOf(number));
            String ruleName = (pol.name() != null && !pol.name().isEmpty()) ? pol.name() : pol.policyId();

            List<String> missingDefaults = new ArrayList<>();
            Map<String, List<String>> extras = new LinkedHashMap<>();

            // Placement: srcintf -> from, dstintf -> to (A0-FG-9)
            if (pol.srcintf().isEmpty()) {
                missingDefaults.add("srcintf");
            } else {
                extras.put("from", pol.srcintf());
            }
            if (pol.dstintf().isEmpty()) {
                missingDefaults.add("dstintf");
            } else {
                extras.put("to", pol.dstintf());
            }

            // Status -> enabled (default enable on 7.4, A0-FG-5)
            String effStatus = pol.status();
            if (effStatus == null) {
                effStatus = FortiGateDefaults.defaultValue(minorVersion, "firewall policy", "status");
                if (effStatus == null) {
                    missingDefaults.add("status");
                }
            }
            Boolean enabled = "enable".equals(effStatus)
                    ? Boolean.TRUE
                    : "disable".equals(effStatus) ? Boolean.FALSE : null;

            // Action -> accept / deny / ipsec->UNKNOWN (default deny on 7.4, A0-FG-5, A0-FG-7)
            String rawAction = pol.action();
            if (rawAction == null) {
                rawAction = FortiGateDefaults.defaultValue(minorVersion, "firewall policy", "action");
                if (rawAction == null) {
                    missingDefaults.add("action");
                }
            }
            String action;
            if ("accept".equals(rawAction) || "deny".equals(rawAction)) {
                action = rawAction;
            } else {
                action = "UNKNOWN";
                if (rawAction != null) {
                    extras.put("fgt-action", List.of(rawAction));
                }
            }
            if (pol.vpntunnel() != null && !pol.vpntunnel().isEmpty()) {
                extras.put("vpn", List.of(pol.vpntunnel()));
            }

            // Negate flags (default disable on 7.4, A0-FG-4, A0-FG-5)
            Boolean srcNeg = resolveEnableDisable(
                    pol.srcaddrNegate(), minorVersion, "firewall policy", "srcaddr-negate", missingDefaults);
            Boolean dstNeg = resolveEnableDisable(
                    pol.dstaddrNegate(), minorVersion, "firewall policy", "dstaddr-negate", missingDefaults);
            Boolean svcNeg = resolveEnableDisable(
                    pol.serviceNegate(), minorVersion, "firewall policy", "service-negate", missingDefaults);

            // Internet Service (ISDB) flags (default disable on 7.4, A0-FG-8)
            Boolean isdbDst = resolveEnableDisable(
                    pol.internetService(), minorVersion, "firewall policy", "internet-service", missingDefaults);
            Boolean isdbSrc = resolveEnableDisable(
                    pol.internetServiceSrc(), minorVersion, "firewall policy", "internet-service-src", missingDefaults);

            // Schedule: A0-FG-5 explicitly states schedule has NO default
            if (pol.schedule() == null || pol.schedule().isEmpty()) {
                missingDefaults.add("schedule");
            } else if (!"always".equals(pol.schedule())) {
                extras.put("time", List.of(pol.schedule()));
            }

            // Identity criteria: users, groups, fsso-groups -> extras.user (A0-FG-12)
            List<String> identity = new ArrayList<>();
            identity.addAll(pol.groups());
            identity.addAll(pol.users());
            identity.addAll(pol.fssoGroups());
            if (!identity.isEmpty()) {
                extras.put("user", List.copyOf(identity));
            }

            // Application / UTM criteria on profile-based policies -> extras.fgt-application
            List<String> appCriteria = new ArrayList<>();
            appCriteria.addAll(pol.applications());
            if (pol.applicationList() != null && !pol.applicationList().isEmpty()) {
                appCriteria.add("list:" + pol.applicationList());
            }
            appCriteria.addAll(pol.appCategories());
            appCriteria.addAll(pol.appGroups());
            appCriteria.addAll(pol.urlCategories());
            if (!appCriteria.isEmpty()) {
                extras.put("fgt-application", List.copyOf(appCriteria));
            }

            // Source NAT on policy (default nat=disable, ippool=disable on 7.4; A0-FG-5, A0-FG-22)
            Boolean natEnabled = resolveEnableDisable(
                    pol.nat(), minorVersion, "firewall policy", "nat", missingDefaults);
            Boolean ippoolEnabled = resolveEnableDisable(
                    pol.ippool(), minorVersion, "firewall policy", "ippool", missingDefaults);
            if (Boolean.TRUE.equals(natEnabled)) {
                extras.put("source-translation-type", List.of("dynamic-ip-and-port"));
                if (Boolean.TRUE.equals(ippoolEnabled) && !pol.poolname().isEmpty()) {
                    List<String> poolIds = new ArrayList<>(pol.poolname().size());
                    for (String pn : pol.poolname()) {
                        poolIds.add(ensureIpPoolObject(snapshotId, pn, vdom, objects));
                    }
                    extras.put("translated-source", List.copyOf(poolIds));
                } else {
                    String unknownEgressId = ensureUnknownObject(
                            snapshotId, "nat-egress-interface", "egress-interface-snat:" + pol.policyId(), "address", objects);
                    extras.put("translated-source", List.of(unknownEgressId));
                }
            }

            // match-vip and match-vip-only (A0-FG-20)
            if (pol.matchVip() != null) {
                extras.put("fgt-match-vip", List.of(pol.matchVip()));
            }
            if ("enable".equals(pol.matchVipOnly())) {
                extras.put("fgt-match-vip-only", List.of("enable"));
            }

            // Source cell
            Cell srcCell;
            if (Boolean.TRUE.equals(isdbSrc)) {
                extras.put(
                        "fgt-internet-service-src",
                        pol.internetServiceSrcNames().isEmpty() ? List.of("enabled") : pol.internetServiceSrcNames());
                String isdbSrcId = ensureDynamicObject(
                        snapshotId,
                        "isdb-src",
                        "isdb-src:" + pol.policyId(),
                        "address",
                        pol.internetServiceSrcNames(),
                        objects);
                srcCell = new Cell(List.of(isdbSrcId), Boolean.TRUE.equals(srcNeg));
            } else if (pol.srcaddr().isEmpty() && pol.srcaddr6().isEmpty()) {
                missingDefaults.add("srcaddr");
                String unkId = ensureUnknownObject(
                        snapshotId, "missing-srcaddr", "missing-srcaddr:" + pol.policyId(), "address", objects);
                srcCell = new Cell(List.of(unkId), false);
            } else if (srcNeg == null) {
                String unkId = ensureUnknownObject(
                        snapshotId, "unproven-srcaddr-negate", "unproven-srcaddr-negate:" + pol.policyId(), "address", objects);
                srcCell = new Cell(List.of(unkId), false);
            } else {
                List<String> refs = new ArrayList<>(pol.srcaddr().size() + pol.srcaddr6().size());
                for (String a : pol.srcaddr()) {
                    refs.add(resolveAddressReference(snapshotId, a, minorVersion, vdom, globalInterfaces, objects, missingDefaultCount));
                }
                for (String a6 : pol.srcaddr6()) {
                    refs.add(resolveAddress6Reference(snapshotId, a6, vdom, objects));
                }
                srcCell = new Cell(refs, srcNeg);
            }

            // Destination and Service cells (A0-FG-8: internet-service enable replaces dstaddr and service with DYNAMIC)
            Cell dstCell;
            Cell svcCell;
            if (Boolean.TRUE.equals(isdbDst)) {
                extras.put(
                        "fgt-internet-service",
                        pol.internetServiceNames().isEmpty() ? List.of("enabled") : pol.internetServiceNames());
                String isdbDstId = ensureDynamicObject(
                        snapshotId,
                        "isdb-dst",
                        "isdb-dst:" + pol.policyId(),
                        "address",
                        pol.internetServiceNames(),
                        objects);
                String isdbSvcId = ensureDynamicObject(
                        snapshotId,
                        "isdb-svc",
                        "isdb-svc:" + pol.policyId(),
                        "service",
                        pol.internetServiceNames(),
                        objects);
                dstCell = new Cell(List.of(isdbDstId), Boolean.TRUE.equals(dstNeg));
                svcCell = new Cell(List.of(isdbSvcId), Boolean.TRUE.equals(svcNeg));
            } else {
                // Destination cell
                if (pol.dstaddr().isEmpty() && pol.dstaddr6().isEmpty()) {
                    missingDefaults.add("dstaddr");
                    String unkId = ensureUnknownObject(
                            snapshotId, "missing-dstaddr", "missing-dstaddr:" + pol.policyId(), "address", objects);
                    dstCell = new Cell(List.of(unkId), false);
                } else if (dstNeg == null) {
                    String unkId = ensureUnknownObject(
                            snapshotId, "unproven-dstaddr-negate", "unproven-dstaddr-negate:" + pol.policyId(), "address", objects);
                    dstCell = new Cell(List.of(unkId), false);
                } else {
                    List<String> refs = new ArrayList<>(pol.dstaddr().size() + pol.dstaddr6().size());
                    List<String> vipRefs = new ArrayList<>();
                    for (String a : pol.dstaddr()) {
                        if (vdom.vips().containsKey(a) || vdom.vipGroups().containsKey(a)) {
                            vipRefs.add(a);
                        }
                        refs.add(resolveAddressReference(snapshotId, a, minorVersion, vdom, globalInterfaces, objects, missingDefaultCount));
                    }
                    for (String a6 : pol.dstaddr6()) {
                        refs.add(resolveAddress6Reference(snapshotId, a6, vdom, objects));
                    }
                    if (!vipRefs.isEmpty()) {
                        extras.put("fgt-vip", List.copyOf(vipRefs));
                    }
                    dstCell = new Cell(refs, dstNeg);
                }

                // Service cell
                if (pol.service().isEmpty()) {
                    missingDefaults.add("service");
                    String unkId = ensureUnknownObject(
                            snapshotId, "missing-service", "missing-service:" + pol.policyId(), "service", objects);
                    svcCell = new Cell(List.of(unkId), false);
                } else if (svcNeg == null) {
                    String unkId = ensureUnknownObject(
                            snapshotId, "unproven-service-negate", "unproven-service-negate:" + pol.policyId(), "service", objects);
                    svcCell = new Cell(List.of(unkId), false);
                } else {
                    List<String> refs = new ArrayList<>(pol.service().size());
                    for (String s : pol.service()) {
                        refs.add(resolveServiceReference(snapshotId, s, minorVersion, vdom, objects, missingDefaultCount));
                    }
                    svcCell = new Cell(refs, svcNeg);
                }
            }

            if (pol.unparsed()) {
                extras.put("fgt-unparsed", List.of("true"));
            }
            if (!missingDefaults.isEmpty()) {
                missingDefaultCount[0] += missingDefaults.size();
                extras.put("fgt-missing-default", List.copyOf(missingDefaults));
            }

            String log = pol.logtraffic() == null ? "" : pol.logtraffic();
            String comment = pol.comments() == null ? "" : pol.comments();

            rules.add(new Rule(
                    ruleId,
                    pol.uuid(),
                    number,
                    ruleName,
                    enabled,
                    srcCell,
                    dstCell,
                    svcCell,
                    emptyApp,
                    action,
                    log,
                    comment,
                    extras));
        }

        return rules;
    }

    private static Boolean resolveEnableDisable(
            String rawValue,
            String minorVersion,
            String table,
            String setting,
            List<String> missingDefaults) {
        String eff = rawValue;
        if (eff == null) {
            eff = FortiGateDefaults.defaultValue(minorVersion, table, setting);
            if (eff == null) {
                missingDefaults.add(setting);
                return null;
            }
        }
        if ("enable".equals(eff)) return Boolean.TRUE;
        if ("disable".equals(eff)) return Boolean.FALSE;
        missingDefaults.add(setting);
        return null;
    }

    private static Section buildImplicitSection(
            String snapshotId,
            String minorVersion,
            VdomConfig vdom,
            Map<String, PolicyObject> objects,
            int[] missingDefaultCount) {
        String sectionId = ref(snapshotId, "implicit", "implicit-deny");
        String anyId = ensureAnyObject(snapshotId, objects);
        Cell anyCell = new Cell(List.of(anyId), false);
        Cell emptyApp = new Cell(List.of(), false);
        List<Rule> rules = new ArrayList<>();

        // A0-FG-10: synthetic ALLOW rule for each zone with 'set intrazone allow', before the implicit deny.
        for (Zone z : vdom.zones().values()) {
            String intrazone = z.intrazone();
            if (intrazone == null) {
                intrazone = FortiGateDefaults.defaultValue(minorVersion, "system zone", "intrazone");
                if (intrazone == null) {
                    missingDefaultCount[0]++;
                }
            }
            if ("allow".equals(intrazone)) {
                int num = rules.size() + 1;
                rules.add(new Rule(
                        ref(snapshotId, "implicit", "intrazone-allow", z.name()),
                        null,
                        num,
                        z.name() + " intrazone allow",
                        Boolean.TRUE,
                        anyCell,
                        anyCell,
                        anyCell,
                        emptyApp,
                        "accept",
                        "",
                        "",
                        Map.of("from", List.of(z.name()), "to", List.of(z.name()))));
            }
        }

        // A0-FG-3: traffic matching no policy hits the implicit deny and is dropped.
        int denyNum = rules.size() + 1;
        rules.add(new Rule(
                ref(snapshotId, "implicit", "implicit-deny-rule"),
                null,
                denyNum,
                "implicit-deny",
                Boolean.TRUE,
                anyCell,
                anyCell,
                anyCell,
                emptyApp,
                "deny",
                "",
                "",
                Map.of("from", List.of("any"), "to", List.of("any"))));

        return new Section(sectionId, "FGT implicit deny", "FGT implicit", null, rules);
    }

    private static Section buildVipSection(
            String snapshotId,
            VdomConfig vdom,
            Map<String, PolicyObject> objects) {
        String sectionId = ref(snapshotId, "nat", "vip");
        String anyId = ensureAnyObject(snapshotId, objects);
        Cell anyCell = new Cell(List.of(anyId), false);
        Cell emptyApp = new Cell(List.of(), false);
        List<Rule> rules = new ArrayList<>();

        for (VipObject vip : vdom.vips().values()) {
            int num = rules.size() + 1;
            String ruleId = ref(snapshotId, "vip-rule", vip.name());
            String vipObjId = ensureVipObject(snapshotId, vip.name(), vdom, objects);

            List<String> mappedIds = new ArrayList<>(vip.mappedip().size());
            for (String mip : vip.mappedip()) {
                mappedIds.add(ensureVipMappedAddress(snapshotId, vip.name(), mip, objects));
            }

            Map<String, List<String>> extras = new LinkedHashMap<>();
            String fromIf = (vip.extintf() != null && !vip.extintf().isEmpty()) ? vip.extintf() : "any";
            extras.put("from", List.of(fromIf));
            extras.put("to", List.of("any"));
            if (!mappedIds.isEmpty()) {
                extras.put("translated-destination", List.copyOf(mappedIds));
            }
            Cell svcCell = anyCell;
            if ("enable".equals(vip.portforward()) && vip.extport() != null && !vip.extport().isEmpty()) {
                String proto = (vip.protocol() == null || vip.protocol().isEmpty()) ? "tcp" : vip.protocol();
                String extSvcId = ensurePortForwardService(snapshotId, vip.name() + ":extport", proto, vip.extport(), objects);
                svcCell = new Cell(List.of(extSvcId), false);
                if (vip.mappedport() != null && !vip.mappedport().isEmpty()) {
                    extras.put("translated-destination-port", List.of(vip.mappedport()));
                }
            }

            rules.add(new Rule(
                    ruleId,
                    vip.uuid(),
                    num,
                    vip.name(),
                    Boolean.TRUE,
                    anyCell,
                    new Cell(List.of(vipObjId), false),
                    svcCell,
                    emptyApp,
                    "destination-nat",
                    "",
                    vip.comment() == null ? "" : vip.comment(),
                    extras));
        }

        return new Section(sectionId, "FGT VIP", "FGT NAT", null, rules);
    }

    private static Section buildCentralSnatSection(
            String snapshotId,
            String minorVersion,
            VdomConfig vdom,
            Map<String, Interface> globalInterfaces,
            Map<String, PolicyObject> objects,
            int[] missingDefaultCount) {
        String sectionId = ref(snapshotId, "nat", "central-snat");
        String anyId = ensureAnyObject(snapshotId, objects);
        Cell anyCell = new Cell(List.of(anyId), false);
        Cell emptyApp = new Cell(List.of(), false);
        List<Rule> rules = new ArrayList<>();

        for (CentralSnatRule sr : vdom.centralSnatRules()) {
            int num = rules.size() + 1;
            String ruleId = ref(snapshotId, "central-snat-rule", sr.ruleId());
            Boolean enabled = "disable".equals(sr.status()) ? Boolean.FALSE : Boolean.TRUE;

            List<String> srcRefs = new ArrayList<>();
            for (String a : sr.origAddr()) {
                srcRefs.add(resolveAddressReference(snapshotId, a, minorVersion, vdom, globalInterfaces, objects, missingDefaultCount));
            }
            List<String> dstRefs = new ArrayList<>();
            for (String a : sr.dstAddr()) {
                dstRefs.add(resolveAddressReference(snapshotId, a, minorVersion, vdom, globalInterfaces, objects, missingDefaultCount));
            }

            Map<String, List<String>> extras = new LinkedHashMap<>();
            if (!sr.srcintf().isEmpty()) extras.put("from", sr.srcintf());
            if (!sr.dstintf().isEmpty()) extras.put("to", sr.dstintf());
            extras.put("fgt-central-snat", List.of("UNSUPPORTED"));
            if (!sr.natIppool().isEmpty()) {
                List<String> poolIds = new ArrayList<>(sr.natIppool().size());
                for (String pn : sr.natIppool()) {
                    poolIds.add(ensureIpPoolObject(snapshotId, pn, vdom, objects));
                }
                extras.put("translated-source", List.copyOf(poolIds));
            }

            rules.add(new Rule(
                    ruleId,
                    sr.uuid(),
                    num,
                    "central-snat:" + sr.ruleId(),
                    enabled,
                    srcRefs.isEmpty() ? anyCell : new Cell(srcRefs, false),
                    dstRefs.isEmpty() ? anyCell : new Cell(dstRefs, false),
                    anyCell,
                    emptyApp,
                    "source-nat",
                    "",
                    sr.comments() == null ? "" : sr.comments(),
                    extras));
        }

        return new Section(sectionId, "FGT central SNAT", "FGT NAT", null, rules);
    }

    private static Section buildNotEvaluatedSection(String snapshotId, VdomConfig vdom) {
        String sectionId = ref(snapshotId, "not-evaluated");
        List<Rule> rules = new ArrayList<>(vdom.nonEvaluatedPolicies().size());
        Cell emptyCell = new Cell(List.of(), false);

        for (int i = 0; i < vdom.nonEvaluatedPolicies().size(); i++) {
            NonEvaluatedPolicy nep = vdom.nonEvaluatedPolicies().get(i);
            int num = i + 1;
            String ruleId = ref(snapshotId, "not-evaluated", nep.table(), nep.policyId(), String.valueOf(num));
            String ruleName = (nep.name() != null && !nep.name().isEmpty())
                    ? nep.table() + ":" + nep.name()
                    : nep.table() + ":" + nep.policyId();
            Map<String, List<String>> extras = new LinkedHashMap<>();
            extras.put("fgt-policy-table", List.of(nep.table()));
            extras.put("fgt-not-evaluated-reason", List.of(notEvaluatedReason(nep.table())));

            rules.add(new Rule(
                    ruleId,
                    nep.uuid(),
                    num,
                    ruleName,
                    null,
                    emptyCell,
                    emptyCell,
                    emptyCell,
                    emptyCell,
                    "UNKNOWN",
                    "",
                    nep.comments() == null ? "" : nep.comments(),
                    extras));
        }

        return new Section(sectionId, "FGT not evaluated", "FGT not evaluated", null, rules);
    }

    private static String notEvaluatedReason(String table) {
        return switch (table) {
            case "firewall local-in-policy", "firewall local-in-policy6" -> "control-plane local-in policy (A0-FG-13)";
            case "firewall proxy-policy" -> "proxy policy out of scope (A0-FG-14)";
            case "firewall security-policy" -> "NGFW policy-based security policy (A0-FG-15)";
            case "firewall multicast-policy", "firewall multicast-policy6" -> "multicast policy out of scope";
            case "firewall dos-policy", "firewall dos-policy6" -> "DoS anomaly policy out of scope";
            default -> "non-transit or legacy policy table out of scope";
        };
    }

    private static void recordInterfacesAndZonesObjects(
            String snapshotId,
            String minorVersion,
            VdomConfig vdom,
            Map<String, PolicyObject> objects,
            int[] missingDefaultCount) {
        for (Zone z : vdom.zones().values()) {
            String zId = ref(snapshotId, "zone", z.name());
            String intrazone = z.intrazone();
            boolean proven = true;
            if (intrazone == null) {
                intrazone = FortiGateDefaults.defaultValue(minorVersion, "system zone", "intrazone");
                if (intrazone == null) {
                    intrazone = "UNKNOWN";
                    proven = false;
                }
            }
            List<String> values = new ArrayList<>();
            values.add("intrazone: " + intrazone);
            for (String iface : z.interfaces()) {
                values.add("interface: " + iface);
            }
            objects.put(zId, new PolicyObject(
                    zId,
                    z.name(),
                    "zone",
                    List.of(),
                    values,
                    proven ? "RESOLVED" : "UNKNOWN"));
        }

        for (SdwanZone sz : vdom.sdwanZones().values()) {
            String szId = ref(snapshotId, "sdwan-zone", sz.name());
            List<String> values = new ArrayList<>();
            for (String iface : sz.interfaces()) {
                values.add("interface: " + iface);
            }
            objects.put(szId, new PolicyObject(
                    szId,
                    sz.name(),
                    "sdwan-zone",
                    List.of(),
                    values,
                    "RESOLVED"));
        }

        String ifObjId = ref(snapshotId, "fgt-interfaces", "interfaces");
        List<String> ifValues = new ArrayList<>();
        for (Interface iface : vdom.interfaces().values()) {
            ifValues.add("interface: " + iface.name());
            if (iface.ip() != null && iface.mask() != null) {
                Integer p = contiguousMaskPrefix(iface.mask());
                if (p != null) {
                    ifValues.add("ip-netmask: " + iface.ip() + "/" + p);
                }
            }
        }
        ifValues.add("missing-defaults: " + missingDefaultCount[0]);
        objects.put(ifObjId, new PolicyObject(
                ifObjId,
                "fgt-interfaces",
                "fgt-interfaces",
                List.of(),
                ifValues,
                "RESOLVED"));
    }

    private static String resolveAddressReference(
            String snapshotId,
            String name,
            String minorVersion,
            VdomConfig vdom,
            Map<String, Interface> globalInterfaces,
            Map<String, PolicyObject> objects,
            int[] missingDefaultCount) {
        // A0-FG-25: 'none' is always unresolved; 'all' is resolved only through its stored definition, never by name.
        if ("none".equals(name)) {
            return ensureUnresolvedObject(snapshotId, "address", name, objects);
        }
        if (vdom.addresses().containsKey(name)) {
            return ensureAddressObject(snapshotId, name, minorVersion, vdom, globalInterfaces, objects, missingDefaultCount);
        }
        if (vdom.addressGroups().containsKey(name)) {
            return ensureAddressGroup(snapshotId, name, minorVersion, vdom, globalInterfaces, objects, new HashSet<>(), missingDefaultCount);
        }
        if (vdom.vips().containsKey(name)) {
            return ensureVipObject(snapshotId, name, vdom, objects);
        }
        if (vdom.vipGroups().containsKey(name)) {
            return ensureVipGroup(snapshotId, name, vdom, objects);
        }
        return ensureUnresolvedObject(snapshotId, "address", name, objects);
    }

    private static String resolveAddress6Reference(
            String snapshotId,
            String name,
            VdomConfig vdom,
            Map<String, PolicyObject> objects) {
        if (vdom.addresses6().containsKey(name)) {
            return ensureAddress6Object(snapshotId, name, vdom, objects);
        }
        if (vdom.addressGroups6().containsKey(name)) {
            return ensureAddressGroup6(snapshotId, name, vdom, objects, new HashSet<>());
        }
        return ensureUnresolvedObject(snapshotId, "address6", name, objects);
    }

    private static String ensureAddressObject(
            String snapshotId,
            String name,
            String minorVersion,
            VdomConfig vdom,
            Map<String, Interface> globalInterfaces,
            Map<String, PolicyObject> objects,
            int[] missingDefaultCount) {
        if ("none".equals(name)) {
            return ensureUnresolvedObject(snapshotId, "address", name, objects);
        }
        String id = ref(snapshotId, "address", name);
        if (objects.containsKey(id)) return id;

        AddressObject addr = vdom.addresses().get(name);
        if (addr == null) {
            return ensureUnresolvedObject(snapshotId, "address", name, objects);
        }
        if (addr.unparsed()) {
            objects.put(id, new PolicyObject(id, name, "address", List.of(), List.of(), "UNSUPPORTED"));
            return id;
        }

        // A0-FG-24: type defaults to ipmask on FortiOS 7.4.
        String effType = addr.type();
        if (effType == null) {
            effType = FortiGateDefaults.defaultValue(minorVersion, "firewall address", "type");
            if (effType == null) {
                missingDefaultCount[0]++;
                objects.put(id, new PolicyObject(id, name, "address", List.of(), List.of(), "UNKNOWN"));
                return id;
            }
        }

        PolicyObject built = switch (effType) {
            case "ipmask" -> buildIpmaskAddress(id, name, addr, minorVersion, missingDefaultCount);
            case "iprange" -> buildIprangeAddress(id, name, addr, missingDefaultCount);
            // A0-FG-23: fqdn, geography, dynamic, route-tag -> DYNAMIC.
            case "fqdn", "geography", "dynamic", "route-tag" ->
                    new PolicyObject(id, name, "address", List.of(), List.of(), "DYNAMIC");
            // A0-FG-23: wildcard (non-contiguous) and mac -> UNSUPPORTED.
            case "wildcard", "mac", "wildcard-fqdn" ->
                    new PolicyObject(id, name, "address", List.of(), List.of(), "UNSUPPORTED");
            // A0-FG-23: interface-subnet resolved from the interface's configured address.
            case "interface-subnet" ->
                    buildInterfaceSubnetAddress(id, name, addr, vdom, globalInterfaces, missingDefaultCount);
            default -> new PolicyObject(id, name, "address", List.of(), List.of(), "UNSUPPORTED");
        };

        objects.put(id, built);
        return id;
    }

    private static PolicyObject buildIpmaskAddress(
            String id,
            String name,
            AddressObject addr,
            String minorVersion,
            int[] missingDefaultCount) {
        String ip = addr.subnetIp();
        String mask = addr.subnetMask();
        if (ip == null || mask == null) {
            // A0-FG-24: subnet defaults to "0.0.0.0 0.0.0.0" on FortiOS 7.4.
            String defSubnet = FortiGateDefaults.defaultValue(minorVersion, "firewall address", "subnet");
            if (defSubnet == null) {
                missingDefaultCount[0]++;
                return new PolicyObject(id, name, "address", List.of(), List.of(), "UNKNOWN");
            }
            String[] parts = defSubnet.split("\\s+");
            ip = parts[0];
            mask = parts[1];
        }
        if (!FortiGateConfigPolicyParser.isValidIpv4(ip) || !FortiGateConfigPolicyParser.isValidIpv4(mask)) {
            return new PolicyObject(id, name, "network", List.of(), List.of(), "UNSUPPORTED");
        }
        Integer prefix = contiguousMaskPrefix(mask);
        if (prefix == null) {
            return new PolicyObject(id, name, "network", List.of(), List.of(), "UNSUPPORTED");
        }
        String objType = prefix == 32 ? "host" : "network";
        return new PolicyObject(id, name, objType, List.of(), List.of("ip-netmask: " + ip + "/" + prefix), "RESOLVED");
    }

    private static PolicyObject buildIprangeAddress(
            String id,
            String name,
            AddressObject addr,
            int[] missingDefaultCount) {
        if (addr.startIp() == null || addr.endIp() == null) {
            missingDefaultCount[0]++;
            return new PolicyObject(id, name, "address-range", List.of(), List.of(), "UNKNOWN");
        }
        Long v1 = ipv4ToLong(addr.startIp());
        Long v2 = ipv4ToLong(addr.endIp());
        if (v1 == null || v2 == null || v1 > v2) {
            return new PolicyObject(id, name, "address-range", List.of(), List.of(), "UNSUPPORTED");
        }
        return new PolicyObject(
                id,
                name,
                "address-range",
                List.of(),
                List.of("ip-range: " + addr.startIp() + "-" + addr.endIp()),
                "RESOLVED");
    }

    private static PolicyObject buildInterfaceSubnetAddress(
            String id,
            String name,
            AddressObject addr,
            VdomConfig vdom,
            Map<String, Interface> globalInterfaces,
            int[] missingDefaultCount) {
        if (addr.subnetIp() != null && addr.subnetMask() != null
                && FortiGateConfigPolicyParser.isValidIpv4(addr.subnetIp())
                && FortiGateConfigPolicyParser.isValidIpv4(addr.subnetMask())) {
            Integer prefix = contiguousMaskPrefix(addr.subnetMask());
            if (prefix != null) {
                return new PolicyObject(
                        id, name, "network", List.of(), List.of("ip-netmask: " + addr.subnetIp() + "/" + prefix), "RESOLVED");
            }
        }
        String ifName = addr.interfaceName();
        if (ifName == null || ifName.isEmpty()) {
            missingDefaultCount[0]++;
            return new PolicyObject(id, name, "address", List.of(), List.of(), "UNKNOWN");
        }
        Interface iface = vdom.interfaces().get(ifName);
        if (iface == null && globalInterfaces != null) {
            iface = globalInterfaces.get(ifName);
        }
        if (iface == null || iface.ip() == null || iface.mask() == null) {
            return new PolicyObject(id, name, "address", List.of(), List.of(), "UNKNOWN");
        }
        if (!FortiGateConfigPolicyParser.isValidIpv4(iface.ip()) || !FortiGateConfigPolicyParser.isValidIpv4(iface.mask())) {
            return new PolicyObject(id, name, "address", List.of(), List.of(), "UNSUPPORTED");
        }
        Integer prefix = contiguousMaskPrefix(iface.mask());
        if (prefix == null) {
            return new PolicyObject(id, name, "address", List.of(), List.of(), "UNSUPPORTED");
        }
        return new PolicyObject(
                id, name, "network", List.of(), List.of("ip-netmask: " + iface.ip() + "/" + prefix), "RESOLVED");
    }

    private static String ensureAddressGroup(
            String snapshotId,
            String name,
            String minorVersion,
            VdomConfig vdom,
            Map<String, Interface> globalInterfaces,
            Map<String, PolicyObject> objects,
            Set<String> path,
            int[] missingDefaultCount) {
        String id = ref(snapshotId, "address", name);
        if (objects.containsKey(id)) return id;

        AddressGroup grp = vdom.addressGroups().get(name);
        if (grp == null) {
            return ensureUnresolvedObject(snapshotId, "address", name, objects);
        }
        if ("enable".equals(grp.exclude()) || !grp.excludeMembers().isEmpty()) {
            objects.put(id, new PolicyObject(id, name, "address-group", List.of(), List.of(), "UNSUPPORTED"));
            return id;
        }
        if (!path.add(name) || path.size() > 32 || grp.members().isEmpty()) {
            objects.put(id, new PolicyObject(id, name, "address-group", List.of(), List.of(), "UNRESOLVED"));
            return id;
        }

        List<String> memberIds = new ArrayList<>(grp.members().size());
        for (String member : grp.members()) {
            if ("none".equals(member)) {
                memberIds.add(ensureUnresolvedObject(snapshotId, "address", member, objects));
            } else if (vdom.addresses().containsKey(member)) {
                memberIds.add(ensureAddressObject(snapshotId, member, minorVersion, vdom, globalInterfaces, objects, missingDefaultCount));
            } else if (vdom.addressGroups().containsKey(member)) {
                memberIds.add(ensureAddressGroup(snapshotId, member, minorVersion, vdom, globalInterfaces, objects, path, missingDefaultCount));
            } else if (vdom.vips().containsKey(member)) {
                memberIds.add(ensureVipObject(snapshotId, member, vdom, objects));
            } else if (vdom.vipGroups().containsKey(member)) {
                memberIds.add(ensureVipGroup(snapshotId, member, vdom, objects));
            } else {
                memberIds.add(ensureUnresolvedObject(snapshotId, "address", member, objects));
            }
        }
        path.remove(name);
        objects.put(id, new PolicyObject(id, name, "address-group", memberIds, List.of(), "RESOLVED"));
        return id;
    }

    private static String ensureAddress6Object(
            String snapshotId,
            String name,
            VdomConfig vdom,
            Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, "address6", name);
        if (objects.containsKey(id)) return id;
        Address6Object a6 = vdom.addresses6().get(name);
        if (a6 == null) {
            return ensureUnresolvedObject(snapshotId, "address6", name, objects);
        }
        // A0-FG-16: IPv6 address objects stay UNSUPPORTED in the IPv4 engine.
        objects.put(id, new PolicyObject(id, name, "address", List.of(), List.of(), "UNSUPPORTED"));
        return id;
    }

    private static String ensureAddressGroup6(
            String snapshotId,
            String name,
            VdomConfig vdom,
            Map<String, PolicyObject> objects,
            Set<String> path) {
        String id = ref(snapshotId, "address6", name);
        if (objects.containsKey(id)) return id;
        AddressGroup6 g6 = vdom.addressGroups6().get(name);
        if (g6 == null || !path.add(name) || path.size() > 32 || g6.members().isEmpty()) {
            objects.put(id, new PolicyObject(id, name, "address-group", List.of(), List.of(), "UNRESOLVED"));
            return id;
        }
        List<String> memberIds = new ArrayList<>(g6.members().size());
        for (String m : g6.members()) {
            if (vdom.addresses6().containsKey(m)) {
                memberIds.add(ensureAddress6Object(snapshotId, m, vdom, objects));
            } else if (vdom.addressGroups6().containsKey(m)) {
                memberIds.add(ensureAddressGroup6(snapshotId, m, vdom, objects, path));
            } else {
                memberIds.add(ensureUnresolvedObject(snapshotId, "address6", m, objects));
            }
        }
        path.remove(name);
        objects.put(id, new PolicyObject(id, name, "address-group", memberIds, List.of(), "UNSUPPORTED"));
        return id;
    }

    private static String ensureVipObject(
            String snapshotId,
            String name,
            VdomConfig vdom,
            Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, "address", name);
        if (objects.containsKey(id)) return id;
        VipObject vip = vdom.vips().get(name);
        if (vip == null) {
            return ensureUnresolvedObject(snapshotId, "address", name, objects);
        }
        if (vip.unparsed() || (vip.type() != null && !"static-nat".equals(vip.type()))) {
            String status = "fqdn".equals(vip.type()) ? "DYNAMIC" : "UNSUPPORTED";
            objects.put(id, new PolicyObject(id, name, "address", List.of(), List.of(), status));
            return id;
        }

        // Section 5.3 / A0-FG-19: in the address dictionary the VIP object resolves to extip with a vip-mapped value kept.
        String extVal = formatIpOrRangeValue(vip.extip());
        if (extVal == null) {
            objects.put(id, new PolicyObject(id, name, "address", List.of(), List.of(), "UNSUPPORTED"));
            return id;
        }
        List<String> values = new ArrayList<>();
        values.add(extVal);
        values.add("vip-mapped: " + String.join(",", vip.mappedip()));
        if ("enable".equals(vip.portforward()) && vip.extport() != null && !vip.extport().isEmpty()) {
            values.add("vip-extport: " + vip.extport());
            if (vip.mappedport() != null && !vip.mappedport().isEmpty()) {
                values.add("vip-mappedport: " + vip.mappedport());
            }
        }
        objects.put(id, new PolicyObject(id, name, "address", List.of(), List.copyOf(values), "RESOLVED"));
        return id;
    }

    private static String ensureVipGroup(
            String snapshotId,
            String name,
            VdomConfig vdom,
            Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, "address", name);
        if (objects.containsKey(id)) return id;
        VipGroup vg = vdom.vipGroups().get(name);
        if (vg == null || vg.members().isEmpty()) {
            objects.put(id, new PolicyObject(id, name, "address-group", List.of(), List.of(), "UNRESOLVED"));
            return id;
        }
        List<String> memberIds = new ArrayList<>(vg.members().size());
        for (String m : vg.members()) {
            if (vdom.vips().containsKey(m)) {
                memberIds.add(ensureVipObject(snapshotId, m, vdom, objects));
            } else {
                memberIds.add(ensureUnresolvedObject(snapshotId, "address", m, objects));
            }
        }
        objects.put(id, new PolicyObject(id, name, "address-group", memberIds, List.of(), "RESOLVED"));
        return id;
    }

    private static String ensureVipMappedAddress(
            String snapshotId,
            String vipName,
            String mappedIpOrRange,
            Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, "vip-mapped", vipName, mappedIpOrRange);
        if (objects.containsKey(id)) return id;
        String val = formatIpOrRangeValue(mappedIpOrRange);
        if (val == null) {
            objects.put(id, new PolicyObject(id, mappedIpOrRange, "address", List.of(), List.of(), "UNSUPPORTED"));
        } else {
            objects.put(id, new PolicyObject(id, mappedIpOrRange, "address", List.of(), List.of(val), "RESOLVED"));
        }
        return id;
    }

    private static String formatIpOrRangeValue(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        int dash = raw.indexOf('-');
        if (dash < 0) {
            return FortiGateConfigPolicyParser.isValidIpv4(raw) ? "ip-netmask: " + raw + "/32" : null;
        }
        String ip1 = raw.substring(0, dash);
        String ip2 = raw.substring(dash + 1);
        Long v1 = ipv4ToLong(ip1);
        Long v2 = ipv4ToLong(ip2);
        if (v1 == null || v2 == null || v1 > v2) return null;
        if (v1.equals(v2)) return "ip-netmask: " + ip1 + "/32";
        return "ip-range: " + ip1 + "-" + ip2;
    }

    private static String ensureIpPoolObject(
            String snapshotId,
            String name,
            VdomConfig vdom,
            Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, "ippool", name);
        if (objects.containsKey(id)) return id;
        IpPool pool = vdom.ipPools().get(name);
        if (pool == null) {
            return ensureUnresolvedObject(snapshotId, "ippool", name, objects);
        }
        if (pool.unparsed()) {
            objects.put(id, new PolicyObject(id, name, "address-range", List.of(), List.of(), "UNSUPPORTED"));
            return id;
        }
        Long v1 = ipv4ToLong(pool.startip());
        Long v2 = ipv4ToLong(pool.endip());
        if (v1 == null || v2 == null || v1 > v2) {
            objects.put(id, new PolicyObject(id, name, "address-range", List.of(), List.of(), "UNSUPPORTED"));
            return id;
        }
        String val = v1.equals(v2)
                ? "ip-netmask: " + pool.startip() + "/32"
                : "ip-range: " + pool.startip() + "-" + pool.endip();
        objects.put(id, new PolicyObject(id, name, "address-range", List.of(), List.of(val), "RESOLVED"));
        return id;
    }

    private static String resolveServiceReference(
            String snapshotId,
            String name,
            String minorVersion,
            VdomConfig vdom,
            Map<String, PolicyObject> objects,
            int[] missingDefaultCount) {
        if (vdom.services().containsKey(name)) {
            return ensureServiceObject(snapshotId, name, minorVersion, vdom, objects, missingDefaultCount);
        }
        if (vdom.serviceGroups().containsKey(name)) {
            return ensureServiceGroup(snapshotId, name, minorVersion, vdom, objects, new HashSet<>(), missingDefaultCount);
        }
        // A0-FG-27: a referenced service with no stored definition -> UNRESOLVED.
        return ensureUnresolvedObject(snapshotId, "service", name, objects);
    }

    private static String ensureServiceObject(
            String snapshotId,
            String name,
            String minorVersion,
            VdomConfig vdom,
            Map<String, PolicyObject> objects,
            int[] missingDefaultCount) {
        String id = ref(snapshotId, "service", name);
        if (objects.containsKey(id)) return id;

        ServiceObject svc = vdom.services().get(name);
        if (svc == null) {
            return ensureUnresolvedObject(snapshotId, "service", name, objects);
        }
        if (svc.unparsed() || svc.hasAddressRestriction()) {
            objects.put(id, new PolicyObject(id, name, "service", List.of(), List.of(), "UNSUPPORTED"));
            return id;
        }

        // A0-FG-26: protocol defaults to TCP/UDP/SCTP on FortiOS 7.4.
        String effProto = svc.protocol();
        if (effProto == null) {
            effProto = FortiGateDefaults.defaultValue(minorVersion, "firewall service custom", "protocol");
            if (effProto == null) {
                missingDefaultCount[0]++;
                objects.put(id, new PolicyObject(id, name, "service", List.of(), List.of(), "UNKNOWN"));
                return id;
            }
        }

        switch (effProto) {
            case "ALL" -> {
                objects.put(id, new PolicyObject(id, name, "any", List.of(), List.of(), "RESOLVED"));
            }
            case "TCP/UDP/SCTP" -> {
                if (!svc.sctpRawRanges().isEmpty()) {
                    objects.put(id, new PolicyObject(id, name, "service", List.of(), List.of(), "UNSUPPORTED"));
                    return id;
                }
                if (svc.tcpRanges().isEmpty() && svc.udpRanges().isEmpty()) {
                    missingDefaultCount[0]++;
                    objects.put(id, new PolicyObject(id, name, "service", List.of(), List.of(), "UNKNOWN"));
                    return id;
                }
                buildTcpUdpServiceObject(snapshotId, id, name, svc.tcpRanges(), svc.udpRanges(), objects);
            }
            case "ICMP", "ICMP6" -> {
                String protoLower = "ICMP".equals(effProto) ? "icmp" : "icmp6";
                List<String> values = new ArrayList<>();
                values.add("protocol: " + protoLower);
                if (svc.icmpType() != null && !svc.icmpType().isEmpty()) {
                    values.add("icmp-type: " + svc.icmpType());
                    if (svc.icmpCode() != null && !svc.icmpCode().isEmpty()) {
                        values.add("icmp-code: " + svc.icmpCode());
                    }
                }
                objects.put(id, new PolicyObject(id, name, "service", List.of(), List.copyOf(values), "RESOLVED"));
            }
            case "IP" -> {
                if (svc.protocolNumber() == null || svc.protocolNumber().isEmpty()) {
                    missingDefaultCount[0]++;
                    objects.put(id, new PolicyObject(id, name, "service", List.of(), List.of("protocol: ip"), "UNKNOWN"));
                } else {
                    objects.put(id, new PolicyObject(
                            id,
                            name,
                            "service",
                            List.of(),
                            List.of("protocol: ip", "protocol-number: " + svc.protocolNumber()),
                            "UNSUPPORTED"));
                }
            }
            default -> {
                objects.put(id, new PolicyObject(id, name, "service", List.of(), List.of(), "UNSUPPORTED"));
            }
        }
        return id;
    }

    private static void buildTcpUdpServiceObject(
            String snapshotId,
            String id,
            String name,
            List<PortRange> tcpRanges,
            List<PortRange> udpRanges,
            Map<String, PolicyObject> objects) {
        boolean tcpNeedsSplit = tcpRanges.size() > 1 && tcpRanges.stream().anyMatch(r -> r.srcStart() != null);
        boolean udpNeedsSplit = udpRanges.size() > 1 && udpRanges.stream().anyMatch(r -> r.srcStart() != null);

        if (!tcpNeedsSplit && !udpNeedsSplit) {
            List<String> values = new ArrayList<>();
            if (!tcpRanges.isEmpty()) {
                PortRange first = tcpRanges.get(0);
                if (first.srcStart() != null) {
                    values.add("protocol/tcp/source-port: " + formatRangeBounds(first.srcStart(), first.srcEnd()));
                }
                values.add("protocol/tcp/port: " + joinDstRanges(tcpRanges));
            }
            if (!udpRanges.isEmpty()) {
                PortRange first = udpRanges.get(0);
                if (first.srcStart() != null) {
                    values.add("protocol/udp/source-port: " + formatRangeBounds(first.srcStart(), first.srcEnd()));
                }
                values.add("protocol/udp/port: " + joinDstRanges(udpRanges));
            }
            objects.put(id, new PolicyObject(id, name, "service", List.of(), List.copyOf(values), "RESOLVED"));
            return;
        }

        // Multiple ranges with source-port constraints: emit one synthetic service object per (proto, range) pair
        // and group them into a service-group so each (src, dst) pair retains its exact product without cross-mixing.
        List<String> memberIds = new ArrayList<>(tcpRanges.size() + udpRanges.size());
        int idx = 0;
        for (PortRange pr : tcpRanges) {
            String subName = name + ":tcp:" + (++idx);
            String subId = ref(snapshotId, "service-part", subName);
            List<String> vals = new ArrayList<>(2);
            if (pr.srcStart() != null) {
                vals.add("protocol/tcp/source-port: " + formatRangeBounds(pr.srcStart(), pr.srcEnd()));
            }
            vals.add("protocol/tcp/port: " + formatRangeBounds(pr.dstStart(), pr.dstEnd()));
            objects.put(subId, new PolicyObject(subId, subName, "service", List.of(), List.copyOf(vals), "RESOLVED"));
            memberIds.add(subId);
        }
        for (PortRange pr : udpRanges) {
            String subName = name + ":udp:" + (++idx);
            String subId = ref(snapshotId, "service-part", subName);
            List<String> vals = new ArrayList<>(2);
            if (pr.srcStart() != null) {
                vals.add("protocol/udp/source-port: " + formatRangeBounds(pr.srcStart(), pr.srcEnd()));
            }
            vals.add("protocol/udp/port: " + formatRangeBounds(pr.dstStart(), pr.dstEnd()));
            objects.put(subId, new PolicyObject(subId, subName, "service", List.of(), List.copyOf(vals), "RESOLVED"));
            memberIds.add(subId);
        }
        objects.put(id, new PolicyObject(id, name, "service-group", List.copyOf(memberIds), List.of(), "RESOLVED"));
    }

    private static String joinDstRanges(List<PortRange> ranges) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) sb.append(',');
            PortRange r = ranges.get(i);
            sb.append(formatRangeBounds(r.dstStart(), r.dstEnd()));
        }
        return sb.toString();
    }

    private static String formatRangeBounds(int start, int end) {
        return start == end ? String.valueOf(start) : start + "-" + end;
    }

    private static String ensurePortForwardService(
            String snapshotId,
            String name,
            String protocol,
            String rawPortRange,
            Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, "vip-service", name);
        if (objects.containsKey(id)) return id;
        PortRange pr = FortiGateConfigPolicyParser.parsePortRangeToken(rawPortRange);
        String protoLower = protocol.toLowerCase(java.util.Locale.ROOT);
        if (pr == null || pr.srcStart() != null || !("tcp".equals(protoLower) || "udp".equals(protoLower))) {
            objects.put(id, new PolicyObject(id, name, "service", List.of(), List.of(), "UNSUPPORTED"));
            return id;
        }
        String rangeStr = formatRangeBounds(pr.dstStart(), pr.dstEnd());
        objects.put(id, new PolicyObject(
                id,
                name,
                "service",
                List.of(),
                List.of("protocol/" + protoLower + "/port: " + rangeStr),
                "RESOLVED"));
        return id;
    }

    private static String ensureServiceGroup(
            String snapshotId,
            String name,
            String minorVersion,
            VdomConfig vdom,
            Map<String, PolicyObject> objects,
            Set<String> path,
            int[] missingDefaultCount) {
        String id = ref(snapshotId, "service", name);
        if (objects.containsKey(id)) return id;

        ServiceGroup sg = vdom.serviceGroups().get(name);
        if (sg == null || !path.add(name) || path.size() > 32 || sg.members().isEmpty()) {
            objects.put(id, new PolicyObject(id, name, "service-group", List.of(), List.of(), "UNRESOLVED"));
            return id;
        }

        List<String> memberIds = new ArrayList<>(sg.members().size());
        for (String member : sg.members()) {
            if (vdom.services().containsKey(member)) {
                memberIds.add(ensureServiceObject(snapshotId, member, minorVersion, vdom, objects, missingDefaultCount));
            } else if (vdom.serviceGroups().containsKey(member)) {
                memberIds.add(ensureServiceGroup(snapshotId, member, minorVersion, vdom, objects, path, missingDefaultCount));
            } else {
                memberIds.add(ensureUnresolvedObject(snapshotId, "service", member, objects));
            }
        }
        path.remove(name);
        objects.put(id, new PolicyObject(id, name, "service-group", memberIds, List.of(), "RESOLVED"));
        return id;
    }

    private static String ensureAnyObject(String snapshotId, Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, "any", "any");
        objects.putIfAbsent(id, new PolicyObject(id, "ANY", "any", List.of(), List.of(), "RESOLVED"));
        return id;
    }

    private static String ensureUnresolvedObject(
            String snapshotId, String kind, String name, Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, kind, name);
        objects.putIfAbsent(id, new PolicyObject(id, name, "unresolved", List.of(), List.of(), "UNRESOLVED"));
        return id;
    }

    private static String ensureUnknownObject(
            String snapshotId, String kind, String name, String objType, Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, kind, name);
        objects.putIfAbsent(id, new PolicyObject(id, name, objType, List.of(), List.of(), "UNKNOWN"));
        return id;
    }

    private static String ensureDynamicObject(
            String snapshotId,
            String kind,
            String name,
            String objType,
            List<String> rawValues,
            Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, kind, name);
        if (objects.containsKey(id)) return id;
        List<String> values = new ArrayList<>(rawValues.size());
        for (String v : rawValues) {
            values.add("isdb: " + v);
        }
        objects.put(id, new PolicyObject(id, name, objType, List.of(), List.copyOf(values), "DYNAMIC"));
        return id;
    }

    static Long ipv4ToLong(String ip) {
        if (!FortiGateConfigPolicyParser.isValidIpv4(ip)) return null;
        long result = 0;
        int val = 0;
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            if (c == '.') {
                result = (result << 8) | (val & 0xFFL);
                val = 0;
            } else {
                val = val * 10 + (c - '0');
            }
        }
        return (result << 8) | (val & 0xFFL);
    }

    static Integer contiguousMaskPrefix(String mask) {
        Long v = ipv4ToLong(mask);
        if (v == null) return null;
        long inv = (~v) & 0xFFFFFFFFL;
        if (((inv + 1) & inv) != 0) {
            return null;
        }
        return Long.bitCount(v);
    }
}
