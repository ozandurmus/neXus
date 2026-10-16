package com.securityexpert.nexus.ui2.sanitizer.format;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How a text input is decoded so that writing it back is byte-exact: UTF-16 when a UTF-16 byte-order mark says so
 * (the mark itself is copied raw), UTF-8 when the whole file is valid UTF-8 (a UTF-8 mark passes through as
 * U+FEFF), and otherwise ISO-8859-1, which maps every byte to one character and back -- so legacy code pages
 * (Windows-1254 and the like) survive untouched while their ASCII addresses and names are still found.
 */
public record TextEncoding(Charset charset, int bomBytes) {

    public static TextEncoding detect(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(4);
        }
        if (head.length >= 2 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xfe) {
            return new TextEncoding(StandardCharsets.UTF_16LE, 2);
        }
        if (head.length >= 2 && (head[0] & 0xff) == 0xfe && (head[1] & 0xff) == 0xff) {
            return new TextEncoding(StandardCharsets.UTF_16BE, 2);
        }
        return new TextEncoding(isValidUtf8(file) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1, 0);
    }

    private static final long MAX_UTF8_SNIFF = 4L << 20;

    static boolean isValidUtf8(Path file) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.allocate(1 << 16);
        CharBuffer out = CharBuffer.allocate(1 << 16);
        long totalRead = 0;
        boolean eof = false;
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] chunk = new byte[1 << 15];
            while (totalRead < MAX_UTF8_SNIFF) {
                int n = stream.read(chunk);
                if (n < 0) {
                    eof = true;
                    break;
                }
                totalRead += n;
                in.put(chunk, 0, n);
                in.flip();
                CoderResult result = decoder.decode(in, out, false);
                if (result.isError()) return false;
                out.clear();
                in.compact();
            }
            if (!eof) {
                return true;
            }
            in.flip();
            CoderResult result = decoder.decode(in, out, true);
            if (result.isError()) return false;
            return !decoder.flush(out).isError() && !in.hasRemaining();
        }
    }
}
