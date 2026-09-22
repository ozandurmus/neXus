package com.securityexpert.nexus.ui2.persistence.artefact.content;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository.Entry;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository.EntryType;

/**
 * Streams a gzip-compressed POSIX ustar / GNU tar archive (a Gaia
 * {@code add backup local} .tgz, a PAN-OS device-state export) and yields
 * one {@link Entry} per member: name, type, size and, for regular files,
 * the SHA-256 of the content. The content itself is read once for the
 * digest and dropped -- nothing is retained beyond the listing, per the
 * raw-evidence law. No archive library: the format is 512-byte headers
 * with octal fields, which is all a listing needs.
 */
public final class TarEntryLister {

    private static final int BLOCK = 512;
    /** Refuse an archive whose listing would be absurd rather than fill the table. */
    private static final int MAX_ENTRIES = 250_000;

    private TarEntryLister() {
    }

    public static List<Entry> list(InputStream gzipped) throws IOException {
        try (InputStream tar = new GZIPInputStream(gzipped, 1 << 16)) {
            return listTar(tar);
        }
    }

    static List<Entry> listTar(InputStream tar) throws IOException {
        List<Entry> entries = new ArrayList<>();
        byte[] header = new byte[BLOCK];
        String pendingLongName = null;
        while (true) {
            if (!readFully(tar, header)) {
                break; // truncated: what was listed so far stands, the caller sees the count
            }
            if (isZeroBlock(header)) {
                break; // end-of-archive marker
            }
            String name = field(header, 0, 100);
            long size = octal(header, 124, 12);
            char type = (char) header[156];
            String prefix = "ustar".equals(field(header, 257, 5)) ? field(header, 345, 155) : "";
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            if (type == 'L') { // GNU long name: the next member's name is this member's content
                pendingLongName = readAsString(tar, size);
                skipPadding(tar, size);
                continue;
            }
            if (type == 'K' || type == 'x' || type == 'g') { // GNU long link / pax headers: not listed
                skip(tar, size);
                skipPadding(tar, size);
                continue;
            }
            if (pendingLongName != null) {
                name = pendingLongName;
                pendingLongName = null;
            }
            EntryType entryType = switch (type) {
                case '0', '\0', '7' -> EntryType.FILE;
                case '5' -> EntryType.DIR;
                case '2' -> EntryType.SYMLINK;
                default -> EntryType.OTHER;
            };
            Optional<String> digest = Optional.empty();
            if (entryType == EntryType.FILE) {
                digest = Optional.of(digestAndSkip(tar, size));
            } else {
                skip(tar, size);
            }
            skipPadding(tar, size);
            entries.add(new Entry(name, entryType, entryType == EntryType.FILE ? size : 0, digest));
            if (entries.size() > MAX_ENTRIES) {
                throw new IOException("archive lists more than " + MAX_ENTRIES + " entries; refusing to record it");
            }
        }
        return entries;
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n < 0) {
                if (read == 0) {
                    return false;
                }
                throw new EOFException("truncated tar header");
            }
            read += n;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] header, int offset, int length) throws IOException {
        if ((header[offset] & 0x80) != 0) { // GNU base-256 for sizes >= 8 GiB
            long value = 0;
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (header[offset + i] & 0xff);
            }
            return value;
        }
        String text = field(header, offset, length).strip();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            throw new IOException("not a tar header (bad octal field)");
        }
    }

    private static String readAsString(InputStream in, long size) throws IOException {
        byte[] bytes = in.readNBytes((int) Math.min(size, 1 << 16));
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static String digestAndSkip(InputStream in, long size) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[1 << 16];
        long remaining = size;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                throw new EOFException("truncated tar member");
            }
            digest.update(buffer, 0, n);
            remaining -= n;
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void skip(InputStream in, long size) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("truncated tar member");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (BLOCK - (size % BLOCK)) % BLOCK;
        skip(in, padding);
    }
}
