package com.securityexpert.nexus.ui2.service.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.zip.GZIPInputStream;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** Fully authenticates and validates a bounded transcript before it can become an HTTP response. */
public final class JobTranscriptReader {
    // The writer caps plaintext at 64 MiB; allow gzip overhead on the encrypted payload.
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private final ObjectMapper json;
    private final int maxBytes;

    public JobTranscriptReader(ObjectMapper json) { this(json, MAX_BYTES); }

    JobTranscriptReader(ObjectMapper json, int maxBytes) {
        this.json = json;
        this.maxBytes = maxBytes;
    }

    public record Transcript(ArrayNode entries, String format) {}

    public static final class ReadFailure extends IOException {
        private final String code;
        private final boolean decrypts;

        private ReadFailure(String code, boolean decrypts, Exception cause) {
            super(code, cause);
            this.code = code;
            this.decrypts = decrypts;
        }

        public String code() { return code; }
        public boolean decrypts() { return decrypts; }
    }

    public Transcript read(ArtefactStore store, ArtefactRef ref, byte[] wrappedKey) throws ReadFailure {
        byte[] payload;
        // Read to EOF before decompressing: gzip EOF must not skip authentication of the final cipher frame.
        try (var input = store.retrieve(ref, wrappedKey, false)) {
            payload = bounded(input, maxBytes + 1024 * 1024);
        } catch (NoSuchFileException e) {
            throw new ReadFailure("TRANSCRIPT_MISSING", false, e);
        } catch (IOException | RuntimeException e) {
            throw new ReadFailure("TRANSCRIPT_DECRYPT_FAILED", false, e);
        }
        try {
            // Historical compressed and uncompressed transcripts share the same store contract.
            if (payload.length >= 2 && (payload[0] & 0xff) == 0x1f && (payload[1] & 0xff) == 0x8b) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                    payload = bounded(gzip, maxBytes);
                }
            } else if (payload.length > maxBytes) {
                throw new IOException("transcript size limit exceeded");
            }
            return parse(payload);
        } catch (IOException | RuntimeException e) {
            throw new ReadFailure("TRANSCRIPT_PARSE_FAILED", true, e);
        }
    }

    private static byte[] bounded(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException("transcript size limit exceeded");
        return bytes;
    }

    private Transcript parse(byte[] payload) throws IOException {
        ArrayNode entries = json.createArrayNode();
        // Jackson's byte parser accepts UTF-8 BOM and CRLF, including multiline JSON arrays.
        try (var parser = json.getFactory().createParser(payload)) {
            JsonToken token = parser.nextToken();
            if (token == JsonToken.START_ARRAY) {
                JsonNode array = json.readTree(parser);
                for (JsonNode entry : array) {
                    if (!entry.isObject()) throw new IOException("transcript entry must be an object");
                    entries.add(entry);
                }
                if (parser.nextToken() != null) throw new IOException("trailing transcript content");
                return new Transcript(entries, "JSON_ARRAY");
            }
            while (token != null) {
                if (token != JsonToken.START_OBJECT) throw new IOException("transcript entry must be an object");
                entries.add((JsonNode) json.readTree(parser));
                token = parser.nextToken();
            }
        }
        return new Transcript(entries, "JSON_LINES");
    }
}
