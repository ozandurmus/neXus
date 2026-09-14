package com.securityexpert.nexus.ui2.persistence.device.configuration;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link DeviceConfigurationRepository} (migration V16, 14G
 * CG-1..CG-10; deviation summary migration V22, 14I DV-2). Mirrors {@code
 * com.securityexpert.nexus.ui2.persistence.device.inventory.
 * JooqDeviceInventoryRepository}'s own shape.
 */
public final class JooqDeviceConfigurationRepository implements DeviceConfigurationRepository {

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqDeviceConfigurationRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public void recordRun(ConfigurationRun run, ConfigurationArtefactRecord artefact, String actorFingerprint,
            String actionId) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            dsl.execute("insert into configuration_artefact(artefact_ref, device_id, job_id, vendor, "
                    + "plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, compression, key_id) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9})",
                    artefact.artefactRef(), artefact.deviceId(), artefact.jobId(), artefact.vendor(),
                    artefact.plaintextSha256(), artefact.plaintextBytes(), artefact.ciphertextSha256(),
                    artefact.ciphertextBytes(), artefact.compression(), artefact.keyId());

            dsl.execute("insert into device_configuration_run(run_id, device_id, job_id, collected_at, vendor, "
                    + "read_kind, is_primary, canonical_hash, raw_hash, raw_bytes, artefact_ref, "
                    + "withheld_line_count, sanitized_text, change_state) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11}, {12}, {13})",
                    run.runId(), run.deviceId(), run.jobId(), Timestamp.from(run.collectedAt()), run.vendor(),
                    run.readKind(), run.primary(), run.canonicalHash(), run.rawHash(), run.rawBytes(),
                    run.artefactRef(), run.withheldLineCount(), run.sanitizedText().orElse(null), run.changeState());

            for (ConfigurationIndexEntry entry : run.index()) {
                dsl.execute("insert into device_configuration_index(index_id, run_id, context, section, source, "
                        + "entry_count) values ({0}, {1}, {2}, {3}, {4}, {5})",
                        UUID.randomUUID().toString(), run.runId(), entry.context(), entry.section(),
                        entry.source().orElse(null), entry.entryCount());
            }
            for (ConfigurationOverride override : run.overrides()) {
                dsl.execute("insert into device_configuration_override(override_id, run_id, context, category, "
                        + "element_path, panorama_source) values ({0}, {1}, {2}, {3}, {4}, {5})",
                        UUID.randomUUID().toString(), run.runId(), override.context(), override.category(),
                        override.elementPath(), override.panoramaSource().orElse(null));
            }

            // 14I DV-2: persist the deviation summary when present (changed runs only).
            if (run.deviationSummary().isPresent()) {
                ConfigurationDeviationSummary summary = run.deviationSummary().get();
                String summaryId = UUID.randomUUID().toString();
                dsl.execute("insert into configuration_run_deviation_summary(summary_id, run_id, status) "
                        + "values ({0}, {1}, {2})",
                        summaryId, run.runId(), summary.status().name());
                for (ConfigurationDeviationEntry entry : summary.entries()) {
                    dsl.execute("insert into configuration_run_deviation_entry(entry_id, summary_id, context, "
                            + "section, source, kind, old_count, new_count) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7})",
                            UUID.randomUUID().toString(), summaryId, entry.context(), entry.section(),
                            entry.source().orElse(null), entry.kind().name(), entry.oldCount(), entry.newCount());
                }
            }
            return null;
        });
    }

    @Override
    public Optional<ConfigurationRun> findLatestRun(String deviceId, String readKind) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> runRows = dsl.fetch("select run_id from device_configuration_run "
                    + "where device_id = {0} and read_kind = {1} order by collected_at desc limit 1",
                    deviceId, readKind);
            return runRows.stream().findFirst().flatMap(row -> assemble(dsl, row.get("run_id", String.class)));
        });
    }

    @Override
    public List<ConfigurationRun> findLatestRuns(List<String> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return transactionBoundary.inTransaction(dsl -> {
            List<ConfigurationRun> result = new ArrayList<>();
            for (String deviceId : deviceIds) {
                findLatestPrimary(dsl, deviceId).ifPresent(result::add);
            }
            return List.copyOf(result);
        });
    }

    @Override
    public ConfigurationRunView findRunView(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> {
            Optional<ConfigurationRun> primary = findLatestPrimary(dsl, deviceId);
            Result<Record> supplementaryHeads = dsl.fetch("select run_id from device_configuration_run "
                    + "where device_id = {0} and is_primary = false and run_id in "
                    + "(select run_id from ("
                    + "  select run_id, read_kind, row_number() over "
                    + "    (partition by read_kind order by collected_at desc) as rn "
                    + "  from device_configuration_run where device_id = {0} and is_primary = false"
                    + ") ranked where rn = 1)", deviceId);
            List<ConfigurationRun> supplementary = new ArrayList<>();
            for (Record head : supplementaryHeads) {
                assemble(dsl, head.get("run_id", String.class)).ifPresent(supplementary::add);
            }
            return new ConfigurationRunView(deviceId, primary, supplementary);
        });
    }

    private static Optional<ConfigurationRun> findLatestPrimary(DSLContext dsl, String deviceId) {
        Result<Record> runRows = dsl.fetch("select run_id from device_configuration_run "
                + "where device_id = {0} and is_primary = true order by collected_at desc limit 1", deviceId);
        return runRows.stream().findFirst().flatMap(row -> assemble(dsl, row.get("run_id", String.class)));
    }

    private static Optional<ConfigurationRun> assemble(DSLContext dsl, String runId) {
        Result<Record> runRows = dsl.fetch("select run_id, device_id, job_id, collected_at, vendor, read_kind, "
                + "is_primary, canonical_hash, raw_hash, raw_bytes, artefact_ref, withheld_line_count, "
                + "sanitized_text, change_state from device_configuration_run where run_id = {0}", runId);
        Optional<Record> runRowOpt = runRows.stream().findFirst();
        if (runRowOpt.isEmpty()) {
            return Optional.empty();
        }
        Record runRow = runRowOpt.get();

        List<ConfigurationIndexEntry> index = new ArrayList<>();
        Result<Record> indexRows = dsl.fetch("select context, section, source, entry_count "
                + "from device_configuration_index where run_id = {0}", runId);
        for (Record row : indexRows) {
            index.add(new ConfigurationIndexEntry(row.get("context", String.class), row.get("section", String.class),
                    Optional.ofNullable(row.get("source", String.class)), row.get("entry_count", Integer.class)));
        }

        List<ConfigurationOverride> overrides = new ArrayList<>();
        Result<Record> overrideRows = dsl.fetch("select context, category, element_path, panorama_source "
                + "from device_configuration_override where run_id = {0}", runId);
        for (Record row : overrideRows) {
            overrides.add(new ConfigurationOverride(row.get("context", String.class), row.get("category", String.class),
                    row.get("element_path", String.class), Optional.ofNullable(row.get("panorama_source", String.class))));
        }

        // 14I DV-2: load the deviation summary when present (changed runs only).
        Optional<ConfigurationDeviationSummary> deviationSummary = assembleDeviationSummary(dsl, runId);

        return Optional.of(new ConfigurationRun(runRow.get("run_id", String.class), runRow.get("device_id", String.class),
                runRow.get("job_id", String.class), runRow.get("collected_at", Timestamp.class).toInstant(),
                runRow.get("vendor", String.class), runRow.get("read_kind", String.class),
                Boolean.TRUE.equals(runRow.get("is_primary", Boolean.class)), runRow.get("canonical_hash", String.class),
                runRow.get("raw_hash", String.class), runRow.get("raw_bytes", Long.class),
                runRow.get("artefact_ref", String.class), runRow.get("withheld_line_count", Integer.class),
                Optional.ofNullable(runRow.get("sanitized_text", String.class)), runRow.get("change_state", String.class),
                index, overrides, deviationSummary));
    }

    private static Optional<ConfigurationDeviationSummary> assembleDeviationSummary(DSLContext dsl, String runId) {
        Result<Record> summaryRows = dsl.fetch(
                "select summary_id, status from configuration_run_deviation_summary where run_id = {0}", runId);
        Optional<Record> summaryRowOpt = summaryRows.stream().findFirst();
        if (summaryRowOpt.isEmpty()) {
            return Optional.empty();
        }
        Record summaryRow = summaryRowOpt.get();
        String summaryId = summaryRow.get("summary_id", String.class);
        ConfigurationDeviationSummary.Status status =
                ConfigurationDeviationSummary.Status.valueOf(summaryRow.get("status", String.class));

        List<ConfigurationDeviationEntry> entries = new ArrayList<>();
        Result<Record> entryRows = dsl.fetch(
                "select context, section, source, kind, old_count, new_count "
                + "from configuration_run_deviation_entry where summary_id = {0}", summaryId);
        for (Record row : entryRows) {
            entries.add(new ConfigurationDeviationEntry(
                    row.get("context", String.class),
                    row.get("section", String.class),
                    Optional.ofNullable(row.get("source", String.class)),
                    ConfigurationDeviationEntry.DeviationKind.valueOf(row.get("kind", String.class)),
                    row.get("old_count", Integer.class),
                    row.get("new_count", Integer.class)));
        }
        return Optional.of(new ConfigurationDeviationSummary(status, entries));
    }
}
