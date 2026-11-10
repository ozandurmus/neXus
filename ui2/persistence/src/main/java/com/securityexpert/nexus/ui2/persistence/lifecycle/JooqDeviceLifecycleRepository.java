package com.securityexpert.nexus.ui2.persistence.lifecycle;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqDeviceLifecycleRepository implements DeviceLifecycleRepository {

    public static final String COLLECTED = "COLLECTED";

    private final TransactionBoundary transactions;

    public JooqDeviceLifecycleRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void recordLicenseRead(String deviceId, String readKind, String outcome, String reason, List<LicenseItem> items) {
        List<LicenseItem> rows = items == null ? List.of() : items;
        transactions.inTransaction(dsl -> {
            boolean collected = COLLECTED.equals(outcome);
            if (collected) {
                // One answer may cover one member only (a Management Center reports each managed device's license in its own
                // call): then only that member's rows are replaced, so the other members' rows of the same kind stay.
                String member = singleMember(rows);
                if (member == null) {
                    dsl.execute("delete from device_license_item where device_id = {0} and read_kind = {1}", deviceId, readKind);
                } else {
                    dsl.execute("delete from device_license_item where device_id = {0} and read_kind = {1} and member_ref = {2}",
                            deviceId, readKind, member);
                }
                for (LicenseItem item : rows) {
                    dsl.execute("insert into device_license_item(item_id, device_id, read_kind, category, label, state, expires_on, "
                            + "detail, member_ref, evidence_grade, observed_at) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, now())",
                            UUID.randomUUID().toString(), deviceId, readKind, item.category(), item.label(), item.state(),
                            item.expiresOn() == null ? null : Date.valueOf(item.expiresOn()), item.detail(), item.memberRef(),
                            item.evidenceGrade());
                }
            }
            upsertRead(dsl, deviceId, readKind, outcome, reason,
                    "(select count(*) from device_license_item where device_id = {0} and read_kind = {1})");
            return null;
        });
    }

    /** The member every item names, or null when the items name none, several, or there are no items. */
    private static String singleMember(List<LicenseItem> rows) {
        String member = null;
        for (LicenseItem item : rows) {
            if (item.memberRef() == null || (member != null && !member.equals(item.memberRef()))) {
                return null;
            }
            member = item.memberRef();
        }
        return member;
    }

    @Override
    public void recordLivePatchRead(String deviceId, String readKind, String outcome, String reason, List<LivePatchItem> items) {
        List<LivePatchItem> rows = items == null ? List.of() : items;
        transactions.inTransaction(dsl -> {
            boolean collected = COLLECTED.equals(outcome);
            if (collected) {
                dsl.execute("delete from device_live_patch_item where device_id = {0}", deviceId);
                for (LivePatchItem item : rows) {
                    dsl.execute("insert into device_live_patch_item(device_id, patch_id, status, mode, pids_running, pids_total, "
                            + "installed_at, refs, observed_at) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}::text[], now()) "
                            + "on conflict (device_id, patch_id) do update set status = excluded.status, mode = excluded.mode, "
                            + "pids_running = excluded.pids_running, pids_total = excluded.pids_total, "
                            + "installed_at = excluded.installed_at, refs = excluded.refs, observed_at = now()",
                            deviceId, item.patchId(), item.status(), item.mode(), item.pidsRunning(), item.pidsTotal(),
                            item.installedAt() == null ? null : Timestamp.valueOf(item.installedAt()),
                            item.refs() == null ? new String[0] : item.refs().toArray(new String[0]));
                }
            }
            upsertRead(dsl, deviceId, readKind, outcome, reason,
                    "(select count(*) from device_live_patch_item where device_id = {0})");
            return null;
        });
    }

    /**
     * The read row. {@code storedCount} is a subquery counting the rows that are stored for the device after this write:
     * the count stays true when a failed read keeps the last good answer, and when one answer replaced one member only.
     */
    private static void upsertRead(DSLContext dsl, String deviceId, String readKind, String outcome, String reason, String storedCount) {
        dsl.execute("insert into device_lifecycle_read(device_id, read_kind, outcome, reason, item_count, observed_at) "
                + "values ({0}, {1}, {2}, {3}, " + storedCount + ", now()) on conflict (device_id, read_kind) do update set "
                + "outcome = excluded.outcome, reason = excluded.reason, item_count = excluded.item_count, observed_at = now()",
                deviceId, readKind, outcome, reason);
    }

    @Override
    public Map<String, List<Read>> readsByDevice() {
        return transactions.inTransaction(dsl -> {
            Map<String, List<Read>> byDevice = new LinkedHashMap<>();
            for (Record row : dsl.fetch("select device_id, read_kind, outcome, reason, item_count, observed_at "
                    + "from device_lifecycle_read order by device_id, read_kind")) {
                String deviceId = row.get("device_id", String.class);
                byDevice.computeIfAbsent(deviceId, k -> new ArrayList<>()).add(new Read(deviceId, row.get("read_kind", String.class),
                        row.get("outcome", String.class), row.get("reason", String.class), row.get("item_count", Integer.class),
                        row.get("observed_at", OffsetDateTime.class).toInstant()));
            }
            return byDevice;
        });
    }

    @Override
    public Map<String, List<LicenseItem>> licenseItemsByDevice() {
        return transactions.inTransaction(dsl -> {
            Map<String, List<LicenseItem>> byDevice = new LinkedHashMap<>();
            for (Record row : dsl.fetch("select device_id, read_kind, category, label, state, expires_on, detail, member_ref, "
                    + "evidence_grade, observed_at from device_license_item "
                    + "order by device_id, category, expires_on nulls last, label, item_id")) {
                Date expires = row.get("expires_on", Date.class);
                byDevice.computeIfAbsent(row.get("device_id", String.class), k -> new ArrayList<>()).add(new LicenseItem(
                        row.get("read_kind", String.class), row.get("category", String.class), row.get("label", String.class),
                        row.get("state", String.class), expires == null ? null : expires.toLocalDate(),
                        row.get("detail", String.class), row.get("member_ref", String.class),
                        row.get("evidence_grade", String.class), row.get("observed_at", OffsetDateTime.class).toInstant()));
            }
            return byDevice;
        });
    }

    @Override
    public Map<String, List<LivePatchItem>> livePatchByDevice() {
        return transactions.inTransaction(dsl -> {
            Map<String, List<LivePatchItem>> byDevice = new LinkedHashMap<>();
            for (Record row : dsl.fetch("select device_id, patch_id, status, mode, pids_running, pids_total, installed_at, refs, "
                    + "observed_at from device_live_patch_item order by device_id, patch_id")) {
                Timestamp installed = row.get("installed_at", Timestamp.class);
                String[] refs = row.get("refs", String[].class);
                byDevice.computeIfAbsent(row.get("device_id", String.class), k -> new ArrayList<>()).add(new LivePatchItem(
                        row.get("patch_id", String.class), row.get("status", String.class), row.get("mode", String.class),
                        row.get("pids_running", Integer.class), row.get("pids_total", Integer.class),
                        installed == null ? null : installed.toLocalDateTime(), refs == null ? List.of() : List.of(refs),
                        row.get("observed_at", OffsetDateTime.class).toInstant()));
            }
            return byDevice;
        });
    }
}
