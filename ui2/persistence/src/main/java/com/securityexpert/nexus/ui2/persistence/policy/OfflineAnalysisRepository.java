package com.securityexpert.nexus.ui2.persistence.policy;

import com.securityexpert.nexus.ui2.persistence.*;
import java.util.*;

/** Stores only completed aggregates; deletion and insertion audit only the opaque analysis identity. */
public final class OfflineAnalysisRepository {
    private final TransactionBoundary transactions;
    public OfflineAnalysisRepository(TransactionBoundary transactions) { this.transactions = transactions; }
    public void save(String id, String policy, String actor, String filename, String aggregates) {
        PolicyJsonWrite.guarded("OfflineAnalysisRepository.save", "AGGREGATE_INSERT", PolicyJsonWrite.bytes(aggregates), () ->
            new AuditedTransactionBoundary(transactions).inTransaction(actor, "policy_offline_write", db -> {
            db.execute("insert into policy_offline_analysis(analysis_id, policy_id, uploader, file_name, aggregates) "
                + "values ({0}, {1}, {2}, {3}, {4}::jsonb)", id, policy, actor, filename, PolicyJsonWrite.json(db, aggregates));
            return null;
        }));
    }
    public List<String> list(String policy, int page) {
        return transactions.inTransaction(db -> db.fetch("select jsonb_build_object('id', analysis_id, 'policyId', policy_id, "
            + "'createdAt', created_at, 'fileName', file_name, 'uploader', uploader, 'rowCount', aggregates->'rowCount', "
            + "'periodStart', aggregates->'periodStart', 'periodEnd', aggregates->'periodEnd')::text as result "
            + "from policy_offline_analysis where policy_id = {0} order by created_at desc, analysis_id limit 50 offset {1}", policy, (long) page * 50)
            .map(row -> row.get("result", String.class)));
    }
    public Optional<String> find(String id) {
        return transactions.inTransaction(db -> db.fetch("select (aggregates || jsonb_build_object('id', analysis_id, "
            + "'policyId', policy_id, 'createdAt', created_at, 'fileName', file_name, 'uploader', uploader))::text as result "
            + "from policy_offline_analysis where analysis_id = {0}", id).stream().findFirst().map(row -> row.get("result", String.class)));
    }
    public boolean delete(String id, String actor) {
        return new AuditedTransactionBoundary(transactions).inTransaction(actor, "policy_offline_write", db ->
            db.execute("delete from policy_offline_analysis where analysis_id = {0}", id) > 0);
    }
}
