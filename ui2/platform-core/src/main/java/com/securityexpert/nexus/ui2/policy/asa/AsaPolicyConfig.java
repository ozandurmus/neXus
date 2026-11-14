package com.securityexpert.nexus.ui2.policy.asa;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable model of a Cisco ASA configuration's policy constructs in configuration order.
 */
public record AsaPolicyConfig(
        String version,
        boolean multipleContext,
        boolean transparent,
        List<Interface> interfaces,
        boolean sameSecurityInter,
        boolean sameSecurityIntra,
        Map<String, String> names,
        Map<String, NetworkObject> networkObjects,
        Map<String, ServiceObject> serviceObjects,
        Map<String, ObjectGroup> objectGroups,
        Map<String, List<Ace>> acls,
        List<AccessGroup> accessGroups,
        List<NatRule> natRules,
        Map<String, Set<String>> aclUses,
        Counters counters) {

    public AsaPolicyConfig {
        version = version == null ? "" : version;
        interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
        names = copyOrderedMap(names);
        networkObjects = copyOrderedMap(networkObjects);
        serviceObjects = copyOrderedMap(serviceObjects);
        objectGroups = copyOrderedMap(objectGroups);
        acls = copyOrderedListMap(acls);
        accessGroups = accessGroups == null ? List.of() : List.copyOf(accessGroups);
        natRules = natRules == null ? List.of() : List.copyOf(natRules);
        aclUses = copyOrderedSetMap(aclUses);
        counters = counters == null ? new Counters(0, 0, 0, 0, 0, 0, 0) : counters;
    }

    /** Interface block with a configured {@code nameif} (A0-ASA-4/5). */
    public record Interface(String physical, String nameif, Integer securityLevel, boolean shutdown) {}

    /**
     * Network object ({@code object network N}, A0-ASA-12/13).
     * {@code kind} is one of {@code HOST}, {@code SUBNET}, {@code RANGE}, {@code FQDN}, or {@code UNPARSED}.
     */
    public record NetworkObject(String name, String kind, String value1, String value2, boolean unparsed) {}

    /**
     * Port operator in a service object, service group, or ACE (A0-ASA-14/15).
     * {@code operator} is one of {@code eq}, {@code lt}, {@code gt}, {@code neq}, {@code range}, or {@code object-group}.
     */
    public record PortOp(String operator, String port1, String port2, String objectGroup) {
        public static PortOp single(String operator, String port) {
            return new PortOp(operator, port, null, null);
        }

        public static PortOp range(String port1, String port2) {
            return new PortOp("range", port1, port2, null);
        }

        public static PortOp group(String groupName) {
            return new PortOp("object-group", null, null, groupName);
        }
    }

    /** Service object ({@code object service N}, A0-ASA-14). */
    public record ServiceObject(
            String name,
            String protocol,
            PortOp sourcePort,
            PortOp destinationPort,
            String icmpType,
            String icmpCode,
            boolean unparsed) {}

    /** Object group ({@code object-group <type> N [protocol]}, A0-ASA-12/14/16). */
    public record ObjectGroup(
            String name,
            String groupType,
            String protocol,
            List<GroupChild> children) {
        public ObjectGroup {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    /**
     * Child entry of an {@link ObjectGroup}.
     * {@code kind} is one of {@code host}, {@code subnet}, {@code ipv6-prefix}, {@code network-object-ref},
     * {@code group-object}, {@code service-object-ref}, {@code service-object}, {@code port-object},
     * {@code protocol-object}, {@code icmp-object}, {@code user}, {@code user-group}, {@code security-group},
     * or {@code unparsed}.
     */
    public record GroupChild(
            String kind,
            String value1,
            String value2,
            PortOp sourcePort,
            PortOp destinationPort,
            boolean unparsed) {}

    /**
     * Address operand in an ACE.
     * {@code kind} is one of {@code any}, {@code any4}, {@code any6}, {@code host}, {@code subnet},
     * {@code object}, {@code object-group}, {@code interface}, or {@code ipv6}.
     */
    public record AddressSpec(String kind, String value1, String value2) {}

    /**
     * Protocol operand in an ACE.
     * {@code kind} is one of {@code literal}, {@code number}, {@code object-group}, or {@code object}.
     */
    public record ProtocolSpec(String kind, String value) {}

    /** Access control entry ({@code access-list N ...}, A0-ASA-1/8/11/15/16). */
    public record Ace(
            String aclName,
            String type,
            String action,
            ProtocolSpec protocol,
            AddressSpec source,
            PortOp sourcePort,
            AddressSpec destination,
            PortOp destinationPort,
            String icmpType,
            List<String> users,
            List<String> securityGroups,
            boolean log,
            String timeRange,
            boolean inactive,
            List<String> remarks,
            boolean unparsed) {
        public Ace {
            users = users == null ? List.of() : List.copyOf(users);
            securityGroups = securityGroups == null ? List.of() : List.copyOf(securityGroups);
            remarks = remarks == null ? List.of() : List.copyOf(remarks);
        }
    }

    /** Access-group binding ({@code access-group N in|out interface IF [control-plane]} or {@code global}, A0-ASA-2/3/9). */
    public record AccessGroup(String aclName, String direction, String interfaceName, boolean controlPlane) {}

    /**
     * NAT rule in section 1 (manual before auto), section 2 (object NAT), or section 3 (manual {@code after-auto})
     * (A0-ASA-17).
     */
    public record NatRule(
            int section,
            String objectName,
            String realInterface,
            String mappedInterface,
            String natType,
            String realSource,
            String mappedSource,
            String realDestination,
            String mappedDestination,
            String serviceProtocol,
            String realService,
            String mappedService,
            boolean inactive,
            boolean noProxyArp,
            boolean interfacePat,
            boolean anyInterface,
            boolean unparsed) {}

    /** Parsed and unparsed construct counters. */
    public record Counters(
            int parsedAces,
            int unparsedAces,
            int parsedNatRules,
            int unparsedNatRules,
            int parsedObjectGroupChildren,
            int unparsedObjectGroupChildren,
            int unknownTopLevelPolicyLines) {}

    private static <K, V> Map<K, V> copyOrderedMap(Map<K, V> input) {
        if (input == null || input.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static <K, V> Map<K, List<V>> copyOrderedListMap(Map<K, List<V>> input) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<K, List<V>> copy = new LinkedHashMap<>(input.size());
        for (var e : input.entrySet()) {
            copy.put(e.getKey(), e.getValue() == null ? List.of() : List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static <K, V> Map<K, Set<V>> copyOrderedSetMap(Map<K, Set<V>> input) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<K, Set<V>> copy = new LinkedHashMap<>(input.size());
        for (var e : input.entrySet()) {
            copy.put(e.getKey(), e.getValue() == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }
}
