package com.securityexpert.nexus.ui2.worker.backup.fortinet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorExecutor;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/** FortiGate confirm, inventory and configuration backup over one SSH shell per run (docs/design/FORTINET_CONTRACT.md). */
public final class FortiGateExecutor {

    private static final System.Logger LOG = System.getLogger(FortiGateExecutor.class.getName());
    private static final Duration CONNECT = Duration.ofSeconds(30);
    private static final Duration SHORT = Duration.ofSeconds(30);
    private static final Duration LONG = Duration.ofSeconds(180);
    private static final Duration BACKUP = Duration.ofSeconds(600);
    private static final int MAX_VDOMS = 64;

    private final DeviceTransport ssh;
    private final ArtefactStore artefactStore;

    public FortiGateExecutor(DeviceTransport ssh, ArtefactStore artefactStore) {
        this.ssh = Objects.requireNonNull(ssh, "ssh");
        this.artefactStore = artefactStore;
    }

    private record Shell(TransportSession session, Optional<String> refusal, boolean authFailure) {
    }

    private Shell open(Target target, String credentialRef) {
        ConnectionTarget ct = new ConnectionTarget(UUID.randomUUID().toString(), target.host(), target.port());
        ConnectSpec spec = new ConnectSpec(credentialRef,
                PersistedManagementEndpointTrustResolver.scopeRef(target.host(), target.port()), Optional.empty());
        return switch (ssh.connect(ct, spec, CONNECT)) {
            case ConnectResult.Authenticated a -> new Shell(a.session(), Optional.empty(), false);
            case ConnectResult.AuthenticationFailed f -> new Shell(null, Optional.of("authentication_failed"), true);
            case ConnectResult.HostKeyRejected h -> new Shell(null, Optional.of("host_key: " + h.reason()), false);
            case ConnectResult.TimedOut t -> new Shell(null, Optional.of("unreachable: " + t.reason()), false);
        };
    }

    private Optional<String> read(TransportSession s, String command, Duration timeout) {
        ExecResult r = ssh.execInteractive(s, new ExecSpec(command, true), timeout);
        if (r instanceof ExecResult.Completed c && !FortiGatePlan.isCliError(c.output())) {
            return Optional.of(stripEcho(c.output(), command));
        }
        LOG.log(System.Logger.Level.INFO, "[FGT] {0}: {1}", command, r instanceof ExecResult.Completed ? "cli_error" : r.getClass().getSimpleName());
        return Optional.empty();
    }

    /** A context move ("config global", "end"): its answer is the new prompt, often with no text. */
    private void move(TransportSession s, String command) {
        ssh.execInteractive(s, new ExecSpec(command, true), SHORT);
    }

    static String stripEcho(String out, String command) {
        String text = out;
        int nl = text.indexOf('\n');
        if (nl >= 0 && text.substring(0, nl).strip().equals(command)) {
            text = text.substring(nl + 1);
        }
        int last = text.lastIndexOf('\n');
        if (last >= 0 && text.substring(last + 1).strip().endsWith("#")) {
            text = text.substring(0, last + 1);
        }
        return text;
    }

    /** "get system status" at the top level; on a VDOM box that refuses it there, inside "config global". */
    private Optional<String> status(TransportSession s) {
        Optional<String> out = read(s, FortiGatePlan.GET_SYSTEM_STATUS, SHORT);
        if (out.isPresent() && out.get().contains("Version:")) {
            return out;
        }
        move(s, FortiGatePlan.CONFIG_GLOBAL);
        out = read(s, FortiGatePlan.GET_SYSTEM_STATUS, SHORT);
        move(s, FortiGatePlan.END);
        return out.filter(o -> o.contains("Version:"));
    }

    public HttpsVendorExecutor.ConfirmOutcome confirm(Target target, String credentialRef) {
        Shell shell = open(target, credentialRef);
        if (shell.session() == null) {
            return shell.authFailure() ? new HttpsVendorExecutor.ConfirmOutcome.AuthenticationFailed(shell.refusal().orElse(""))
                    : new HttpsVendorExecutor.ConfirmOutcome.Failed(shell.refusal().orElse(""));
        }
        try {
            Optional<String> out = status(shell.session());
            FortiGatePlan.Status st = FortiGatePlan.parseStatus(out.orElse(null));
            if (st.model().isEmpty() || !st.model().get().startsWith("Forti")) {
                return new HttpsVendorExecutor.ConfirmOutcome.Failed("not a FortiGate: get system status names no Forti model");
            }
            LOG.log(System.Logger.Level.INFO, "[FGT] confirm: hostname={0} version={1} vdoms={2}", st.hostname().isPresent(),
                    st.version().isPresent(), st.multiVdom());
            return new HttpsVendorExecutor.ConfirmOutcome.Confirmed(new HttpsVendorExecutor.Identity(st.hostname(), st.model(), st.version()));
        } finally {
            ssh.disconnect(shell.session());
        }
    }

    public HttpsVendorExecutor.InventoryOutcome inventory(Target target, String credentialRef) {
        Shell shell = open(target, credentialRef);
        if (shell.session() == null) {
            return shell.authFailure() ? new HttpsVendorExecutor.InventoryOutcome.AuthenticationFailed(shell.refusal().orElse(""))
                    : new HttpsVendorExecutor.InventoryOutcome.Failed(shell.refusal().orElse(""));
        }
        try {
            TransportSession s = shell.session();
            FortiGatePlan.Status st = FortiGatePlan.parseStatus(status(s).orElse(null));
            if (st.model().isEmpty()) {
                return new HttpsVendorExecutor.InventoryOutcome.Failed("get system status gave no FortiGate answer");
            }
            List<FortiGatePlan.Iface> ifaces;
            Map<String, List<com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute>> routes = new LinkedHashMap<>();
            if (st.multiVdom()) {
                move(s, FortiGatePlan.CONFIG_GLOBAL);
                ifaces = FortiGatePlan.parseInterfaces(read(s, FortiGatePlan.SHOW_SYSTEM_INTERFACE, LONG).orElse(null));
                move(s, FortiGatePlan.END);
                Set<String> vdoms = new LinkedHashSet<>();
                ifaces.forEach(i -> vdoms.add(i.vdom()));
                for (String vdom : vdoms) {
                    if (routes.size() >= MAX_VDOMS || !FortiGatePlan.validVdom(vdom)) {
                        continue;
                    }
                    move(s, FortiGatePlan.CONFIG_VDOM);
                    move(s, FortiGatePlan.editVdom(vdom));
                    routes.put(vdom, FortiGatePlan.parseRoutes(read(s, FortiGatePlan.GET_ROUTING_TABLE, LONG).orElse(null)));
                    move(s, FortiGatePlan.END);
                }
            } else {
                ifaces = FortiGatePlan.parseInterfaces(read(s, FortiGatePlan.SHOW_SYSTEM_INTERFACE, LONG).orElse(null));
                routes.put("root", FortiGatePlan.parseRoutes(read(s, FortiGatePlan.GET_ROUTING_TABLE, LONG).orElse(null)));
            }
            Map<String, List<InventoryInterface>> byVdom = new LinkedHashMap<>();
            for (FortiGatePlan.Iface i : ifaces) {
                byVdom.computeIfAbsent(st.multiVdom() ? i.vdom() : "root", k -> new ArrayList<>()).add(i.row());
            }
            routes.keySet().forEach(v -> byVdom.computeIfAbsent(v, k -> new ArrayList<>()));
            List<InventoryContext> contexts = new ArrayList<>();
            byVdom.forEach((vdom, list) -> contexts.add(new InventoryContext(st.multiVdom() ? vdom : InventoryContext.PHYSICAL, list,
                    routes.getOrDefault(vdom, List.of()))));
            LOG.log(System.Logger.Level.INFO, "[FGT] inventory: vdoms={0} interfaces={1} routes={2} ha={3}", byVdom.size(), ifaces.size(),
                    routes.values().stream().mapToInt(List::size).sum(), FortiGatePlan.haRole(st.haMode()).orElse("none"));
            return new HttpsVendorExecutor.InventoryOutcome.Completed(contexts, List.of(),
                    st.multiVdom() ? Optional.of(String.join(", ", byVdom.keySet())) : Optional.empty(),
                    new HttpsVendorExecutor.Identity(st.hostname(), st.model(), st.version()));
        } finally {
            ssh.disconnect(shell.session());
        }
    }

    /** The whole configuration ("show" at the top level: every VDOM, non-default values) and the system status. */
    public BackupResult backup(Target target, String credentialRef, String deviceId, String jobId) {
        if (artefactStore == null) {
            return new BackupResult.ArtefactStoreFailed("no artefact store in this worker");
        }
        Shell shell = open(target, credentialRef);
        if (shell.session() == null) {
            return new BackupResult.ConnectFailed(shell.refusal().orElse("connect_failed"));
        }
        Map<String, byte[]> members = new LinkedHashMap<>();
        try {
            TransportSession s = shell.session();
            status(s).ifPresent(t -> members.put("system-status.txt", t.getBytes(StandardCharsets.UTF_8)));
            Optional<String> config = read(s, FortiGatePlan.SHOW, BACKUP);
            if (config.isEmpty() || !FortiGatePlan.isConfiguration(config.get())) {
                return new BackupResult.SubmitOutputUnparseable("show gave no FortiOS configuration (no #config-version header)");
            }
            members.put("fortigate.conf", config.get().getBytes(StandardCharsets.UTF_8));
        } finally {
            ssh.disconnect(shell.session());
        }
        StringBuilder manifest = new StringBuilder("# FortiGate configuration backup (neXus, read-only CLI: show)\n");
        members.forEach((n, b) -> manifest.append(n).append('\t').append(b.length).append(" bytes\n"));
        ArtefactStore.ArtefactHandle handle;
        try {
            handle = artefactStore.open(deviceId, jobId, "fortinet", false);
        } catch (IOException e) {
            return new BackupResult.ArtefactStoreFailed("artefact store open failed: " + e.getMessage());
        }
        try {
            try (com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter tar =
                    new com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter(new java.util.zip.GZIPOutputStream(handle.sink()))) {
                tar.file("manifest.txt", manifest.toString().getBytes(StandardCharsets.UTF_8));
                for (var e : members.entrySet()) {
                    tar.file(e.getKey(), e.getValue());
                }
            }
            ArtefactStore.ArtefactMetadata metadata = handle.finish();
            LOG.log(System.Logger.Level.INFO, "[FGT_BACKUP] stored {0} files, {1} bytes", members.size(), metadata.plaintextBytes());
            return new BackupResult.Completed(metadata, "fortigate-configuration.tgz", Optional.empty(), Optional.empty());
        } catch (IOException e) {
            try {
                handle.close();
            } catch (IOException ignored) {
                // best effort
            }
            return new BackupResult.ArtefactStoreFailed("bundle could not be stored: " + e.getClass().getSimpleName());
        }
    }
}
