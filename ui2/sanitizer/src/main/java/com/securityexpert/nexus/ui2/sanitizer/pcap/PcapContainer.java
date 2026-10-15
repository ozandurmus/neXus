package com.securityexpert.nexus.ui2.sanitizer.pcap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The capture container walk of PacketBreaker's {@code rewrite} (frozen 5b608e6), byte for byte: classic pcap keeps
 * its global header and every record header with only the captured length updated; pcapng keeps section, interface
 * and packet blocks, drops every option except the numeric interface ones ({@code if_tsresol}, {@code if_tsoffset},
 * {@code if_fcslen}) and the interface-statistics counters, sets the section length to -1, and drops name-resolution,
 * decryption-secrets, custom and unknown blocks entirely. A damaged or oversized record refuses the capture.
 *
 * <p>Frames are handed to the {@link FrameRewriter} in batches (one vault lookup per batch on revert); output order
 * is the input order.</p>
 */
final class PcapContainer {

    private static final int MAX_RECORD = 64 * 1024 * 1024;
    private static final int BATCH = 512;

    private final FrameRewriter frames;
    private final InputStream in;
    private final OutputStream out;
    private final com.securityexpert.nexus.ui2.sanitizer.core.TokenResolver resolver;
    private final List<Object> queue = new ArrayList<>();
    private final List<byte[]> batchFrames = new ArrayList<>();
    private final List<Integer> batchLinks = new ArrayList<>();
    private long count;

    /** A packet awaiting its rewrite: everything around the frame bytes, and how to frame the new bytes. */
    private record Pending(int index, ByteOrder order, int kind, byte[] head, long wireLength, boolean pcapng) {
    }

    PcapContainer(FrameRewriter frames, InputStream in, OutputStream out, com.securityexpert.nexus.ui2.sanitizer.core.TokenResolver resolver) {
        this.frames = frames;
        this.in = in;
        this.out = out;
        this.resolver = resolver;
    }

    /** @return the number of packets */
    long run() throws IOException {
        byte[] magic = readExact(4);
        ByteOrder classic = switch (hex(magic)) {
            case "d4c3b2a1", "4d3cb2a1" -> ByteOrder.LITTLE_ENDIAN;
            case "a1b2c3d4", "a1b23c4d" -> ByteOrder.BIG_ENDIAN;
            default -> null;
        };
        if (classic != null) {
            byte[] header = readExact(20);
            write(magic);
            write(header);
            int linkType = ByteBuffer.wrap(header, 16, 4).order(classic).getInt() & 0x0fffffff;
            while (true) {
                byte[] record = in.readNBytes(16);
                if (record.length == 0) break;
                if (record.length != 16) throw new PcapException("Damaged pcap record");
                ByteBuffer b = ByteBuffer.wrap(record).order(classic);
                int caplen = b.getInt(8);
                long wirelen = b.getInt(12) & 0xffffffffL;
                byte[] raw = readExact(caplen);
                packet(raw, linkType, new Pending(0, classic, 0, Arrays.copyOf(record, 8), wirelen, false));
            }
        } else if (hex(magic).equals("0a0d0d0a")) {
            pcapng(magic);
        } else {
            throw new PcapException("Only classic pcap and pcapng are supported");
        }
        flush();
        if (count == 0) throw new PcapException("No usable packets");
        return count;
    }

    private void pcapng(byte[] firstType) throws IOException {
        ByteOrder order = ByteOrder.LITTLE_ENDIAN;
        List<Integer> linkTypes = new ArrayList<>();
        byte[] header = concat(firstType, readExact(4));
        while (true) {
            byte[] body;
            long size;
            if (Arrays.equals(Arrays.copyOf(header, 4), new byte[] {0x0a, 0x0d, 0x0d, 0x0a})) {
                byte[] bom = readExact(4);
                if (hex(bom).equals("4d3c2b1a")) order = ByteOrder.LITTLE_ENDIAN;
                else if (hex(bom).equals("1a2b3c4d")) order = ByteOrder.BIG_ENDIAN;
                else throw new PcapException("Invalid pcapng byte order");
                size = ByteBuffer.wrap(header, 4, 4).order(order).getInt() & 0xffffffffL;
                body = concat(bom, readExact(size - 12));
                linkTypes.clear();
            } else {
                size = ByteBuffer.wrap(header, 4, 4).order(order).getInt() & 0xffffffffL;
                body = readExact(size - 8);
            }
            if (size < 12 || size % 4 != 0 || body.length < 4
                    || !Arrays.equals(Arrays.copyOfRange(body, body.length - 4, body.length), u32(order, size))) {
                throw new PcapException("Invalid pcapng block length");
            }
            int kind = ByteBuffer.wrap(header, 0, 4).order(order).getInt();
            body = Arrays.copyOf(body, body.length - 4);
            byte[] newBody = null;
            if (kind == 0x0A0D0D0A) {
                newBody = concat(Arrays.copyOf(body, 8), ByteBuffer.allocate(8).order(order).putLong(-1L).array());
            } else if (kind == 1) {
                linkTypes.add(ByteBuffer.wrap(body, 0, 2).order(order).getShort() & 0xffff);
                newBody = concat(Arrays.copyOf(body, 8), safeOptions(Arrays.copyOfRange(body, 8, body.length), order,
                        Map.of(9, 1, 14, 8, 13, 1)));
            } else if (kind == 6 || kind == 2) {
                ByteBuffer b = ByteBuffer.wrap(body).order(order);
                int iface = kind == 6 ? b.getInt(0) : b.getShort(0) & 0xffff;
                long caplen = b.getInt(12) & 0xffffffffL;
                long wirelen = b.getInt(16) & 0xffffffffL;
                if (20 + caplen > body.length) throw new PcapException("tshark/container frame length mismatch");
                byte[] raw = Arrays.copyOfRange(body, 20, (int) (20 + caplen));
                int linkType = iface < linkTypes.size() ? linkTypes.get(iface) : -1;
                packet(raw, linkType, new Pending(0, order, kind, Arrays.copyOf(body, 12), wirelen, true));
            } else if (kind == 5) {
                newBody = concat(Arrays.copyOf(body, 12), safeOptions(Arrays.copyOfRange(body, 12, body.length), order,
                        Map.of(2, 8, 3, 8, 4, 8, 5, 8, 6, 8, 7, 8, 8, 8)));
            } else if (kind == 3) {
                throw new PcapException("Untimestamped pcapng simple packet blocks are unsupported; convert to EPB first");
            }
            // NRB, DSB, custom blocks, unknown vendor metadata: dropped entirely (newBody stays null).
            if (newBody != null) write(block(order, kind, newBody));
            header = in.readNBytes(8);
            if (header.length == 0) break;
            if (header.length != 8) throw new PcapException("Damaged pcapng block");
        }
    }

    private static byte[] block(ByteOrder order, int kind, byte[] body) {
        int size = body.length + 12;
        return ByteBuffer.allocate(size).order(order).putInt(kind).putInt(size).put(body).putInt(size).array();
    }

    /** {@code safe_options}: only the allowed numeric options, each with its exact length, then end-of-options. */
    static byte[] safeOptions(byte[] data, ByteOrder order, Map<Integer, Integer> allowed) {
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        int pos = 0;
        while (pos + 4 <= data.length) {
            ByteBuffer b = ByteBuffer.wrap(data, pos, 4).order(order);
            int code = b.getShort() & 0xffff;
            int size = b.getShort() & 0xffff;
            pos += 4;
            if (code == 0) break;
            if (pos + size > data.length) throw new PcapException("Malformed pcapng option");
            byte[] value = Arrays.copyOfRange(data, pos, pos + size);
            if (allowed.containsKey(code)) {
                if (size != allowed.get(code)) throw new PcapException("Invalid numeric pcapng option length");
                result.writeBytes(ByteBuffer.allocate(4).order(order).putShort((short) code).putShort((short) size).array());
                result.writeBytes(value);
                result.writeBytes(new byte[(4 - size % 4) % 4]);
            }
            pos += (size + 3) & ~3;
        }
        result.writeBytes(new byte[4]);
        return result.toByteArray();
    }

    private void packet(byte[] raw, int linkType, Pending pending) throws IOException {
        count++;
        queue.add(new Pending(batchFrames.size(), pending.order(), pending.kind(), pending.head(), pending.wireLength(), pending.pcapng()));
        batchFrames.add(raw);
        batchLinks.add(linkType);
        if (batchFrames.size() >= BATCH) flush();
    }

    private void write(byte[] bytes) throws IOException {
        if (batchFrames.isEmpty()) out.write(bytes);
        else queue.add(bytes);
    }

    private void flush() throws IOException {
        if (resolver != null && !batchFrames.isEmpty()) {
            frames.resolved(resolver.resolve(frames.keys(batchFrames, batchLinks)));
        }
        for (Object item : queue) {
            if (item instanceof byte[] bytes) {
                out.write(bytes);
                continue;
            }
            Pending p = (Pending) item;
            byte[] raw = batchFrames.get(p.index());
            int linkType = batchLinks.get(p.index());
            byte[] rewritten = linkType < 0 || !Layers.supportedLinkType(linkType)
                    ? unsupported(raw) : frames.rewrite(raw, linkType);
            if (p.pcapng()) {
                byte[] body = ByteBuffer.allocate(12 + 8 + rewritten.length + (4 - rewritten.length % 4) % 4).order(p.order())
                        .put(p.head()).putInt(rewritten.length).putInt((int) p.wireLength()).put(rewritten).array();
                out.write(block(p.order(), p.kind(), body));
            } else {
                out.write(ByteBuffer.allocate(16).order(p.order()).put(p.head()).putInt(rewritten.length)
                        .putInt((int) p.wireLength()).array());
                out.write(rewritten);
            }
        }
        queue.clear();
        batchFrames.clear();
        batchLinks.clear();
    }

    /** A frame of a link type no decoder exists for: the reference refuses it, the neXus modes zero it. */
    private byte[] unsupported(byte[] raw) {
        if (frames.mode() == FrameRewriter.Mode.REFERENCE) {
            throw new PcapException("Unsupported or incomplete L4/tunnel header; cannot safely anonymize this frame");
        }
        if (frames.mode() == FrameRewriter.Mode.REVERT) return raw;
        return new byte[raw.length];
    }

    private byte[] readExact(long size) throws IOException {
        if (size < 0 || size > MAX_RECORD) throw new PcapException("Invalid or oversized capture record");
        byte[] data = in.readNBytes((int) size);
        if (data.length != size) throw new PcapException("Damaged capture record; no output published");
        return data;
    }

    private static byte[] u32(ByteOrder order, long value) {
        return ByteBuffer.allocate(4).order(order).putInt((int) value).array();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String hex(byte[] b) {
        return java.util.HexFormat.of().formatHex(b);
    }
}
