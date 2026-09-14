package com.securityexpert.nexus.ui2.worker.configuration;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies every byte read from a source {@link InputStream} into a sink
 * {@link OutputStream} as it is read -- the one-pass mechanism that lets
 * the streaming XML parser (14G CG-5) and the artefact store's write
 * pipeline both consume the same transport response without buffering it
 * twice or holding it whole in memory.
 */
final class TeeInputStream extends FilterInputStream {

    private final OutputStream sink;

    TeeInputStream(InputStream source, OutputStream sink) {
        super(source);
        this.sink = sink;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            sink.write(b);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            sink.write(b, off, n);
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            sink.close();
        }
    }
}
