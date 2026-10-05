package com.securityexpert.nexus.ui2.persistence.policy;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.exception.DataAccessException;

/** Bound JDBC parameters while retaining one atomic JSON publication and history trigger. */
final class PolicyJsonWrite {
    static final int CHUNK_CHARS = 16_384; // At most 64 KiB UTF-8 per parameter.
    private PolicyJsonWrite() {}

    static long bytes(String... values) {
        long bytes = 0;
        for (String value : values) if (value != null) bytes += value.getBytes(StandardCharsets.UTF_8).length;
        return bytes;
    }

    static <T> T guarded(String method, String kind, long bytes, Supplier<T> write) {
        try { return write.get(); }
        catch (DataAccessException failure) { throw new PolicyDatabaseFailure(method, kind, bytes); }
    }

    static Field<String> json(DSLContext db, String value) {
        if (value == null || value.length() <= CHUNK_CHARS) return DSL.val(value, String.class);
        // Transaction-local staging only; no durable schema or raw vendor response retention.
        String table = "policy_json_" + java.util.UUID.randomUUID().toString().replace("-", "");
        guarded("PolicyJsonWrite.json", "JSON_STAGE_CREATE", bytes(value),
            () -> db.execute("create temporary table " + table + " (ordinal integer, fragment text) on commit drop"));
        int ordinal = 0;
        for (int start = 0; start < value.length();) {
            int end = Math.min(value.length(), start + CHUNK_CHARS);
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            int index = ordinal++;
            String fragment = value.substring(start, end);
            guarded("PolicyJsonWrite.json", "JSON_CHUNK_INSERT", bytes(fragment),
                () -> db.execute("insert into " + table + " (ordinal, fragment) values ({0}, {1})", index, fragment));
            start = end;
        }
        return DSL.field("(select string_agg(fragment, '' order by ordinal) from " + table + ")", String.class);
    }
}
