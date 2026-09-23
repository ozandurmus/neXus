package com.securityexpert.nexus.ui2.service.overview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import org.jooq.Record;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationQueryService;

/**
 * OVERVIEW_EXCEPTION_SCREEN_CONTRACT §5.1: recomputes a cluster's member DIFF summary when the sanitized texts
 * its members serve have changed since the cluster's latest row -- at startup and every 5 minutes. The texts
 * are the ones {@code GET /devices/{id}/configuration/text} serves; after comparison only section names and
 * counts are stored.
 */
@Service
public class ClusterDiffTask {

    private static final System.Logger LOG = System.getLogger(ClusterDiffTask.class.getName());

    private final TransactionBoundary transactionBoundary;
    private final DeviceRepository deviceRepository;
    private final ConfigurationQueryService configurationQueryService;

    public ClusterDiffTask(TransactionBoundary transactionBoundary, DeviceRepository deviceRepository,
            ConfigurationQueryService configurationQueryService) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.configurationQueryService = Objects.requireNonNull(configurationQueryService, "configurationQueryService");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        Thread t = new Thread(this::refreshQuietly, "cluster-diff-startup");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void refreshQuietly() {
        try {
            int written = refresh();
            if (written > 0) {
                LOG.log(System.Logger.Level.INFO, "[CLUSTER_DIFF] recomputed {0} cluster(s)", written);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[CLUSTER_DIFF] refresh failed: {0}", e.getMessage());
        }
    }

    /** @return the number of clusters recomputed */
    public synchronized int refresh() {
        Map<String, List<DeviceSummaryRecord>> clusters = new TreeMap<>();
        for (DeviceSummaryRecord d : deviceRepository.listAll()) {
            if (d.enrollmentState() == DeviceEnrollmentState.ENROLLED && d.clusterMemberRef().isPresent()) {
                clusters.computeIfAbsent(d.clusterMemberRef().get(), k -> new ArrayList<>()).add(d);
            }
        }
        Map<String, String> servedRun = servedTextRunByDevice();
        Map<String, List<String>> latestSignature = latestSignatures();
        int written = 0;
        for (Map.Entry<String, List<DeviceSummaryRecord>> cluster : clusters.entrySet()) {
            List<String> signature = cluster.getValue().stream()
                    .map(d -> d.deviceId() + ":" + servedRun.getOrDefault(d.deviceId(), "-")).sorted().toList();
            if (signature.equals(latestSignature.get(cluster.getKey()))) {
                continue;
            }
            Map<String, List<ConfigurationProjection.Row>> rows = new LinkedHashMap<>();
            for (DeviceSummaryRecord member : cluster.getValue()) {
                if (!servedRun.containsKey(member.deviceId())) {
                    continue;
                }
                Optional<String> text = configurationQueryService.sanitizedText(member.deviceId());
                if (text.isEmpty()) {
                    continue;
                }
                rows.put(member.deviceId(), "palo_alto".equalsIgnoreCase(member.vendorHint())
                        ? ConfigurationProjection.paloAlto(text.get()) : ConfigurationProjection.checkPoint(text.get()));
            }
            boolean comparable = rows.size() >= 2 && rows.size() == cluster.getValue().size();
            ConfigurationProjection.ClusterDiff diff = comparable ? ConfigurationProjection.cluster(rows)
                    : new ConfigurationProjection.ClusterDiff(0, 0, List.of());
            String[] runIds = signature.toArray(String[]::new);
            String[] sections = diff.diffSections().toArray(String[]::new);
            String signaturesJson = signaturesJson(diff.signatures());
            transactionBoundary.inTransaction(dsl -> dsl.execute(
                    "insert into cluster_member_diff(cluster_ref, member_run_ids, comparable, diff_section_count, "
                            + "diff_setting_count, diff_sections, diff_signatures) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}::jsonb)",
                    cluster.getKey(), runIds, comparable, sections.length, diff.diffCount(), sections, signaturesJson));
            written++;
        }
        // keep the latest row per cluster and the one before it; older rows carry no reader
        transactionBoundary.inTransaction(dsl -> dsl.execute("delete from cluster_member_diff c where computed_at < "
                + "(select min(computed_at) from (select computed_at from cluster_member_diff x where x.cluster_ref = c.cluster_ref "
                + "order by computed_at desc limit 2) keep)"));
        return written;
    }

    /** The run whose text each device serves: Check Point show_configuration, Palo Alto active (else effective-running). */
    private Map<String, String> servedTextRunByDevice() {
        List<Record> rows = transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select distinct on (device_id, read_kind) device_id, read_kind, run_id from device_configuration_run "
                        + "where sanitized_text is not null order by device_id, read_kind, collected_at desc"));
        Map<String, Map<String, String>> byDevice = new HashMap<>();
        for (Record r : rows) {
            byDevice.computeIfAbsent(r.get("device_id", String.class), k -> new HashMap<>())
                    .put(r.get("read_kind", String.class), r.get("run_id", String.class));
        }
        Map<String, String> served = new HashMap<>();
        byDevice.forEach((device, kinds) -> {
            String run = kinds.getOrDefault("show_configuration",
                    kinds.getOrDefault("active", kinds.getOrDefault("effective_running", null)));
            if (run != null) {
                served.put(device, run);
            }
        });
        return served;
    }

    private Map<String, List<String>> latestSignatures() {
        List<Record> rows = transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select distinct on (cluster_ref) cluster_ref, member_run_ids from cluster_member_diff "
                        + "order by cluster_ref, computed_at desc"));
        Map<String, List<String>> out = new HashMap<>();
        for (Record r : rows) {
            String[] ids = r.get("member_run_ids", String[].class);
            out.put(r.get("cluster_ref", String.class), ids == null ? List.of() : Arrays.asList(ids));
        }
        return out;
    }

    /** Hand-rolled flat JSON object of signature -> count (signatures are product-made strings; quotes and backslashes escaped). */
    static String signaturesJson(java.util.Map<String, Integer> signatures) {
        StringBuilder b = new StringBuilder("{");
        for (var e : new java.util.TreeMap<>(signatures).entrySet()) {
            if (b.length() > 1) b.append(',');
            b.append('"').append(e.getKey().replace("\\", "\\\\").replace("\"", "\\\"")).append("\":").append(e.getValue());
        }
        return b.append('}').toString();
    }
}
