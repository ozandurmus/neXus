package com.securityexpert.nexus.ui2.service.device.configuration;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupSizeAnomalyRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Backup size anomalies on the Backups "Active major deviations" card and their resolution (PO decision 2026-10-30,
 * docs/design/BACKUP_SIZE_ANOMALY_RETENTION.md). The worker records them at ingest; while one is open the device's
 * last good backup is kept by retention whatever its age. Only a security_admin's "accept new size", with a reason,
 * releases it: the anomaly becomes the new normal and the age rule resumes. Nothing releases it automatically.
 */
public final class BackupSizeAnomalyService {

    public static final int REASON_MAX = 500;
    public static final String ROLE_NOTE = "Only a security_admin can accept a new backup size.";

    /** A refusal the controller answers with its code. */
    public static final class Refusal extends RuntimeException {
        private final String code;

        public Refusal(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** One device's open anomaly with the inventory's name fields (masked for the replay viewer by the server). */
    public record View(JooqBackupSizeAnomalyRepository.OpenAnomaly anomaly, Optional<String> hostname,
            Optional<String> clusterMemberRef) {
    }

    private final JooqBackupSizeAnomalyRepository repository;
    private final Supplier<List<DeviceSummaryRecord>> devices;
    private final RbacEvaluator rbac;

    public BackupSizeAnomalyService(JooqBackupSizeAnomalyRepository repository,
            Supplier<List<DeviceSummaryRecord>> devices, RbacEvaluator rbac) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.rbac = rbac;
    }

    public List<View> open() {
        List<JooqBackupSizeAnomalyRepository.OpenAnomaly> open = repository.openAnomalies();
        if (open.isEmpty()) {
            return List.of();
        }
        Map<String, DeviceSummaryRecord> inventory = new HashMap<>();
        for (DeviceSummaryRecord device : devices.get()) {
            inventory.put(device.deviceId(), device);
        }
        return open.stream().map(a -> {
            DeviceSummaryRecord d = inventory.get(a.deviceId());
            return new View(a, d == null ? Optional.empty() : d.observedHostname(),
                    d == null ? Optional.empty() : d.clusterMemberRef());
        }).toList();
    }

    /** security_admin (including the root administrator); never the replay viewer. */
    public boolean mayAccept(String actor) {
        if (actor == null || rbac == null) {
            return false;
        }
        var viewer = rbac.evaluate(actor, Optional.of(RoleToken.REPLAY_VIEWER), Instant.now());
        boolean replayViewer = viewer.outcome() == AuthzOutcome.PERMITTED
                && viewer.authority().filter(RbacEvaluator.ROOT_AUTHORITY::equals).isEmpty();
        return !replayViewer
                && rbac.evaluate(actor, Optional.of(RoleToken.SECURITY_ADMIN), Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }

    /** @return how many open anomaly rows were resolved */
    public int accept(String deviceId, String reason, String actor) {
        if (!mayAccept(actor)) {
            throw new Refusal("WRONG_ROLE");
        }
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty() || text.length() > REASON_MAX) {
            throw new Refusal("REASON_REQUIRED");
        }
        int resolved = repository.accept(deviceId, actor, ActionRegistry.BACKUP_SIZE_ANOMALY_ACCEPT, text);
        if (resolved == 0) {
            throw new Refusal("NO_OPEN_SIZE_ANOMALY");
        }
        return resolved;
    }
}
