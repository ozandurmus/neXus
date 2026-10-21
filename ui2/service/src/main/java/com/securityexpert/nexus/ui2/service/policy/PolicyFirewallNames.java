package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import java.time.Instant;
import java.util.*;

/**
 * PAN-OS identifies a managed firewall by its serial (a Panorama device-group member, a rule's target selector). The
 * Policy screen shows the firewall's hostname instead, resolved here from the inventory: the serial recorded for an
 * enrolled Palo Alto firewall ({@link DeviceRepository#listPanSerials}) matched exactly and whole, never by prefix,
 * case folding or any other guess. A serial that matches no enrolled device, or more than one, stays a serial and is
 * labelled as not enrolled. The serial stays on the entry as a secondary field for operators.
 *
 * <p>The hostname is the one the Devices screen shows ({@link DeviceRepository#listNameIdentities}), so the masked view
 * pseudonymises it with the device ID exactly like that screen does.
 */
public final class PolicyFirewallNames {
    private record Index(Map<String, String> hostnameByDevice, Map<String, String> deviceBySerial, Map<String, String> serialByDevice) {
        static final Index EMPTY = new Index(Map.of(), Map.of(), Map.of());
    }

    private final DeviceRepository devices;
    private Index index = Index.EMPTY;
    private Instant indexAt;

    public PolicyFirewallNames(DeviceRepository devices) { this.devices = devices; }

    /** A new or renamed device is seen within this long; one inventory read serves every response meanwhile. */
    private synchronized Index index() {
        if (devices == null) return Index.EMPTY;
        var now = Instant.now();
        if (indexAt != null && indexAt.plusSeconds(30).isAfter(now)) return index;
        Map<String, String> hostnames = new HashMap<>();
        devices.listNameIdentities().forEach(d -> d.hostname().filter(h -> !h.isBlank()).ifPresent(h -> hostnames.put(d.deviceId(), h)));
        Map<String, List<String>> bySerial = new HashMap<>();
        for (var pan : devices.listPanSerials()) {
            var ids = bySerial.computeIfAbsent(pan.serial(), s -> new ArrayList<>());
            if (!ids.contains(pan.deviceId())) ids.add(pan.deviceId());
        }
        Map<String, String> deviceBySerial = new HashMap<>(), serialByDevice = new HashMap<>();
        // A serial recorded for two enrolled devices proves neither: it stays unresolved.
        bySerial.forEach((serial, ids) -> { if (ids.size() == 1) { deviceBySerial.put(serial, ids.get(0)); serialByDevice.putIfAbsent(ids.get(0), serial); } });
        index = new Index(Map.copyOf(hostnames), Map.copyOf(deviceBySerial), Map.copyOf(serialByDevice));
        indexAt = now;
        return index;
    }

    /** The hostname to show for a management target: its enrolled device's, else whatever name was stored. */
    public String targetName(String deviceId, String storedName) {
        var index = index();
        String hostname = index.hostnameByDevice().get(deviceId);
        if (hostname != null) return hostname;
        // A stored name that is itself an enrolled firewall's serial (Panorama had no hostname for it).
        String bySerial = index.deviceBySerial().get(storedName);
        return bySerial != null && index.hostnameByDevice().containsKey(bySerial) ? index.hostnameByDevice().get(bySerial) : storedName;
    }

    /** The recorded serial of an enrolled firewall, for the secondary field; empty when none is recorded. */
    public Optional<String> serialOfDevice(String deviceId) { return Optional.ofNullable(index().serialByDevice().get(deviceId)); }

    /** One target selector: {@code serial}, {@code deviceId} and {@code name} (empty when not enrolled) and {@code enrolled}. */
    public Map<String, Object> selector(String serial) {
        var index = index();
        String deviceId = index.deviceBySerial().get(serial);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serial", serial);
        out.put("deviceId", deviceId == null ? "" : deviceId);
        out.put("name", deviceId == null ? "" : index.hostnameByDevice().getOrDefault(deviceId, ""));
        out.put("enrolled", deviceId != null);
        return out;
    }

    public List<Map<String, Object>> selectors(Collection<String> serials) {
        return new LinkedHashSet<>(serials).stream().map(this::selector).toList();
    }

    /** The target selectors of a rule's extras ({@code target-selectors}); empty for every other rule. */
    public List<Map<String, Object>> ruleSelectors(Map<String, List<String>> extras) {
        return selectors(extras == null ? List.of() : extras.getOrDefault("target-selectors", List.of()));
    }

    /** The target selectors named by a revision's field changes (before and after values of the extras field). */
    public List<Map<String, Object>> changeSelectors(Object changes) {
        Set<String> serials = new LinkedHashSet<>();
        if (changes instanceof List<?> list) for (Object change : list) {
            if (!(change instanceof Map<?, ?> row) || !"extras".equals(row.get("field"))) continue;
            for (String side : List.of("before", "after"))
                if (row.get(side) instanceof Map<?, ?> extras && extras.get("target-selectors") instanceof List<?> values)
                    for (Object value : values) if (value instanceof String serial && !serial.isEmpty()) serials.add(serial);
        }
        return selectors(serials);
    }

    /**
     * Target maps ({@code deviceId}, {@code name}, ...) of a PAN policy with the firewall's hostname as the name and the
     * recorded serial as a secondary field. Other vendors' targets are gateways named by their management server.
     */
    public List<Map<String, Object>> targets(String vendor, Object targets) {
        if (!(targets instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> target = new LinkedHashMap<>();
            raw.forEach((k, v) -> target.put(String.valueOf(k), v));
            if ("PAN".equals(vendor) && target.get("deviceId") instanceof String id && target.get("name") instanceof String name) {
                target.put("name", targetName(id, name));
                serialOfDevice(id).ifPresent(serial -> target.put("serial", serial));
            }
            out.add(target);
        }
        return out;
    }
}
