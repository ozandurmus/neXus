package com.securityexpert.nexus.ui2.persistence.policy;

import com.securityexpert.nexus.ui2.persistence.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.*;

/** Immutable generations; publish the manifest only after every short chunk transaction succeeds. */
final class PolicyChunkStore {
    static final int MAX_BYTES = 1_048_576;
    private static final int CHARS = 131_072; // Worst-case UTF-8 and gzip overhead both fit below MAX_BYTES.
    record Manifest(String generation, int count) {}
    private final TransactionBoundary tx;
    PolicyChunkStore(TransactionBoundary tx) { this.tx = tx; }

    static void requireBounded(long bytes) {
        if (bytes > MAX_BYTES) throw new PolicyDatabaseFailure("PolicyChunkStore", "STATEMENT_PARAMETER_TOO_LARGE", bytes,
            "POLICY_DB_STATEMENT_PARAMETER_TOO_LARGE");
    }

    /** Skip object tokens rather than materializing the inventory's large array. */
    static String inventoryMetadata(String value) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var metadata = mapper.createObjectNode();
        try (var parser = mapper.getFactory().createParser(value)) {
            if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT)
                throw new IllegalStateException("POLICY_INVENTORY_INVALID");
            while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                if (parser.currentToken() != com.fasterxml.jackson.core.JsonToken.FIELD_NAME)
                    throw new IllegalStateException("POLICY_INVENTORY_INVALID");
                String field = parser.currentName(); parser.nextToken();
                if (field.equals("objects")) {
                    if (parser.currentToken() != com.fasterxml.jackson.core.JsonToken.START_ARRAY)
                        throw new IllegalStateException("POLICY_INVENTORY_INVALID");
                    parser.skipChildren();
                }
                else metadata.set(field, mapper.readTree(parser));
            }
            if (parser.nextToken() != null) throw new IllegalStateException("POLICY_INVENTORY_INVALID");
            metadata.putArray("objects");
            return mapper.writeValueAsString(metadata);
        } catch (IOException invalid) { throw new IllegalStateException("POLICY_INVENTORY_INVALID"); }
    }

    static void validateSnapshots(String value) {
        try (var parser = new com.fasterxml.jackson.core.JsonFactory().createParser(value)) {
            if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_ARRAY)
                throw new IllegalStateException("POLICY_DOMAIN_SNAPSHOTS_INVALID");
            parser.skipChildren();
            if (parser.nextToken() != null) throw new IllegalStateException("POLICY_DOMAIN_SNAPSHOTS_INVALID");
        } catch (IOException invalid) { throw new IllegalStateException("POLICY_DOMAIN_SNAPSHOTS_INVALID"); }
    }

    Manifest write(String source, String domain, String type, String kind, String value, String actor) {
        for (String parameter : new String[]{source, domain, type, kind, actor}) requireBounded(PolicyJsonWrite.bytes(parameter));
        String generation = UUID.randomUUID().toString();
        int ordinal = 0;
        for (int start = 0; start < value.length();) {
            int end = Math.min(value.length(), start + CHARS);
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            byte[] plain = value.substring(start, end).getBytes(StandardCharsets.UTF_8);
            byte[] payload = compress(plain);
            requireBounded(plain.length); requireBounded(payload.length);
            int page = ordinal++;
            PolicyJsonWrite.guarded("PolicyChunkStore.write", "CHUNK_INSERT", payload.length,
                () -> new AuditedTransactionBoundary(tx).inTransaction(actor, "policy_collect_chunk", db -> {
                    db.execute("insert into cp_policy_json_chunk(generation, source_id, domain_ref, object_type, kind, ordinal, payload, plain_bytes) "
                        + "values ({0},{1},{2},{3},{4},{5},{6},{7})", generation, source, domain, type, kind, page, payload, plain.length);
                    return null;
                }));
            start = end;
        }
        return new Manifest(generation, ordinal);
    }

    Reader reader(String source, String domain, String type, String kind, Manifest manifest) {
        return new Reader() {
            int ordinal;
            Reader current = Reader.nullReader();
            boolean closed;
            @Override public int read(char[] buffer, int offset, int length) throws IOException {
                if (closed) throw new IOException("POLICY_CHUNK_READER_CLOSED");
                if (length == 0) return 0;
                while (true) {
                    int read = current.read(buffer, offset, length);
                    if (read >= 0) return read;
                    current.close();
                    if (ordinal == manifest.count()) return -1;
                    int page = ordinal++;
                    var chunk = tx.inTransaction(db -> db.fetch("select payload, plain_bytes from cp_policy_json_chunk "
                        + "where generation = {0} and source_id = {1} and domain_ref = {2} and object_type = {3} and kind = {4} and ordinal = {5}",
                        manifest.generation(), source, domain, type, kind, page).stream().findFirst()
                        .orElseThrow(() -> new IllegalStateException("POLICY_CHUNK_MISSING")));
                    byte[] plain = decompress(chunk.get("payload", byte[].class), chunk.get("plain_bytes", Integer.class));
                    current = new StringReader(new String(plain, StandardCharsets.UTF_8));
                }
            }
            @Override public void close() throws IOException { closed = true; current.close(); }
        };
    }

    String read(String source, String domain, String type, String kind, Manifest manifest) {
        try (var reader = reader(source, domain, type, kind, manifest)) {
            var result = new StringWriter(); reader.transferTo(result); return result.toString();
        } catch (IOException invalid) { throw new IllegalStateException("POLICY_CHUNK_INVALID"); }
    }
    private static byte[] compress(byte[] plain) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(plain); }
            return bytes.toByteArray();
        } catch (IOException invalid) { throw new IllegalStateException("POLICY_CHUNK_INVALID"); }
    }
    private static byte[] decompress(byte[] payload, int expected) throws IOException {
        requireBounded(payload.length); requireBounded(expected);
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
            byte[] plain = gzip.readNBytes(MAX_BYTES + 1);
            if (plain.length != expected || plain.length > MAX_BYTES) throw new IOException("POLICY_CHUNK_INVALID");
            return plain;
        }
    }
}
