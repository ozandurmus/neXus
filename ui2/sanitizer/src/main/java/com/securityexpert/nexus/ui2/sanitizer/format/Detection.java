package com.securityexpert.nexus.ui2.sanitizer.format;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides an input's kind from its content, never from its name (DATA_SANITIZER_CONTRACT.md §3.1 "type by
 * content"): pcap and pcapng by their magic numbers, XLSX as a zip holding an OOXML workbook, JSON when the whole
 * text is well-formed JSON, CSV when the first lines share one delimiter count, otherwise text. Other binary
 * content (archives, images, executables, compressed captures) is refused.
 */
public record Detection(FileType type, TextEncoding encoding, char delimiter) {

    private static final int SNIFF = 64 * 1024;

    public static Detection of(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(SNIFF);
        }
        if (head.length == 0) throw new UnsupportedInputException("EMPTY_INPUT", "the file is empty");
        if (head.length >= 4) {
            int m = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16) | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
            if (m == 0xd4c3b2a1 || m == 0xa1b2c3d4 || m == 0x4d3cb2a1 || m == 0xa1b23c4d) {
                return new Detection(FileType.PCAP, null, (char) 0);
            }
            if (m == 0x0a0d0d0a) return new Detection(FileType.PCAPNG, null, (char) 0);
            if (m == 0x504b0304) {
                if (XlsxFormat.isWorkbook(file)) return new Detection(FileType.XLSX, null, (char) 0);
                throw new UnsupportedInputException("UNSUPPORTED_ARCHIVE", "a zip archive that is not an XLSX workbook");
            }
        }
        TextEncoding encoding = TextEncoding.detect(file);
        if (encoding.bomBytes() == 0 && looksBinary(head)) {
            throw new UnsupportedInputException("UNSUPPORTED_BINARY",
                    "binary content that is not pcap, pcapng or xlsx (compressed captures must be decompressed first)");
        }
        String text = new String(head, encoding.bomBytes(), head.length - encoding.bomBytes(), encoding.charset());
        String stripped = text.startsWith("﻿") ? text.substring(1) : text;
        String lead = stripped.stripLeading();
        if (!lead.isEmpty() && (lead.charAt(0) == '{' || lead.charAt(0) == '[')) {
            try (InputStream in = Files.newInputStream(file)) {
                if (JsonFormat.isValid(in, encoding)) return new Detection(FileType.JSON, encoding, (char) 0);
            }
        }
        char delimiter = CsvFormat.sniffDelimiter(stripped);
        if (delimiter != 0) return new Detection(FileType.CSV, encoding, delimiter);
        return new Detection(FileType.TEXT, encoding, (char) 0);
    }

    /** NUL bytes, or many control bytes, in the first 64 KB of a file without a UTF-16 mark. */
    private static boolean looksBinary(byte[] head) {
        int control = 0;
        for (byte b : head) {
            int v = b & 0xff;
            if (v == 0) return true;
            if (v < 0x09 || (v > 0x0d && v < 0x20 && v != 0x1b)) control++;
        }
        return control > head.length / 20;
    }

    static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
