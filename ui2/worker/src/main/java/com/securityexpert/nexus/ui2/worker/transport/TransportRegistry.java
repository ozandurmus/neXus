package com.securityexpert.nexus.ui2.worker.transport;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;

/**
 * Maps {@link TransportKind} to the one registered {@link DeviceTransport}
 * adapter (contract §5: "only the transport the first capability needs is
 * implemented"). Only {@link TransportKind#SSH_EXEC} has a registered
 * adapter at this movement; {@link TransportKind#SSH_INTERACTIVE} and
 * {@link TransportKind#PAN_XML_API} are absent on purpose.
 */
public final class TransportRegistry {

    private final Map<TransportKind, DeviceTransport> adapters = new EnumMap<>(TransportKind.class);

    public void register(TransportKind kind, DeviceTransport adapter) {
        adapters.put(kind, adapter);
    }

    public Optional<DeviceTransport> find(TransportKind kind) {
        return Optional.ofNullable(adapters.get(kind));
    }

    public boolean hasAdapter(TransportKind kind) {
        return adapters.containsKey(kind);
    }
}
