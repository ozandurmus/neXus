package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.util.Map;

/**
 * Loads, per Cisco ASA device id, the configured interface name to {@code nameif} table a topology build renames ASA
 * interfaces with (see {@link TopologyInput#interfaceNames()}); empty when none is provable. Implemented outside this
 * package: the topology never reads or evaluates policy itself.
 */
@FunctionalInterface
public interface InterfaceNameSource {

    InterfaceNameSource NONE = () -> Map.of();

    Map<String, Map<String, String>> load();
}
