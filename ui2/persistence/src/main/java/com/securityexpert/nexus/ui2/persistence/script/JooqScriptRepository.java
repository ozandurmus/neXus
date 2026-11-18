package com.securityexpert.nexus.ui2.persistence.script;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link ScriptRepository}. Every mutation runs inside {@link AuditedTransactionBoundary} so
 * {@code trg_audit_script} and {@code trg_audit_script_version} capture the actor and action.
 */
public final class JooqScriptRepository implements ScriptRepository {

    private static final String SCRIPT_COLUMNS = "script_id, name, kind, current_version, enabled, schedule, "
            + "timeout_s, egress, egress_device_targets, credential_refs, notify, run_as, "
            + "created_by_actor_fingerprint, created_at, updated_at";

    private static final String VERSION_COLUMNS = "script_id, version, sha256, size_bytes, artefact_ref, key_id, "
            + "wrapped_data_key, secret_findings::text as secret_findings_json, credential_refs, "
            + "egress_device_targets, created_by, created_at";

    private static final Pattern FINDING_ITEM = Pattern.compile(
            "\\{\\s*\"line\"\\s*:\\s*(\\d+)\\s*,\\s*\"pattern_class\"\\s*:\\s*\"([^\"]+)\"\\s*}"
                    + "|\\{\\s*\"pattern_class\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"line\"\\s*:\\s*(\\d+)\\s*}");

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqScriptRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public List<ScriptRecord> findAll() {
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select " + SCRIPT_COLUMNS + " from script order by name asc, script_id asc")
                .stream()
                .map(JooqScriptRepository::toScriptRecord)
                .toList());
    }

    @Override
    public Optional<ScriptRecord> findById(String scriptId) {
        if (scriptId == null || scriptId.isBlank()) {
            return Optional.empty();
        }
        return transactionBoundary.inTransaction(dsl -> fetchScriptById(dsl, scriptId));
    }

    @Override
    public Optional<ScriptRecord> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select " + SCRIPT_COLUMNS + " from script where name = {0}", name.strip())
                .stream()
                .findFirst()
                .map(JooqScriptRepository::toScriptRecord));
    }

    @Override
    public List<ScriptVersionRecord> findVersions(String scriptId) {
        if (scriptId == null || scriptId.isBlank()) {
            return List.of();
        }
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select " + VERSION_COLUMNS + " from script_version where script_id = {0} order by version desc",
                        scriptId)
                .stream()
                .map(JooqScriptRepository::toVersionRecord)
                .toList());
    }

    @Override
    public Optional<ScriptVersionRecord> findVersion(String scriptId, int version) {
        if (scriptId == null || scriptId.isBlank() || version < 1) {
            return Optional.empty();
        }
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select " + VERSION_COLUMNS + " from script_version where script_id = {0} and version = {1}",
                        scriptId, version)
                .stream()
                .findFirst()
                .map(JooqScriptRepository::toVersionRecord));
    }

    @Override
    public ScriptRecord create(ScriptRecord script, ScriptVersionRecord initialVersion, String actorFingerprint,
            String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            Timestamp createdAt = Timestamp.from(script.createdAt());
            Timestamp updatedAt = Timestamp.from(script.updatedAt());
            dsl.execute("insert into script(script_id, name, kind, current_version, enabled, schedule, timeout_s, "
                    + "egress, egress_device_targets, credential_refs, notify, run_as, "
                    + "created_by_actor_fingerprint, created_at, updated_at) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}::text[], {8}::text[], {9}::text[], "
                    + "{10}, {11}, {12}, {13}, {14})",
                    script.scriptId(),
                    script.name(),
                    script.kind(),
                    script.currentVersion(),
                    script.enabled(),
                    script.schedule().orElse(null),
                    script.timeoutSeconds(),
                    script.egress().toArray(String[]::new),
                    script.egressDeviceTargets().toArray(String[]::new),
                    script.credentialRefs().toArray(String[]::new),
                    script.notifyMode(),
                    script.runAs(),
                    script.createdByActorFingerprint(),
                    createdAt,
                    updatedAt);
            insertVersionRow(dsl, initialVersion);
            return fetchScriptById(dsl, script.scriptId()).orElseThrow();
        });
    }

    @Override
    public ScriptRecord appendVersion(String scriptId, ScriptVersionRecord version, String actorFingerprint,
            String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            dsl.execute("select 1 from script where script_id = {0} for update", scriptId);
            insertVersionRow(dsl, version);
            Timestamp now = Timestamp.from(version.createdAt());
            dsl.execute("update script set current_version = {1}, credential_refs = {2}::text[], "
                    + "egress_device_targets = {3}::text[], updated_at = {4} where script_id = {0}",
                    scriptId,
                    version.version(),
                    version.credentialRefs().toArray(String[]::new),
                    version.egressDeviceTargets().toArray(String[]::new),
                    now);
            return fetchScriptById(dsl, scriptId).orElseThrow();
        });
    }

    @Override
    public Optional<ScriptRecord> updateSettings(
            String scriptId,
            boolean enabled,
            Optional<String> schedule,
            int timeoutSeconds,
            List<String> egress,
            List<String> egressDeviceTargets,
            List<String> credentialRefs,
            String notifyMode,
            String runAs,
            String actorFingerprint,
            String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            int updated = dsl.execute("update script set enabled = {1}, schedule = {2}, timeout_s = {3}, "
                    + "egress = {4}::text[], egress_device_targets = {5}::text[], credential_refs = {6}::text[], "
                    + "notify = {7}, run_as = {8}, updated_at = {9} where script_id = {0}",
                    scriptId,
                    enabled,
                    schedule.orElse(null),
                    timeoutSeconds,
                    egress.toArray(String[]::new),
                    egressDeviceTargets.toArray(String[]::new),
                    credentialRefs.toArray(String[]::new),
                    notifyMode,
                    runAs,
                    Timestamp.from(Instant.now()));
            if (updated == 0) {
                return Optional.empty();
            }
            return fetchScriptById(dsl, scriptId);
        });
    }

    @Override
    public Optional<ScriptRecord> setEnabled(String scriptId, boolean enabled, String actorFingerprint,
            String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            int updated = dsl.execute("update script set enabled = {1}, updated_at = {2} where script_id = {0}",
                    scriptId, enabled, Timestamp.from(Instant.now()));
            if (updated == 0) {
                return Optional.empty();
            }
            return fetchScriptById(dsl, scriptId);
        });
    }

    @Override
    public List<String> findEnrolledDeviceIdsByEndpointHosts(List<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return List.of();
        }
        Set<String> lowered = new LinkedHashSet<>();
        for (String h : hosts) {
            if (h != null && !h.isBlank()) {
                lowered.add(h.strip().toLowerCase(Locale.ROOT));
            }
        }
        if (lowered.isEmpty()) {
            return List.of();
        }
        return transactionBoundary.inTransaction(dsl -> {
            Set<String> matched = new LinkedHashSet<>();
            for (String host : lowered) {
                List<String> ids = dsl.fetch("select distinct d.device_id, d.created_at from devices d "
                        + "join endpoints e on e.device_id = d.device_id "
                        + "where d.enrollment_state <> 'DRAFT' "
                        + "and (lower(btrim(e.address_ref)) = {0} or lower(btrim(split_part(e.address_ref, ':', 1))) = {0}) "
                        + "order by d.created_at asc, d.device_id asc",
                        host).getValues("device_id", String.class);
                matched.addAll(ids);
            }
            return List.copyOf(matched);
        });
    }

    private static void insertVersionRow(DSLContext dsl, ScriptVersionRecord v) {
        dsl.execute("insert into script_version(script_id, version, sha256, size_bytes, artefact_ref, key_id, "
                + "wrapped_data_key, secret_findings, credential_refs, egress_device_targets, created_by, created_at) "
                + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}::jsonb, {8}::text[], {9}::text[], {10}, {11})",
                v.scriptId(),
                v.version(),
                v.sha256(),
                v.sizeBytes(),
                v.artefactRef(),
                v.keyId(),
                v.wrappedDataKey(),
                toSecretFindingsJson(v.secretFindings()),
                v.credentialRefs().toArray(String[]::new),
                v.egressDeviceTargets().toArray(String[]::new),
                v.createdBy(),
                Timestamp.from(v.createdAt()));
    }

    private static Optional<ScriptRecord> fetchScriptById(DSLContext dsl, String scriptId) {
        return dsl.fetch("select " + SCRIPT_COLUMNS + " from script where script_id = {0}", scriptId)
                .stream()
                .findFirst()
                .map(JooqScriptRepository::toScriptRecord);
    }

    private static ScriptRecord toScriptRecord(Record row) {
        return new ScriptRecord(
                row.get("script_id", String.class),
                row.get("name", String.class),
                row.get("kind", String.class),
                row.get("current_version", Integer.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                Optional.ofNullable(row.get("schedule", String.class)),
                row.get("timeout_s", Integer.class),
                stringList(row.get("egress", String[].class)),
                stringList(row.get("egress_device_targets", String[].class)),
                stringList(row.get("credential_refs", String[].class)),
                row.get("notify", String.class),
                row.get("run_as", String.class),
                row.get("created_by_actor_fingerprint", String.class),
                row.get("created_at", Timestamp.class).toInstant(),
                row.get("updated_at", Timestamp.class).toInstant());
    }

    private static ScriptVersionRecord toVersionRecord(Record row) {
        return new ScriptVersionRecord(
                row.get("script_id", String.class),
                row.get("version", Integer.class),
                row.get("sha256", String.class),
                row.get("size_bytes", Long.class),
                row.get("artefact_ref", String.class),
                row.get("key_id", String.class),
                row.get("wrapped_data_key", byte[].class),
                parseSecretFindings(row.get("secret_findings_json", String.class)),
                stringList(row.get("credential_refs", String[].class)),
                stringList(row.get("egress_device_targets", String[].class)),
                row.get("created_by", String.class),
                row.get("created_at", Timestamp.class).toInstant());
    }

    private static List<String> stringList(String[] values) {
        return values == null ? List.of() : List.copyOf(Arrays.asList(values));
    }

    public static String toSecretFindingsJson(List<ScriptSecretFinding> findings) {
        List<ScriptSecretFinding> list = findings == null ? List.of() : findings;
        StringBuilder linesOut = new StringBuilder("[");
        StringBuilder itemsOut = new StringBuilder("[");
        List<Integer> uniqueLines = list.stream().map(ScriptSecretFinding::line).distinct().sorted().toList();
        for (int i = 0; i < uniqueLines.size(); i++) {
            if (i > 0) linesOut.append(',');
            linesOut.append(uniqueLines.get(i));
        }
        linesOut.append(']');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) itemsOut.append(',');
            ScriptSecretFinding f = list.get(i);
            itemsOut.append("{\"line\":").append(f.line())
                    .append(",\"pattern_class\":\"").append(escapeJson(f.patternClass())).append("\"}");
        }
        itemsOut.append(']');
        return "{\"count\":" + list.size() + ",\"line_numbers\":" + linesOut + ",\"findings\":" + itemsOut + "}";
    }

    public static List<ScriptSecretFinding> parseSecretFindings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<ScriptSecretFinding> out = new ArrayList<>();
        Matcher m = FINDING_ITEM.matcher(json);
        while (m.find()) {
            if (m.group(1) != null) {
                out.add(new ScriptSecretFinding(Integer.parseInt(m.group(1)), m.group(2)));
            } else {
                out.add(new ScriptSecretFinding(Integer.parseInt(m.group(4)), m.group(3)));
            }
        }
        return List.copyOf(out);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
