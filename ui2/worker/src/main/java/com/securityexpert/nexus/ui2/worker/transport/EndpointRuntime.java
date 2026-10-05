package com.securityexpert.nexus.ui2.worker.transport;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository.Owner;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository.Permit;

/** Admission surrounds transports, never commands or credential resolution as an alternative path. */
public final class EndpointRuntime implements AutoCloseable {
    private static volatile EndpointAdmissionRepository repository;
    private static final ThreadLocal<EndpointRuntime> ACTIVE = new ThreadLocal<>();
    private static final ScheduledExecutorService HEARTBEATS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "endpoint-heartbeat"); thread.setDaemon(true); return thread;
    });
    private final EndpointRuntime previous;
    private final Owner owner;
    private final java.util.concurrent.atomic.AtomicInteger sessionSequence = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<String, Lease> reserved = new ConcurrentHashMap<>();
    private final Map<String, Lease> http = new HashMap<>();
    private final List<Lease> leases = Collections.synchronizedList(new ArrayList<>());
    private EndpointAdmissionRepository.TaskPermit fleet;
    private ScheduledFuture<?> fleetHeartbeat;
    private volatile boolean fleetLost, closing;
    public void acquireFleet() {
        if (!"FAILOVER_EXECUTION".equals(owner.purpose())) return;
        fleet = repository.acquireFleet(owner.role(), owner.instance(), owner.generation(), owner.jobId(), owner.jobEpoch()).orElseThrow(AdmissionWait::new);
        fleetHeartbeat = HEARTBEATS.scheduleAtFixedRate(() -> {
            try { if (!repository.renewFleet(fleet)) { fleetLost = true; repository.ownershipLost(owner); } }
            catch (RuntimeException unavailable) { fleetLost = true; }
        }, 10, 10, TimeUnit.SECONDS);
    }
    private final Set<java.net.http.HttpClient> clients = ConcurrentHashMap.newKeySet();
    private EndpointRuntime(Owner owner) { this.owner = owner; previous = ACTIVE.get(); ACTIVE.set(this); }
    public static void install(EndpointAdmissionRepository value) { repository = Objects.requireNonNull(value); }
    public static EndpointRuntime open(Owner owner) { return new EndpointRuntime(owner); }
    public static EndpointRuntime current() { return ACTIVE.get(); }
    public static AutoCloseable attach(EndpointRuntime scope) {
        var previous = ACTIVE.get();
        if (scope == null) ACTIVE.remove(); else ACTIVE.set(scope);
        return () -> { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); };
    }
    public static Owner owner() { return ACTIVE.get() == null ? null : ACTIVE.get().owner; }
    public static final class AdmissionWait extends IllegalStateException {
        public AdmissionWait() { super("ADMISSION_WAIT"); }
    }
    public static final class LeaseLost extends IllegalStateException {
        public LeaseLost() { super("ENDPOINT_LEASE_LOST"); }
    }
    public static void checkCommand() {
        var scope = ACTIVE.get();
        if (scope != null && "POLICY".equals(scope.owner.purpose()) && !repository.openingAllowed(scope.owner)) throw new AdmissionWait();
    }
    private boolean ownershipLost() {
        synchronized (leases) { return fleetLost || leases.stream().anyMatch(lease -> lease.lost); }
    }
    public static void checkPublication() {
        var scope = ACTIVE.get();
        if (scope != null && scope.ownershipLost()) throw new LeaseLost();
    }
    public static void rethrow(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof AdmissionWait wait) throw wait;
            else if (cause instanceof LeaseLost lost) throw lost;
        checkPublication();
    }
    public void preflight(String key) {
        Lease lease = acquire(key); leases.add(lease); reserved.put(key, lease);
    }
    private Lease take(String key) {
        if (closing) throw new LeaseLost();
        Lease reservedLease = reserved.remove(key);
        if (reservedLease != null) return reservedLease;
        Lease lease = acquire(key);
        synchronized (leases) {
            if (closing) { lease.abort(); throw new LeaseLost(); }
            leases.add(lease);
        }
        return lease;
    }
    public static Lease ssh(String host, int port) {
        if (repository == null) throw new IllegalStateException("ENDPOINT_ADMISSION_NOT_CONFIGURED");
        var scope = ACTIVE.get();
        if (scope == null) throw new IllegalStateException("ENDPOINT_OWNER_MISSING");
        if (scope.fleetLost) throw new LeaseLost();
        return scope.take(EndpointAddress.keyHost(host, port));
    }
    public static void http(String host, int port, java.net.http.HttpClient client) {
        if (repository == null) throw new IllegalStateException("ENDPOINT_ADMISSION_NOT_CONFIGURED");
        var scope = ACTIVE.get();
        if (scope == null) throw new IllegalStateException("ENDPOINT_OWNER_MISSING");
        if (scope.fleetLost) throw new LeaseLost();
        checkCommand();
        String key = EndpointAddress.keyHost(host, port);
        Lease lease = scope.http.computeIfAbsent(key, unused -> {
            return scope.take(key);
        });
        try {
            lease.check(); scope.clients.add(client);
            lease.onLoss(() -> scope.clients.forEach(java.net.http.HttpClient::shutdownNow));
        } catch (RuntimeException denied) { client.close(); throw denied; }
    }
    private Lease acquire(String key) {
        String session = "session-" + sessionSequence.incrementAndGet();
        String identity = owner.jobId() == null ? owner.operationRef() : owner.jobId();
        String ticket = UUID.nameUUIDFromBytes((identity + ":" + session).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do {
            com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
            var permit = repository.acquire(ticket, key, session, owner);
            if (permit.isPresent()) return new Lease(permit.get(), owner);
            try { Thread.sleep(500); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AdmissionWait(); }
        } while (System.nanoTime() < deadline);
        System.getLogger(EndpointRuntime.class.getName()).log(System.Logger.Level.WARNING,
            "ADMISSION_WAIT role={0} purpose={1}", owner.role(), owner.purpose());
        throw new AdmissionWait();
    }
    public static void closeHttpClients() {
        var scope = ACTIVE.get();
        if (scope == null) return;
        scope.clients.forEach(java.net.http.HttpClient::close);
        scope.clients.clear();
    }
    public static final class Lease {
        private final Permit permit;
        private final Owner owner;
        private final ScheduledFuture<?> heartbeat;
        private volatile boolean lost, closed;
        private volatile Runnable closeTransport = () -> {};
        private Lease(Permit permit, Owner owner) {
            this.permit = permit; this.owner = owner;
            heartbeat = HEARTBEATS.scheduleAtFixedRate(() -> {
                try { if (!repository.renew(permit, owner)) lose(); }
                catch (RuntimeException unavailable) { lose(); }
            }, 10, 10, TimeUnit.SECONDS);
        }
        private void lose() {
            lost = true;
            try { closeTransport.run(); }
            finally {
                try { repository.ownershipLost(owner); }
                catch (RuntimeException unavailable) { /* DB loss itself denies writes; the job lease expires. */ }
            }
            System.getLogger(EndpointRuntime.class.getName()).log(System.Logger.Level.WARNING,
                "ENDPOINT_LEASE_LOST role={0} purpose={1}", owner.role(), owner.purpose());
        }
        public void onLoss(Runnable close) { closeTransport = close; if (lost) close.run(); }
        public void check() {
            boolean current;
            try { current = !lost && !closed && repository.renew(permit, owner); }
            catch (RuntimeException unavailable) { lose(); throw new LeaseLost(); }
            if (!current) { lose(); throw new LeaseLost(); }
        }
        public synchronized void abort() { closeTransport.run(); closed(); }
        public synchronized void closed() {
            if (closed) return;
            closed = true; heartbeat.cancel(false);
            repository.releaseClosed(permit);
        }
    }
    @Override public void close() {
        closing = true;
        RuntimeException failure = null;
        boolean httpClosed = true;
        try {
            for (var client : clients) {
                try { client.close(); }
                catch (RuntimeException notClosed) { httpClosed = false; failure = notClosed; }
            }
            for (var lease : new ArrayList<>(leases)) {
                try {
                    if (httpClosed || !http.containsValue(lease)) lease.abort();
                } catch (RuntimeException unavailable) { failure = unavailable; }
            }
            if (fleet != null) {
                fleetHeartbeat.cancel(false);
                if (failure == null && !fleetLost && repository.operationCertain(owner)) repository.releaseFleetClosed(fleet);
            }
        } finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
        if (failure != null) {
            if (ownershipLost()) throw new LeaseLost();
            throw failure;
        }
    }
}
