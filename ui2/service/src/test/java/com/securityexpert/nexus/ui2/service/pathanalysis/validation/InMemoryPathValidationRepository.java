package com.securityexpert.nexus.ui2.service.pathanalysis.validation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.pathvalidation.PathValidationRepository;

/** The V188 tables in memory, with the plan-only-while-pending rule of the migration's CHECK. */
public final class InMemoryPathValidationRepository implements PathValidationRepository {

    public static final class Row {
        public final NewHop hop;
        public String result;
        public String plan;
        public String jobId;
        public String jobState;
        public String checks = "[]";
        public final List<String> reasons;

        Row(NewHop hop) {
            this.hop = hop;
            this.result = hop.result();
            this.plan = hop.planJson().orElse(null);
            this.reasons = new ArrayList<>(hop.reasons());
        }

        void end(String newResult) {
            result = newResult;
            plan = null;
        }
    }

    public final Map<String, Validation> heads = new LinkedHashMap<>();
    public final Map<String, List<Row>> rows = new LinkedHashMap<>();

    @Override
    public void create(String validationId, String actor, String topologySnapshotId, String pathVerdict,
            int hopsNotValidated, List<NewHop> hops) {
        if (!validationId.matches("pv-[0-9a-f-]{36}") || !topologySnapshotId.startsWith("tps-") || hops.size() > 4)
            throw new IllegalArgumentException("V188 CHECK");
        for (NewHop h : hops) {
            if (!h.nodeId().matches("tn-[0-9a-f]{20}") || !h.branchId().matches("b[0-9]{1,3}")
                    || ("PENDING".equals(h.result()) != h.planJson().isPresent()))
                throw new IllegalArgumentException("V188 CHECK");
        }
        heads.put(validationId, new Validation(validationId, actor, Instant.now(), topologySnapshotId, pathVerdict,
                hopsNotValidated, List.of()));
        List<Row> list = new ArrayList<>();
        hops.forEach(h -> list.add(new Row(h)));
        rows.put(validationId, list);
    }

    public Row row(String validationId, int ordinal) {
        return rows.get(validationId).stream().filter(r -> r.hop.ordinal() == ordinal).findFirst().orElseThrow();
    }

    @Override
    public boolean attachJob(String validationId, int ordinal, String jobId) {
        Row r = row(validationId, ordinal);
        if (!"PENDING".equals(r.result) || r.jobId != null) return false;
        r.jobId = jobId;
        r.jobState = "REQUESTED";
        return true;
    }

    @Override
    public void abandon(String validationId, int ordinal, String reason) {
        Row r = row(validationId, ordinal);
        if ("PENDING".equals(r.result)) {
            r.end("NOT_EVALUABLE");
            r.reasons.add(reason);
        }
    }

    @Override
    public Optional<Pending> pendingForJob(String jobId) {
        return Optional.empty();
    }

    @Override
    public boolean recordOutcome(String jobId, long leaseEpoch, String result, String checksJson, List<String> reasons) {
        return false;
    }

    @Override
    public boolean abandonJob(String jobId, String reason) {
        return false;
    }

    @Override
    public Optional<Validation> find(String validationId) {
        Validation head = heads.get(validationId);
        if (head == null) return Optional.empty();
        List<Hop> hops = rows.get(validationId).stream().map(r -> new Hop(r.hop.ordinal(), r.hop.branchId(),
                r.hop.hopIndex(), r.hop.nodeId(), r.hop.vendor(), r.hop.deviceId(), Optional.ofNullable(r.jobId),
                Optional.ofNullable(r.jobState), r.result, r.checks, r.reasons, Optional.empty())).toList();
        return Optional.of(new Validation(head.validationId(), head.submittedBy(), head.submittedAt(),
                head.topologySnapshotId(), head.pathVerdict(), head.hopsNotValidated(), hops));
    }
}
