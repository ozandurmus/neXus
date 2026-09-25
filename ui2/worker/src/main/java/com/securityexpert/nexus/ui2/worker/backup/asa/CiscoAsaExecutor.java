package com.securityexpert.nexus.ui2.worker.backup.asa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorExecutor;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/**
 * Cisco ASA confirm, inventory and backup over one SSH interactive shell per run (docs/design/CISCO_ASA_CONTRACT.md).
 * Answers in the vendor-executor outcome types so the confirm, inventory and backup jobs the HTTPS vendors use run it
 * unchanged. Every command is a read; the backup is the configuration text (running with keys, startup, version),
 * never the device's own {@code backup} command, which writes an archive to its flash.
 */
public final class CiscoAsaExecutor {

    private static final System.Logger LOG = System.getLogger(CiscoAsaExecutor.class.getName());
    private static final Duration CONNECT = Duration.ofSeconds(30);
    private static final Duration SHORT = Duration.ofSeconds(30);
    private static final Duration LONG = Duration.ofSeconds(180);

    private final DeviceTransport ssh;
    private final ArtefactStore artefactStore;

    public CiscoAsaExecutor(DeviceTransport ssh, ArtefactStore artefactStore) {
        this.ssh = Objects.requireNonNull(ssh, "ssh");
        this.artefactStore = artefactStore;
    }

    /** One open shell, already set to no paging, at privilege 15 -- or why not. */
    private record Shell(TransportSession session, Optional<String> refusal, boolean authFailure) {
    }

    private Shell open(Target target, String credentialRef) {
        ConnectionTarget ct = new ConnectionTarget(UUID.randomUUID().toString(), target.host(), target.port());
        ConnectSpec spec = new ConnectSpec(credentialRef,
                PersistedManagementEndpointTrustResolver.scopeRef(target.host(), target.port()), Optional.empty());
        ConnectResult r = ssh.connect(ct, spec, CONNECT);
        return switch (r) {
            case ConnectResult.Authenticated a -> {
                TransportSession s = a.session();
                // A session setting: the pager is off for this login only.
                ssh.execInteractive(s, new ExecSpec(CiscoAsaPlan.TERMINAL_PAGER_0, true), SHORT);
                Optional<String> priv = read(s, CiscoAsaPlan.SHOW_CURPRIV, SHORT);
                Optional<Integer> level = priv.flatMap(CiscoAsaPlan::parsePrivilege);
                if (level.isPresent() && level.get() < 15) {
                    ssh.disconnect(s);
                    yield new Shell(null, Optional.of("the account is at privilege " + level.get()
                            + "; the ASA reads need a privilege-15 account"), true);
                }
                yield new Shell(s, Optional.empty(), false);
            }
            case ConnectResult.AuthenticationFailed f -> new Shell(null, Optional.of("authentication_failed"), true);
            case ConnectResult.HostKeyRejected h -> new Shell(null, Optional.of("host_key: " + h.reason()), false);
            case ConnectResult.TimedOut t -> new Shell(null, Optional.of("unreachable: " + t.reason()), false);
        };
    }

    private Optional<String> read(TransportSession s, String command, Duration timeout) {
        ExecResult r = ssh.execInteractive(s, new ExecSpec(command, true), timeout);
        if (r instanceof ExecResult.Completed c && !CiscoAsaPlan.isCliError(c.output())) {
            return Optional.of(stripEcho(c.output(), command));
        }
        LOG.log(System.Logger.Level.INFO, "[ASA] {0}: {1}", command, r instanceof ExecResult.Completed ? "cli_error" : r.getClass().getSimpleName());
        return Optional.empty();
    }

    /** The shell echoes the command on the first line and ends with the prompt: neither is device output. */
    static String stripEcho(String out, String command) {
        String text = out;
        int nl = text.indexOf('\n');
        if (nl >= 0 && text.substring(0, nl).contains(command)) {
            text = text.substring(nl + 1);
        }
        int last = text.lastIndexOf('\n');
        if (last >= 0) {
            String tail = text.substring(last + 1).strip();
            if (tail.endsWith("#") || tail.endsWith(">")) {
                text = text.substring(0, last + 1);
            }
        }
        return text;
    }

    public HttpsVendorExecutor.ConfirmOutcome confirm(Target target, String credentialRef) {
        Shell shell = open(target, credentialRef);
        if (shell.session() == null) {
            return shell.authFailure() ? new HttpsVendorExecutor.ConfirmOutcome.AuthenticationFailed(shell.refusal().orElse(""))
                    : new HttpsVendorExecutor.ConfirmOutcome.Failed(shell.refusal().orElse(""));
        }
        try {
            Optional<String> version = read(shell.session(), CiscoAsaPlan.SHOW_VERSION, SHORT);
            if (version.isEmpty()) {
                return new HttpsVendorExecutor.ConfirmOutcome.Failed("show version gave no answer");
            }
            CiscoAsaPlan.Version v = CiscoAsaPlan.parseVersion(version.get());
            if (v.softwareVersion().isEmpty()) {
                return new HttpsVendorExecutor.ConfirmOutcome.Failed("not an ASA: show version names no Adaptive Security Appliance software");
            }
            LOG.log(System.Logger.Level.INFO, "[ASA] confirm: hostname={0} model={1} version={2}",
                    v.hostname().isPresent(), v.model().isPresent(), v.softwareVersion().isPresent());
            return new HttpsVendorExecutor.ConfirmOutcome.Confirmed(
                    new HttpsVendorExecutor.Identity(v.hostname(), v.model(), v.softwareVersion()));
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
            CiscoAsaPlan.Version v = CiscoAsaPlan.parseVersion(read(s, CiscoAsaPlan.SHOW_VERSION, SHORT).orElse(null));
            if (v.softwareVersion().isEmpty()) {
                return new HttpsVendorExecutor.InventoryOutcome.Failed("show version gave no ASA answer");
            }
            Optional<String> ip = read(s, CiscoAsaPlan.SHOW_IP_ADDRESS, SHORT);
            Optional<String> brief = read(s, CiscoAsaPlan.SHOW_INTERFACE_IP_BRIEF, SHORT);
            Optional<String> route = read(s, CiscoAsaPlan.SHOW_ROUTE, LONG);
            Optional<String> failover = read(s, CiscoAsaPlan.SHOW_FAILOVER_THIS_HOST, SHORT);
            boolean multiple = read(s, CiscoAsaPlan.SHOW_MODE, SHORT).map(CiscoAsaPlan::isMultipleContext).orElse(false);
            var interfaces = CiscoAsaPlan.interfaces(ip.orElse(null), brief.orElse(null));
            var routes = CiscoAsaPlan.parseRoutes(route.orElse(null));
            LOG.log(System.Logger.Level.INFO, "[ASA] inventory: interfaces={0} routes={1} ha={2} multiple_context={3}",
                    interfaces.size(), routes.size(), failover.flatMap(CiscoAsaPlan::parseHaRole).orElse("none"), multiple);
            String context = v.hostname().orElse(InventoryContext.PHYSICAL);
            return new HttpsVendorExecutor.InventoryOutcome.Completed(List.of(new InventoryContext(context, interfaces, routes)),
                    List.of(), Optional.empty(), new HttpsVendorExecutor.Identity(v.hostname(), v.model(), v.softwareVersion()));
        } finally {
            ssh.disconnect(shell.session());
        }
    }

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
            Optional<String> running = read(s, CiscoAsaPlan.MORE_SYSTEM_RUNNING_CONFIG, LONG);
            if (running.isEmpty() || !running.get().contains("ASA Version")) {
                return new BackupResult.SubmitOutputUnparseable("more system:running-config gave no ASA configuration");
            }
            members.put("running-config.txt", running.get().getBytes(StandardCharsets.UTF_8));
            read(s, CiscoAsaPlan.SHOW_STARTUP_CONFIG, LONG).ifPresent(t -> members.put("startup-config.txt", t.getBytes(StandardCharsets.UTF_8)));
            read(s, CiscoAsaPlan.SHOW_VERSION, SHORT).ifPresent(t -> members.put("show-version.txt", t.getBytes(StandardCharsets.UTF_8)));
            read(s, CiscoAsaPlan.SHOW_MODE, SHORT).ifPresent(t -> members.put("show-mode.txt", t.getBytes(StandardCharsets.UTF_8)));
        } finally {
            ssh.disconnect(shell.session());
        }
        StringBuilder manifest = new StringBuilder("# Cisco ASA configuration backup (neXus, read-only CLI)\n");
        members.forEach((name, bytes) -> manifest.append(name).append('\t').append(bytes.length).append(" bytes\n"));
        ArtefactStore.ArtefactHandle handle;
        try {
            handle = artefactStore.open(deviceId, jobId, "cisco_asa", false);
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
            LOG.log(System.Logger.Level.INFO, "[ASA_BACKUP] stored {0} files, {1} bytes", members.size(), metadata.plaintextBytes());
            return new BackupResult.Completed(metadata, "asa-configuration.tgz", Optional.empty(), Optional.empty());
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
