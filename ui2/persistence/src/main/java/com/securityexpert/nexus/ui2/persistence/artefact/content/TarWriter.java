package com.securityexpert.nexus.ui2.persistence.artefact.content;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A minimal POSIX ustar writer for the few regular files a backup bundle
 * carries (a device-state export and a set-format configuration). The
 * counterpart of {@link TarEntryLister}; no directories, links or long
 * names -- a bundle's member names are the product's own, short and fixed.
 */
public final class TarWriter implements AutoCloseable {

    private static final int BLOCK = 512;

    private final OutputStream out;
    private boolean closed;

    public TarWriter(OutputStream out) {
        this.out = out;
    }

    public void file(String name, byte[] content) throws IOException {
        out.write(header(name, content.length));
        out.write(content);
        pad(content.length);
    }

    /**
     * A member whose bytes are streamed (an archive too large to hold in memory): the size is known up front (the tar
     * header needs it), the returned stream takes exactly that many bytes, and closing it pads the member. Fewer or
     * more bytes than announced is an IOException -- never a silently corrupt bundle.
     */
    public OutputStream begin(String name, long size) throws IOException {
        out.write(header(name, size));
        return new OutputStream() {
            private long written;
            private boolean ended;

            @Override
            public void write(int b) throws IOException {
                if (written + 1 > size) {
                    throw new IOException("tar member " + name + " longer than announced " + size);
                }
                out.write(b);
                written++;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (written + len > size) {
                    throw new IOException("tar member " + name + " longer than announced " + size);
                }
                out.write(b, off, len);
                written += len;
            }

            @Override
            public void close() throws IOException {
                if (ended) {
                    return;
                }
                ended = true;
                if (written != size) {
                    throw new IOException("tar member " + name + " ended at " + written + " of " + size + " bytes");
                }
                pad(size);
            }
        };
    }

    private byte[] header(String name, long size) throws IOException {
        if (name.getBytes(StandardCharsets.UTF_8).length > 100) {
            throw new IOException("tar member name longer than 100 bytes: " + name);
        }
        byte[] header = new byte[BLOCK];
        put(header, 0, name);
        put(header, 100, "0000644");
        put(header, 108, "0000000");
        put(header, 116, "0000000");
        put(header, 124, String.format("%011o", size));
        put(header, 136, String.format("%011o", System.currentTimeMillis() / 1000));
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = '0';
        put(header, 257, "ustar");
        put(header, 263, "00");
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        put(header, 148, String.format("%06o", sum) + "\0 ");
        return header;
    }

    private void pad(long length) throws IOException {
        long padding = (BLOCK - (length % BLOCK)) % BLOCK;
        out.write(new byte[(int) padding]);
    }

    private static void put(byte[] header, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    /** Writes the end-of-archive marker and closes the underlying stream. */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        out.write(new byte[2 * BLOCK]);
        out.close();
    }
}
