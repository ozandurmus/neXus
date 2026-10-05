package com.securityexpert.nexus.ui2.service.failover;

import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository.Owner;

/** Existing service execution entry point shares the worker's durable fleet and endpoint budgets. */
@Component
public final class ServiceEndpointAdmission {
    private final EndpointAdmissionRepository admission;
    private final DeviceRepository devices;
    private static final ScheduledExecutorService HEARTBEATS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "service-endpoint-heartbeat"); thread.setDaemon(true); return thread;
    });
    public ServiceEndpointAdmission(TransactionBoundary transactions, DeviceRepository devices) {
        admission = new EndpointAdmissionRepository(transactions); this.devices = devices;
    }
    public Scope open() { return new Scope(); }
    public final class Scope implements AutoCloseable {
        private final String instance = "service-" + UUID.randomUUID();
        private final EndpointAdmissionRepository.TaskPermit fleet;
        private final Owner owner;
        private final List<EndpointAdmissionRepository.Permit> permits = new ArrayList<>();
        private final ScheduledFuture<?> heartbeat;
        private volatile boolean lost;
        private boolean dispatched;
        private Scope() {
            fleet = admission.acquireFleet("service", instance, 1).orElseThrow(() -> new IllegalStateException("ADMISSION_WAIT"));
            owner = new Owner(null, 0, fleet.key(), fleet.epoch(), "service", instance, 1, "FAILOVER_EXECUTION", false);
            heartbeat = HEARTBEATS.scheduleAtFixedRate(() -> {
                synchronized (permits) {
                    try {
                        if (!admission.renewFleet(fleet)) lost = true;
                        for (var permit : permits) if (!admission.renew(permit, owner)) lost = true;
                    } catch (RuntimeException unavailable) { lost = true; }
                }
            }, 10, 10, TimeUnit.SECONDS);
        }
        public void members(String first, String second) {
            // Only a proven member->device registry relationship is usable. No hostname/ordinal joins.
            var endpoints = List.of(first, second).stream().map(member -> devices.findEndpointByDeviceId(member)
                .orElseThrow(() -> new IllegalStateException("ENDPOINT_NOT_EVALUABLE"))).toList();
            List<String> keys = endpoints.stream().map(endpoint -> EndpointAddress.key(endpoint.addressRef(),
                endpoint.transportKind().toLowerCase(Locale.ROOT).contains("ssh") ? 22 : 443)).distinct().sorted().toList();
            for (String key : keys) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                String ticket = UUID.randomUUID().toString();
                Optional<EndpointAdmissionRepository.Permit> permit;
                do {
                    check();
                    permit = admission.acquire(ticket, key, ticket, owner);
                    if (permit.isPresent()) break;
                    try { Thread.sleep(100); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("ADMISSION_WAIT"); }
                } while (System.nanoTime() < deadline);
                synchronized (permits) { permits.add(permit.orElseThrow(() -> new IllegalStateException("ADMISSION_WAIT"))); }
            }
        }
        public void check() {
            if (lost || !admission.renewFleet(fleet)) throw new IllegalStateException("ENDPOINT_LEASE_LOST");
            synchronized (permits) {
                for (var permit : permits) if (!admission.renew(permit, owner)) throw new IllegalStateException("ENDPOINT_LEASE_LOST");
            }
        }
        public void dispatched() { check(); dispatched = true; }
        @Override public void close() {
            heartbeat.cancel(false);
            // The legacy dispatcher interface supplies no transport-close proof. A dispatched service operation
            // retains its slots for quarantine/reconciliation; a timestamp never authorizes another mutation.
            if (!dispatched && !lost) {
                permits.forEach(admission::releaseClosed);
                admission.releaseFleetClosed(fleet);
            }
        }
    }
}
