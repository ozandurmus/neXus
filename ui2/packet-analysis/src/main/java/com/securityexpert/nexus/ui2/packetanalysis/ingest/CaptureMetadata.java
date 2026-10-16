package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Container framing only (pcap, pcapng, snoop): interfaces, snaplen, link type, drop counters, complete records,
 * zero-filled tails and damaged final records. Protocol dissection always belongs to tshark.
 *
 * <p>Byte-level port of the reference walker. The result is an insertion-ordered map with the reference keys and
 * key order, because it becomes the start of the capture's {@code inventory} JSON. Integers are {@link Long}
 * (an unsigned 64-bit drop counter wider than a long is a {@link BigInteger}); absent values are {@code null}.
 */
public final class CaptureMetadata {

    private static final int ZERO_SCAN_CHUNK = 1024 * 1024;

    private final FileChannel channel;
    private final long size;
    private final Cancellation cancel;
    private final Map<String, Object> result = new LinkedHashMap<>();
    /** The reference file object's position ({@code f.tell()}), which may lie past the end after a seek. */
    private long pos;
    private long zeroStart;

    private CaptureMetadata(FileChannel channel, long size, Cancellation cancel) {
        this.channel = channel;
        this.size = size;
        this.cancel = cancel == null ? Cancellation.NONE : cancel;
    }

    /** The reference {@code metadata(path, cancel)}. Malformed framing raises IllegalArgumentException. */
    public static Map<String, Object> read(Path path, Cancellation cancel) throws IOException {
        long size = Files.size(path);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            CaptureMetadata m = new CaptureMetadata(ch, size, cancel);
            m.walk();
            return m.result;
        }
    }

    private void checkCancel() {
        if (cancel.isSet()) {
            throw new IngestCancelledException("Ingest cancelled");
        }
    }

    private byte[] read(int n) throws IOException {
        if (pos >= size || n <= 0) {
            return new byte[0];
        }
        int want = (int) Math.min(n, size - pos);
        ByteBuffer b = ByteBuffer.allocate(want);
        while (b.hasRemaining()) {
            int r = channel.read(b, pos + b.position());
            if (r < 0) {
                break;
            }
        }
        byte[] out = Arrays.copyOf(b.array(), b.position());
        pos += out.length;
        return out;
    }

    private void seek(long p) {
        pos = p;
    }

    /** One backwards scan avoids rescanning long zero runs in the middle of a file. */
    private long zeroSuffixStart() throws IOException {
        long end = size;
        while (end != 0) {
            checkCancel();
            long start = Math.max(0, end - ZERO_SCAN_CHUNK);
            seek(start);
            byte[] data = read((int) (end - start));
            int n = data.length;
            while (n > 0 && data[n - 1] == 0) {
                n--;
            }
            if (n > 0) {
                return start + n;
            }
            end = start;
        }
        return 0;
    }

    private boolean zeroTail(long start, int recordSize) {
        if (start < zeroStart || start >= size) {
            return false;
        }
        long count = (size - start) / recordSize;
        result.put("zero_tail_bytes", size - start);
        result.put("zero_tail_records", count);
        result.put("zero_tail_record_size", (long) recordSize);
        result.put("frame_limit", result.get("complete_records"));
        if ((size - start) % recordSize != 0) {
            result.put("damaged_tail", true);
        }
        return true;
    }

    private void incrementRecords() {
        result.put("complete_records", (Long) result.get("complete_records") + 1);
    }

    private static Map<String, Object> iface(long id, long section, Long snaplen, long linkType, String name) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("section", section);
        item.put("snaplen", snaplen);
        item.put("link_type", linkType);
        item.put("name", name);
        return item;
    }

    private static ByteBuffer buf(byte[] data, ByteOrder order) {
        return ByteBuffer.wrap(data).order(order);
    }

    private static long u32(ByteBuffer b, int at) {
        return Integer.toUnsignedLong(b.getInt(at));
    }

    private static int u16(ByteBuffer b, int at) {
        return Short.toUnsignedInt(b.getShort(at));
    }

    private void walk() throws IOException {
        result.put("format", "unknown");
        result.put("interfaces", new ArrayList<Object>());
        result.put("ifdrop", null);
        result.put("osdrop", null);
        result.put("complete_records", 0L);
        zeroStart = zeroSuffixStart();
        seek(0);
        byte[] magic = read(4);
        ByteOrder pcapOrder = pcapOrder(magic);
        if (Arrays.equals(magic, "snoo".getBytes(StandardCharsets.ISO_8859_1))) {
            snoop();
            return;
        }
        if (pcapOrder != null) {
            pcap(pcapOrder);
            return;
        }
        if (!Arrays.equals(magic, new byte[] {0x0a, 0x0d, 0x0d, 0x0a})) {
            throw new IllegalArgumentException("Only pcap, pcapng and snoop containers are supported");
        }
        pcapng();
    }

    static ByteOrder pcapOrder(byte[] magic) {
        if (magic.length != 4) {
            return null;
        }
        int m = ((magic[0] & 0xff) << 24) | ((magic[1] & 0xff) << 16) | ((magic[2] & 0xff) << 8) | (magic[3] & 0xff);
        return switch (m) {
            case 0xd4c3b2a1, 0x4d3cb2a1 -> ByteOrder.LITTLE_ENDIAN;
            case 0xa1b2c3d4, 0xa1b23c4d -> ByteOrder.BIG_ENDIAN;
            default -> null;
        };
    }

    private void snoop() throws IOException {
        if (!Arrays.equals(read(4), new byte[] {'p', 0, 0, 0})) {
            throw new IllegalArgumentException("Invalid snoop header");
        }
        byte[] header = read(8);
        if (header.length != 8) {
            throw new IllegalArgumentException("Truncated snoop header");
        }
        ByteBuffer h = buf(header, ByteOrder.BIG_ENDIAN);
        long version = u32(h, 0);
        long network = u32(h, 4);
        if (version != 2) {
            throw new IllegalArgumentException("Unsupported snoop version");
        }
        result.put("format", "snoop");
        result.put("adapter", "checkpoint");
        result.put("ifdrop", 0L);
        result.put("interfaces", new ArrayList<Object>(List.of(iface(0, 0, null, network, "snoop network type"))));
        while (pos < size) {
            checkCancel();
            long start = pos;
            if (zeroTail(start, 24)) {
                break;
            }
            byte[] record = read(24);
            if (record.length < 24) {
                result.put("damaged_tail", true);
                break;
            }
            ByteBuffer r = buf(record, ByteOrder.BIG_ENDIAN);
            long original = u32(r, 0);
            long included = u32(r, 4);
            long length = u32(r, 8);
            long drops = u32(r, 12);
            if (length < 24 || length % 4 != 0 || included > length - 24 || included > original) {
                result.put("damaged_tail", true);
                break;
            }
            if (start + length > size) {
                result.put("damaged_tail", true);
                break;
            }
            incrementRecords();
            result.put("ifdrop", Math.max((Long) result.get("ifdrop"), drops));
            seek(start + length);
        }
    }

    private void pcap(ByteOrder order) throws IOException {
        byte[] rest = read(20);
        if (rest.length != 20) {
            throw new IllegalArgumentException("Truncated pcap header");
        }
        ByteBuffer h = buf(rest, order);
        long snaplen = u32(h, 12);
        long link = u32(h, 16);
        result.put("format", "pcap");
        result.put("interfaces", new ArrayList<Object>(List.of(iface(0, 0, snaplen, link & 0xFFFF, null))));
        while (pos < size) {
            checkCancel();
            long start = pos;
            if (zeroTail(start, 16)) {
                break;
            }
            byte[] header = read(16);
            if (header.length < 16) {
                result.put("damaged_tail", true);
                break;
            }
            long caplen = u32(buf(header, order), 8);
            if (start + 16 + caplen > size) {
                result.put("damaged_tail", true);
                break;
            }
            seek(pos + caplen);
            incrementRecords();
        }
    }

    private void pcapng() throws IOException {
        result.put("format", "pcapng");
        seek(0);
        ByteOrder order = ByteOrder.LITTLE_ENDIAN;
        long section = -1;
        List<Map<String, Object>> local = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Object> interfaces = (List<Object>) result.get("interfaces");
        // (section, interface, option code) -> max counter; insertion order kept like the reference dict.
        Map<List<Long>, BigInteger> counters = new LinkedHashMap<>();
        while (pos < size) {
            checkCancel();
            long start = pos;
            if (zeroTail(start, 12)) {
                break;
            }
            byte[] head = read(12);
            if (head.length != 12) {
                result.put("damaged_tail", true);
                break;
            }
            if (head[0] == 0x0a && head[1] == 0x0d && head[2] == 0x0d && head[3] == 0x0a) {
                byte[] bom = Arrays.copyOfRange(head, 8, 12);
                boolean little = Arrays.equals(bom, new byte[] {0x4d, 0x3c, 0x2b, 0x1a});
                boolean big = Arrays.equals(bom, new byte[] {0x1a, 0x2b, 0x3c, 0x4d});
                if (!little && !big) {
                    throw new IllegalArgumentException("Invalid pcapng byte order");
                }
                order = little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
                section++;
                local = new ArrayList<>();
            }
            ByteBuffer hb = buf(head, order);
            long kind = u32(hb, 0);
            long length = u32(hb, 4);
            if (start + length > size) {
                result.put("damaged_tail", true);
                break;
            }
            if (length < 12 || length % 4 != 0) {
                throw new IllegalArgumentException("Invalid pcapng block length");
            }
            seek(start + length - 4);
            byte[] trailer = read(4);
            if (u32(buf(trailer, order), 0) != length) {
                if (start + length == size) {
                    result.put("damaged_tail", true);
                    break;
                }
                throw new IllegalArgumentException("Mismatched pcapng block trailer");
            }
            if (kind == 1 || kind == 5) {
                if (length > 1024 * 1024) {
                    throw new IllegalArgumentException("Metadata block exceeds 1 MiB safety limit");
                }
                seek(start + 8);
                byte[] body = read((int) (length - 12));
                ByteBuffer bb = buf(body, order);
                if (kind == 1) {
                    if (body.length < 8) {
                        throw new IllegalArgumentException("Truncated interface description");
                    }
                    int link = u16(bb, 0);
                    long snaplen = u32(bb, 4);
                    Map<String, Object> item = iface(local.size(), section, snaplen, link, null);
                    for (Option o : options(Arrays.copyOfRange(body, 8, body.length), order)) {
                        if (o.code == 2) {
                            item.put("name", new String(o.value, StandardCharsets.UTF_8));
                        }
                    }
                    local.add(item);
                    interfaces.add(item);
                } else {
                    if (body.length < 12) {
                        throw new IllegalArgumentException("Truncated interface statistics");
                    }
                    long ifaceId = u32(bb, 0);
                    for (Option o : options(Arrays.copyOfRange(body, 12, body.length), order)) {
                        if ((o.code == 5 || o.code == 7) && o.value.length == 8) {
                            List<Long> key = List.of(section, ifaceId, (long) o.code);
                            BigInteger v = new BigInteger(1, reverseIfLittle(o.value, order));
                            BigInteger old = counters.getOrDefault(key, BigInteger.ZERO);
                            counters.put(key, old.max(v));
                        }
                    }
                }
            }
            if (kind == 2 || kind == 3 || kind == 6) {
                incrementRecords();
            }
            seek(start + length);
        }
        String[][] names = {{"5", "ifdrop"}, {"7", "osdrop"}};
        for (String[] n : names) {
            long code = Long.parseLong(n[0]);
            BigInteger sum = null;
            for (Map.Entry<List<Long>, BigInteger> e : counters.entrySet()) {
                if (e.getKey().get(2) == code) {
                    sum = sum == null ? e.getValue() : sum.add(e.getValue());
                }
            }
            if (sum != null) {
                result.put(n[1], sum.bitLength() < 64 ? (Object) sum.longValue() : sum);
            }
        }
        result.put("multiple_sections", section > 0);
    }

    private static byte[] reverseIfLittle(byte[] v, ByteOrder order) {
        byte[] out = v.clone();
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int i = 0; i < out.length / 2; i++) {
                byte t = out[i];
                out[i] = out[out.length - 1 - i];
                out[out.length - 1 - i] = t;
            }
        }
        return out;
    }

    record Option(int code, byte[] value) {
    }

    /**
     * pcapng options up to opt_endofopt. The reference generator raises when it reaches a malformed option, after
     * yielding the earlier ones; every caller consumes the whole sequence, so raising up front is equivalent.
     */
    static List<Option> options(byte[] data, ByteOrder order) {
        List<Option> out = new ArrayList<>();
        ByteBuffer b = buf(data, order);
        int p = 0;
        while (p + 4 <= data.length) {
            int code = u16(b, p);
            int size = u16(b, p + 2);
            p += 4;
            if (code == 0) {
                break;
            }
            if (p + size > data.length) {
                throw new IllegalArgumentException("Malformed pcapng option");
            }
            out.add(new Option(code, Arrays.copyOfRange(data, p, p + size)));
            p += (size + 3) & ~3;
        }
        return out;
    }
}
