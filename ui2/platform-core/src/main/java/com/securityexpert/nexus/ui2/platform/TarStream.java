package com.securityexpert.nexus.ui2.platform;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal POSIX ustar writer and reader, so the support bundle code needs no archive library. Regular files only; an
 * entry is written with its size known up front (the header carries it), so a large item streams in 64 KiB chunks and
 * is never held whole. Names longer than 100 characters use the ustar prefix field.
 */
public final class TarStream {
    private TarStream() {}

    public static final int BLOCK = 512;

    /** Writes entries one after another; {@link #finish()} writes the two zero blocks. Does not close the target. */
    public static final class Writer {
        private final OutputStream out;
        private long remaining;
        private long padding;

        public Writer(OutputStream out) { this.out = out; }

        /** Starts an entry; the caller then writes exactly {@code size} bytes through {@link #write} and calls {@link #endEntry}. */
        public void beginEntry(String name, long size, long mtimeEpochSeconds) throws IOException {
            if (remaining != 0) throw new IOException("previous tar entry is not complete");
            byte[] h = new byte[BLOCK];
            String path = name;
            String prefix = "";
            if (path.getBytes(StandardCharsets.UTF_8).length > 100) {
                int cut = path.lastIndexOf('/', 155);
                while (cut > 0 && (path.length() - cut - 1 > 100)) cut = path.lastIndexOf('/', cut - 1);
                if (cut <= 0 || path.length() - cut - 1 > 100 || cut > 155) throw new IOException("tar entry name too long");
                prefix = path.substring(0, cut);
                path = path.substring(cut + 1);
            }
            put(h, 0, 100, path);
            put(h, 100, 8, "0000644\0");
            put(h, 108, 8, "0000000\0");
            put(h, 116, 8, "0000000\0");
            put(h, 124, 12, String.format("%011o\0", size));
            put(h, 136, 12, String.format("%011o\0", mtimeEpochSeconds));
            for (int i = 148; i < 156; i++) h[i] = ' ';
            h[156] = '0';
            put(h, 257, 6, "ustar\0");
            put(h, 263, 2, "00");
            put(h, 345, 155, prefix);
            long sum = 0;
            for (byte b : h) sum += b & 0xff;
            put(h, 148, 8, String.format("%06o\0 ", sum));
            out.write(h);
            remaining = size;
            padding = (BLOCK - size % BLOCK) % BLOCK;
        }

        public void write(byte[] b, int off, int len) throws IOException {
            if (len > remaining) throw new IOException("more bytes than the tar entry size");
            out.write(b, off, len);
            remaining -= len;
        }

        public void endEntry() throws IOException {
            if (remaining != 0) throw new IOException("tar entry shorter than its declared size");
            out.write(new byte[(int) padding]);
            padding = 0;
        }

        public void entry(String name, byte[] bytes, long mtime) throws IOException {
            beginEntry(name, bytes.length, mtime);
            write(bytes, 0, bytes.length);
            endEntry();
        }

        public void finish() throws IOException {
            out.write(new byte[BLOCK * 2]);
            out.flush();
        }

        private static void put(byte[] h, int off, int len, String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(b, 0, h, off, Math.min(b.length, len));
        }
    }

    public record Entry(String name, byte[] bytes) {
    }

    /**
     * Reads a small archive (regular files) fully; refuses one over {@code maxTotalBytes}. For the inner archives the
     * collector itself wrote (cp_gaia_extras), never for device-supplied archives.
     */
    public static List<Entry> readAll(InputStream in, long maxTotalBytes) throws IOException {
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        byte[] h = new byte[BLOCK];
        while (true) {
            int got = in.readNBytes(h, 0, BLOCK);
            if (got == 0) break;
            if (got < BLOCK) throw new EOFException("truncated tar header");
            boolean zero = true;
            for (byte b : h) if (b != 0) { zero = false; break; }
            if (zero) break;
            String name = text(h, 0, 100);
            String prefix = text(h, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            long size = Long.parseLong(text(h, 124, 12).strip(), 8);
            total += size;
            if (total > maxTotalBytes) throw new IOException("tar archive larger than the bound");
            byte[] data = in.readNBytes((int) size);
            if (data.length != size) throw new EOFException("truncated tar entry");
            in.skipNBytes((BLOCK - size % BLOCK) % BLOCK);
            if (h[156] == '0' || h[156] == 0) entries.add(new Entry(name, data));
        }
        return entries;
    }

    private static String text(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        return new String(h, off, end - off, StandardCharsets.UTF_8);
    }

    /** Convenience for tests and the inner archive: writes entries as a plain tar into a byte array. */
    public static byte[] toBytes(List<Entry> entries, long mtime) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Writer w = new Writer(bos);
        for (Entry e : entries) w.entry(e.name(), e.bytes(), mtime);
        w.finish();
        return bos.toByteArray();
    }
}
