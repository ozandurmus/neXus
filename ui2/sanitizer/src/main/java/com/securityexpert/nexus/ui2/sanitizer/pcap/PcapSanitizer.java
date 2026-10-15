package com.securityexpert.nexus.ui2.sanitizer.pcap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.securityexpert.nexus.ui2.sanitizer.core.Counts;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenResolver;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenSink;

/**
 * The pcap/pcapng path (DATA_SANITIZER_CONTRACT.md §1, §4): the PacketBreaker modes ported byte for byte for parity,
 * the neXus mode, and its vault revert. Every entry point streams: one record (at most 64 MB, PacketBreaker's limit)
 * and one batch of frames in memory at a time.
 */
public final class PcapSanitizer {

    /** PacketBreaker's payload modes: {@code zero} keeps lengths, {@code headers-only} cuts after the headers. */
    public enum ReferenceMode { ZERO, HEADERS_ONLY }

    private PcapSanitizer() {
    }

    /**
     * PacketBreaker {@code anonymize(mode, key, map_ips)} on a stream (parity).
     *
     * @param key the reference key (at least 32 bytes), or {@code null} for PacketBreaker's default MAC-only key
     */
    public static long reference(InputStream in, OutputStream out, ReferenceMode mode, byte[] key, boolean mapIps)
            throws IOException {
        if (mapIps && key == null) throw new PcapException("IP mapping requires a local key; reuse it across every project file");
        FrameRewriter frames = FrameRewriter.reference(mode == ReferenceMode.HEADERS_ONLY, key, mapIps);
        return run(frames, in, out, null);
    }

    /** The neXus mode: every emitted address and DNS-name token goes to {@code sink}; counts per kind. */
    public static long sanitize(InputStream in, OutputStream out, Mappers mappers, TokenSink sink, Counts counts)
            throws IOException {
        return run(FrameRewriter.nexus(mappers, sink, counts), in, out, null);
    }

    /** Reverts a neXus-sanitized capture: addresses and DNS names the vault holds get their originals back. */
    public static long revert(InputStream in, OutputStream out, TokenResolver resolver, Counts counts) throws IOException {
        return run(FrameRewriter.revert(counts), in, out, resolver);
    }

    private static long run(FrameRewriter frames, InputStream in, OutputStream out, TokenResolver resolver)
            throws IOException {
        BufferedOutputStream buffered = new BufferedOutputStream(out, 1 << 16);
        long packets = new PcapContainer(frames, new BufferedInputStream(in, 1 << 16), buffered, resolver).run();
        buffered.flush();
        return packets;
    }
}
