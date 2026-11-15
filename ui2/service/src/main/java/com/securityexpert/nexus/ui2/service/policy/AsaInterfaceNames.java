package com.securityexpert.nexus.ui2.service.policy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.InterfaceNameSource;

/**
 * The configured interface name to {@code nameif} table of each Cisco ASA, read from the mapper's interface table
 * ({@code asa-interfaces}: {@code nameif} / {@code physical} / {@code security-level} values in interface order) of the
 * device's stored configuration (ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md step 8). A device is left out -- its hops stay
 * UNKNOWN -- when its snapshot does not name exactly one target, when the device has more than one ASA snapshot, or when
 * the table repeats a name or a nameif. Names are compared by exact string equality only.
 */
public final class AsaInterfaceNames implements InterfaceNameSource {

    private final Supplier<List<PolicySnapshot>> snapshots;

    public AsaInterfaceNames(Supplier<List<PolicySnapshot>> snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Override
    public Map<String, Map<String, String>> load() {
        Map<String, Map<String, String>> out = new HashMap<>();
        Set<String> repeated = new HashSet<>();
        for (PolicySnapshot snapshot : snapshots.get()) {
            if (!"ASA".equals(snapshot.metadata().vendor()) || snapshot.metadata().targets().size() != 1) {
                continue;
            }
            String deviceId = snapshot.metadata().targets().get(0).deviceId();
            Map<String, String> table = table(snapshot);
            if (out.containsKey(deviceId) || repeated.contains(deviceId)) {
                out.remove(deviceId);
                repeated.add(deviceId);
                continue;
            }
            if (table != null && !table.isEmpty()) {
                out.put(deviceId, table);
            }
        }
        return out;
    }

    /** The physical-to-nameif table of one snapshot, or {@code null} when it is absent or not unambiguous. */
    static Map<String, String> table(PolicySnapshot snapshot) {
        if (!snapshot.failures().isEmpty()) {
            return null;
        }
        Map<String, String> table = null;
        for (PolicySnapshot.PolicyObject object : snapshot.objects().values()) {
            if (!"asa-interfaces".equals(object.type())) {
                continue;
            }
            if (table != null) {
                return null;
            }
            table = new HashMap<>();
            Set<String> nameifs = new HashSet<>();
            String nameif = null;
            for (String value : object.values()) {
                if (value.startsWith("nameif: ")) {
                    nameif = value.substring("nameif: ".length());
                    if (nameif.isEmpty() || !nameifs.add(nameif)) {
                        return null;
                    }
                } else if (value.startsWith("physical: ")) {
                    String physical = value.substring("physical: ".length());
                    if (nameif == null || physical.isEmpty() || table.putIfAbsent(physical, nameif) != null) {
                        return null;
                    }
                    nameif = null;
                } else if (value.startsWith("security-level: ")) {
                    nameif = null;
                }
            }
        }
        return table == null ? null : Map.copyOf(table);
    }
}
