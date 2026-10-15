package com.securityexpert.nexus.ui2.sanitizer.pcap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.sanitizer.Sanitizer;
import com.securityexpert.nexus.ui2.sanitizer.TestVault;
import com.securityexpert.nexus.ui2.sanitizer.core.Addresses;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.SanitizerKey;
import com.securityexpert.nexus.ui2.sanitizer.format.FileType;

/**
 * The neXus pcap mode (DATA_SANITIZER_CONTRACT.md §1): structure, timing, lengths and TCP/UDP/ICMP header fields
 * kept; addresses (IPv4, IPv6, ARP, quoted inner headers) mapped; payload after the L4 header zeroed; checksums
 * valid again; DNS names replaced by same-length pseudonyms; revert restores addresses and names. When tshark is
 * installed the output is also checked with it (frame count, timestamps, lengths, seq/ack/flags, checksum status).
 */
class NexusPcapTest {

    private static final byte[] SECRET = "PLANTED-private-host-secret".getBytes(StandardCharsets.US_ASCII);

    @TempDir Path dir;
    private final TestVault vault = new TestVault();
    private final Mappers mappers = new Mappers(SanitizerKey.of(TestVault.key()), KnownNames.NONE);

    @Test
    void sanitizedCaptureKeepsStructureMapsAddressesAndZeroesPayload() throws Exception {
        List<byte[]> frames = frames();
        Path in = dir.resolve("in.cap");
        Files.write(in, pcap(frames));
        Path out = dir.resolve("out.cap");
        Sanitizer.Result result = new Sanitizer(mappers).sanitize(in, out, vault);
        assertEquals(FileType.PCAP, result.type());
        assertEquals(frames.size(), result.units());
        byte[] data = Files.readAllBytes(out);
        assertEquals(Files.size(in), data.length, "zero mode keeps every length");
        assertFalse(contains(data, SECRET), "payload removed");
        for (String a : List.of("192.0.2.10", "198.51.100.20", "203.0.113.53", "192.0.2.80")) {
            assertFalse(contains(data, Addresses.parse(a)), "address bytes gone");
        }
        assertFalse(contains(data, Addresses.parse("2001:db8:1::10")));
        assertFalse(contains(data, "example".getBytes(StandardCharsets.US_ASCII)), "DNS labels replaced");
        assertTrue(contains(data, "com".getBytes(StandardCharsets.US_ASCII)), "public suffix kept");
        List<byte[]> outFrames = records(data);
        List<byte[]> inFrames = records(Files.readAllBytes(in));
        assertEquals(inFrames.size(), outFrames.size());
        for (int i = 0; i < inFrames.size(); i++) {
            assertEquals(inFrames.get(i).length, outFrames.get(i).length, "frame " + i + " length");
        }
        assertEquals(timestamps(Files.readAllBytes(in)), timestamps(data));
        // TCP header fields (seq, ack, flags, window) unchanged; the mapped addresses follow the permutation.
        byte[] tcpIn = inFrames.get(0);
        byte[] tcpOut = outFrames.get(0);
        assertArrayEquals(Arrays.copyOfRange(tcpIn, 34, 50), Arrays.copyOfRange(tcpOut, 34, 50));
        assertArrayEquals(mappers.ip().map(Arrays.copyOfRange(tcpIn, 26, 30)), Arrays.copyOfRange(tcpOut, 26, 30));
        assertTrue(isZero(tcpOut, 54, tcpOut.length), "bytes after the TCP header are zero");
        assertEquals(0, ipChecksum(tcpOut, 14), "IPv4 header checksum valid");
        assertEquals(0, l4Checksum(tcpOut, 14, 6), "TCP checksum valid");
        assertEquals(0, l4Checksum(outFrames.get(1), 14, 17), "UDP checksum valid after the DNS rewrite");
        // ARP addresses are mapped; the OUI of every MAC is kept.
        byte[] arp = outFrames.get(3);
        assertArrayEquals(mappers.ip().map(Addresses.parse("192.0.2.10")), Arrays.copyOfRange(arp, 28, 32));
        assertArrayEquals(new byte[] {0x00, 0x1a, 0x2b}, Arrays.copyOfRange(arp, 22, 25));
        assertTrue(result.counts().get("dns_name") >= 2);
        assertTrue(result.counts().get("payload_bytes_zeroed") > 0);
    }

    @Test
    void revertRestoresAddressesAndDnsNamesButNotThePayload() throws Exception {
        List<byte[]> frames = frames();
        Path in = dir.resolve("in.cap");
        Files.write(in, pcap(frames));
        Path out = dir.resolve("out.cap");
        Path back = dir.resolve("back.cap");
        Sanitizer sanitizer = new Sanitizer(mappers);
        sanitizer.sanitize(in, out, vault);
        sanitizer.revert(out, back, vault);
        List<byte[]> original = records(Files.readAllBytes(in));
        List<byte[]> reverted = records(Files.readAllBytes(back));
        // TCP over IPv4: the header is the original header except its checksum, which now covers the zeroed payload.
        assertArrayEquals(Arrays.copyOfRange(original.get(0), 0, 50), Arrays.copyOfRange(reverted.get(0), 0, 50));
        assertArrayEquals(Arrays.copyOfRange(original.get(0), 52, 54), Arrays.copyOfRange(reverted.get(0), 52, 54));
        // The DNS response: every byte back except nothing -- names, A data and header were all kept.
        assertArrayEquals(original.get(1), reverted.get(1));
        assertArrayEquals(original.get(2), reverted.get(2));
        assertArrayEquals(original.get(3), reverted.get(3), "ARP comes back whole");
        assertArrayEquals(Arrays.copyOfRange(original.get(5), 0, 54 + 16), Arrays.copyOfRange(reverted.get(5), 0, 54 + 16),
                "IPv6 header and TCP header up to its checksum");
        assertEquals(0, l4Checksum(reverted.get(5), 14, 6));
        assertTrue(isZero(reverted.get(0), 54, reverted.get(0).length));
        assertEquals(0, l4Checksum(reverted.get(0), 14, 6), "checksum follows the restored addresses");
    }

    @Test
    void pcapngOptionsAndUnknownBlocksAreDroppedWhileTimestampsStay() throws Exception {
        List<byte[]> frames = frames();
        byte[] ng = pcapng(frames.subList(0, 2));
        Path in = dir.resolve("in.ng");
        Files.write(in, ng);
        Path out = dir.resolve("out.ng");
        Sanitizer.Result result = new Sanitizer(mappers).sanitize(in, out, vault);
        assertEquals(FileType.PCAPNG, result.type());
        byte[] data = Files.readAllBytes(out);
        assertFalse(contains(data, SECRET), "comments and the custom block are dropped");
        assertEquals(2, result.units());
    }

    @Test
    void framesOutsideTheHeaderModelAreZeroedNotRefused() throws Exception {
        byte[] lldp = new byte[64];
        System.arraycopy(HexFormat.of().parseHex("0180c200000e001a2b3c4d5e88cc"), 0, lldp, 0, 14);
        System.arraycopy(SECRET, 0, lldp, 20, SECRET.length);
        byte[] fragment = ipv4Frame(17, "192.0.2.10", "198.51.100.20", SECRET, 0x0010);
        Path in = dir.resolve("odd.cap");
        Files.write(in, pcap(List.of(lldp, fragment)));
        Path out = dir.resolve("odd.out");
        Sanitizer.Result result = new Sanitizer(mappers).sanitize(in, out, vault);
        byte[] data = Files.readAllBytes(out);
        assertFalse(contains(data, SECRET));
        assertFalse(contains(data, Addresses.parse("192.0.2.10")), "a non-first fragment keeps a mapped IP header");
        assertEquals(1L, result.counts().get("frames_without_ip"));
    }

    @Test
    void tsharkReadsTheOutputWithEveryStructuralFieldUnchanged() throws Exception {
        Path tshark = tshark();
        Assumptions.assumeTrue(tshark != null, "tshark not installed");
        Path in = dir.resolve("in.pcap");
        Files.write(in, pcap(frames()));
        Path out = dir.resolve("out.pcap");
        new Sanitizer(mappers).sanitize(in, out, vault);
        List<String> fields = List.of("frame.time_epoch", "frame.len", "frame.cap_len", "tcp.seq_raw", "tcp.ack_raw",
                "tcp.flags", "tcp.window_size_value", "udp.length", "ip.id", "ip.ttl", "ipv6.hlim", "dns.flags",
                "dns.count.queries", "dns.count.answers");
        List<String> before = tsharkFields(tshark, in, fields, false);
        List<String> after = tsharkFields(tshark, out, fields, false);
        assertEquals(frames().size(), after.size());
        assertEquals(before, after, "frame count, timestamps, lengths, TCP seq/ack/flags unchanged");
        List<String> status = tsharkFields(tshark, out, List.of("ip.checksum.status", "tcp.checksum.status", "udp.checksum.status"), true);
        for (String row : status) {
            for (String value : row.split("\t", -1)) {
                for (String v : value.split(",")) assertTrue(v.isEmpty() || v.equals("1"), "checksum good: " + row);
            }
        }
        List<String> names = tsharkFields(tshark, out, List.of("dns.qry.name"), false);
        assertTrue(names.stream().anyMatch(n -> n.matches("[a-z][a-z0-9]{2}\\.[a-z][a-z0-9]{6}\\.com")), names.toString());
    }

    @Test
    void everyReferenceFixtureSanitizesInTheNexusModeAndStaysStructurallyIdenticalForTshark() throws Exception {
        String manifest;
        try (var in = getClass().getResourceAsStream("/pcap-parity/manifest.json")) {
            manifest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var m = java.util.regex.Pattern.compile("\"name\": \"([^\"]+)\",\\s*\"input_base64\": \"([^\"]*)\"").matcher(manifest);
        Path tshark = tshark();
        int sanitized = 0;
        while (m.find()) {
            if (m.group(1).equals("damaged-tail")) continue;
            byte[] input = java.util.Base64.getDecoder().decode(m.group(2));
            Path in = dir.resolve(m.group(1) + ".in");
            Path out = dir.resolve(m.group(1) + ".out");
            Files.write(in, input);
            new Sanitizer(mappers).sanitize(in, out, vault);
            sanitized++;
            byte[] data = Files.readAllBytes(out);
            assertFalse(contains(data, SECRET), m.group(1) + ": payload removed");
            for (String a : List.of("192.0.2.123", "198.51.100.231", "192.0.2.10", "192.0.2.20")) {
                assertFalse(contains(data, Addresses.parse(a)), m.group(1) + ": inner and outer addresses mapped");
            }
            if (tshark != null) {
                List<String> fields = List.of("frame.time_epoch", "frame.len", "frame.cap_len", "tcp.seq_raw", "tcp.ack_raw",
                        "tcp.flags", "ip.id", "ip.ttl", "ipv6.hlim", "icmp.type", "icmpv6.type");
                assertEquals(tsharkFields(tshark, in, fields, false), tsharkFields(tshark, out, fields, false), m.group(1));
            }
        }
        assertEquals(19, sanitized, "the neXus mode refuses nothing but a damaged container");
    }

    // ---- synthetic frames ----------------------------------------------------------------------------------

    static List<byte[]> frames() {
        List<byte[]> out = new ArrayList<>();
        out.add(tcpFrame("192.0.2.10", "198.51.100.20", 41000, 443, 123456, 654321, 0x18, SECRET));
        out.add(udpFrame("203.0.113.53", "192.0.2.10", 53, 53000, dnsResponse()));
        out.add(udpFrame("192.0.2.10", "203.0.113.53", 53001, 53, dnsQuery()));
        out.add(arpFrame());
        out.add(icmpUnreachable());
        out.add(tcp6Frame("2001:db8:1::10", "2001:db8:2::20"));
        return out;
    }

    static byte[] eth(int type) {
        byte[] e = new byte[14];
        System.arraycopy(HexFormat.of().parseHex("001a2b3c4d5e001a2b3c4d5f"), 0, e, 0, 12);
        e[12] = (byte) (type >> 8);
        e[13] = (byte) type;
        return e;
    }

    static byte[] ipv4Frame(int proto, String src, String dst, byte[] l4, int fragment) {
        ByteBuffer b = ByteBuffer.allocate(14 + 20 + l4.length);
        b.put(eth(0x0800));
        b.put((byte) 0x45).put((byte) 0).putShort((short) (20 + l4.length)).putShort((short) 777).putShort((short) fragment)
                .put((byte) 64).put((byte) proto).putShort((short) 0).put(Addresses.parse(src)).put(Addresses.parse(dst));
        b.put(l4);
        byte[] f = b.array();
        int c = 0xffff & ~sum(f, 14, 34, 0);
        f[24] = (byte) (c >> 8);
        f[25] = (byte) c;
        if (proto == 6 || proto == 17) {
            int at = proto == 6 ? 34 + 16 : 34 + 6;
            int l = l4Sum(f, 14, proto);
            int cs = 0xffff & ~l;
            if (proto == 17 && cs == 0) cs = 0xffff;
            f[at] = (byte) (cs >> 8);
            f[at + 1] = (byte) cs;
        }
        return f;
    }

    static byte[] tcpFrame(String src, String dst, int sport, int dport, int seq, int ack, int flags, byte[] payload) {
        ByteBuffer t = ByteBuffer.allocate(20 + payload.length);
        t.putShort((short) sport).putShort((short) dport).putInt(seq).putInt(ack).put((byte) 0x50).put((byte) flags)
                .putShort((short) 65535).putShort((short) 0).putShort((short) 0).put(payload);
        return ipv4Frame(6, src, dst, t.array(), 0x4000);
    }

    static byte[] udpFrame(String src, String dst, int sport, int dport, byte[] payload) {
        ByteBuffer u = ByteBuffer.allocate(8 + payload.length);
        u.putShort((short) sport).putShort((short) dport).putShort((short) (8 + payload.length)).putShort((short) 0).put(payload);
        return ipv4Frame(17, src, dst, u.array(), 0x4000);
    }

    static byte[] dnsQuery() {
        ByteBuffer b = ByteBuffer.allocate(512);
        b.putShort((short) 0x1234).putShort((short) 0x0100).putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        b.put(name("www.example.com")).putShort((short) 1).putShort((short) 1);
        return Arrays.copyOf(b.array(), b.position());
    }

    static byte[] dnsResponse() {
        ByteBuffer b = ByteBuffer.allocate(512);
        b.putShort((short) 0x1234).putShort((short) 0x8180).putShort((short) 1).putShort((short) 2).putShort((short) 0).putShort((short) 0);
        b.put(name("www.example.com")).putShort((short) 1).putShort((short) 1);
        // CNAME www -> web.example.com (compressed suffix), then A for it.
        b.putShort((short) 0xc00c).putShort((short) 5).putShort((short) 1).putInt(300);
        byte[] cname = concat(new byte[] {3, 'w', 'e', 'b'}, new byte[] {(byte) 0xc0, 16});
        b.putShort((short) cname.length).put(cname);
        int cnameAt = 12 + 17 + 4 + 12;
        b.putShort((short) (0xc000 | cnameAt)).putShort((short) 1).putShort((short) 1).putInt(300).putShort((short) 4)
                .put(Addresses.parse("192.0.2.80"));
        return Arrays.copyOf(b.array(), b.position());
    }

    static byte[] name(String dotted) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String label : dotted.split("\\.")) {
            out.write(label.length());
            out.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
        }
        out.write(0);
        return out.toByteArray();
    }

    static byte[] arpFrame() {
        ByteBuffer b = ByteBuffer.allocate(60);
        b.put(HexFormat.of().parseHex("ffffffffffff001a2b3c4d5e0806"));
        b.putShort((short) 1).putShort((short) 0x0800).put((byte) 6).put((byte) 4).putShort((short) 1)
                .put(HexFormat.of().parseHex("001a2b3c4d5e")).put(Addresses.parse("192.0.2.10"))
                .put(new byte[6]).put(Addresses.parse("192.0.2.1"));
        return b.array();
    }

    static byte[] icmpUnreachable() {
        byte[] inner = tcpFrame("198.51.100.20", "192.0.2.10", 443, 41000, 1, 2, 0x12, SECRET);
        byte[] quote = Arrays.copyOfRange(inner, 14, 14 + 28);
        byte[] icmp = concat(new byte[] {3, 1, 0, 0, 0, 0, 0, 0}, quote);
        int c = 0xffff & ~sum(icmp, 0, icmp.length, 0);
        icmp[2] = (byte) (c >> 8);
        icmp[3] = (byte) c;
        return ipv4Frame(1, "192.0.2.1", "198.51.100.20", icmp, 0x4000);
    }

    static byte[] tcp6Frame(String src, String dst) {
        ByteBuffer t = ByteBuffer.allocate(20 + SECRET.length);
        t.putShort((short) 40000).putShort((short) 22).putInt(7).putInt(9).put((byte) 0x50).put((byte) 0x10)
                .putShort((short) 1000).putShort((short) 0).putShort((short) 0).put(SECRET);
        byte[] tcp = t.array();
        ByteBuffer b = ByteBuffer.allocate(14 + 40 + tcp.length);
        b.put(eth(0x86dd)).putInt(0x60000000).putShort((short) tcp.length).put((byte) 6).put((byte) 64)
                .put(Addresses.parse(src)).put(Addresses.parse(dst)).put(tcp);
        byte[] f = b.array();
        int pseudo = sum(f, 22, 54, 0) + tcp.length + 6;
        int cs = 0xffff & ~fold(sum(f, 54, f.length, pseudo));
        f[54 + 16] = (byte) (cs >> 8);
        f[54 + 17] = (byte) cs;
        return f;
    }

    // ---- containers ----------------------------------------------------------------------------------------

    static byte[] pcap(List<byte[]> frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0xa1b2c3d4).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(1);
        out.writeBytes(h.array());
        for (int i = 0; i < frames.size(); i++) {
            byte[] f = frames.get(i);
            ByteBuffer r = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            r.putInt(1700000000 + i).putInt(1000 * i + 7).putInt(f.length).putInt(f.length + (i == 0 ? 100 : 0));
            out.writeBytes(r.array());
            out.writeBytes(f);
        }
        return out.toByteArray();
    }

    static byte[] pcapng(List<byte[]> frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] comment = option(1, SECRET);
        out.writeBytes(block(0x0A0D0D0A, concat(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0x1A2B3C4D)
                .putShort((short) 1).putShort((short) 0).putLong(-1).array(), concat(comment, new byte[4]))));
        out.writeBytes(block(1, concat(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1)
                .putShort((short) 0).putInt(65535).array(), concat(comment, new byte[4]))));
        for (int i = 0; i < frames.size(); i++) {
            byte[] f = frames.get(i);
            long stamp = 1700000000000000L + i * 1000L;
            ByteBuffer b = ByteBuffer.allocate(20 + f.length + (4 - f.length % 4) % 4).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(0).putInt((int) (stamp >>> 32)).putInt((int) stamp).putInt(f.length).putInt(f.length).put(f);
            out.writeBytes(block(6, concat(b.array(), concat(comment, new byte[4]))));
        }
        out.writeBytes(block(0xBAD, concat(SECRET, new byte[(4 - SECRET.length % 4) % 4])));
        return out.toByteArray();
    }

    static byte[] option(int code, byte[] value) {
        ByteBuffer b = ByteBuffer.allocate(4 + value.length + (4 - value.length % 4) % 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) code).putShort((short) value.length).put(value);
        return b.array();
    }

    static byte[] block(int kind, byte[] body) {
        int size = body.length + 12;
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).putInt(kind).putInt(size).put(body).putInt(size).array();
    }

    static List<byte[]> records(byte[] pcap) {
        List<byte[]> out = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(pcap).order(ByteOrder.LITTLE_ENDIAN);
        int at = 24;
        while (at + 16 <= pcap.length) {
            int caplen = b.getInt(at + 8);
            out.add(Arrays.copyOfRange(pcap, at + 16, at + 16 + caplen));
            at += 16 + caplen;
        }
        return out;
    }

    static List<String> timestamps(byte[] pcap) {
        List<String> out = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(pcap).order(ByteOrder.LITTLE_ENDIAN);
        int at = 24;
        while (at + 16 <= pcap.length) {
            out.add(b.getInt(at) + "." + b.getInt(at + 4) + "/" + b.getInt(at + 12));
            at += 16 + b.getInt(at + 8);
        }
        return out;
    }

    // ---- checksums -----------------------------------------------------------------------------------------

    static int sum(byte[] d, int from, int to, int initial) {
        long s = initial;
        for (int i = from; i < to; i += 2) s += ((d[i] & 0xff) << 8) | (i + 1 < to ? d[i + 1] & 0xff : 0);
        return fold(s);
    }

    static int fold(long s) {
        while ((s >> 16) != 0) s = (s & 0xffff) + (s >> 16);
        return (int) s;
    }

    static int ipChecksum(byte[] f, int ip) {
        return 0xffff & ~sum(f, ip, ip + 20, 0);
    }

    static int l4Sum(byte[] f, int ip, int proto) {
        int length = (((f[ip + 2] & 0xff) << 8) | (f[ip + 3] & 0xff)) - 20;
        int pseudo = sum(f, ip + 12, ip + 20, 0) + proto + length;
        return fold(sum(f, ip + 20, ip + 20 + length, pseudo));
    }

    static int l4Checksum(byte[] f, int ip, int proto) {
        if ((f[ip] >> 4) == 6) {
            int length = ((f[ip + 4] & 0xff) << 8) | (f[ip + 5] & 0xff);
            int pseudo = sum(f, ip + 8, ip + 40, 0) + length + proto;
            return 0xffff & ~fold(sum(f, ip + 40, ip + 40 + length, pseudo));
        }
        return 0xffff & ~l4Sum(f, ip, proto);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    static boolean isZero(byte[] d, int from, int to) {
        for (int i = from; i < to; i++) if (d[i] != 0) return false;
        return true;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static Path tshark() {
        String env = System.getenv("TSHARK");
        for (String candidate : new String[] {env, "/Applications/Wireshark.app/Contents/MacOS/tshark", "/usr/bin/tshark",
                "/usr/local/bin/tshark", "/opt/homebrew/bin/tshark"}) {
            if (candidate != null && Files.isExecutable(Path.of(candidate))) return Path.of(candidate);
        }
        return null;
    }

    static List<String> tsharkFields(Path tshark, Path file, List<String> fields, boolean checkChecksums)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(tshark.toString(), "-n", "-r", file.toString()));
        if (checkChecksums) {
            command.addAll(List.of("-o", "ip.check_checksum:TRUE", "-o", "tcp.check_checksum:TRUE", "-o", "udp.check_checksum:TRUE"));
        }
        command.addAll(List.of("-T", "fields"));
        for (String f : fields) command.addAll(List.of("-e", f));
        Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), "tshark parses the file");
        return text.lines().toList();
    }

    static Map<String, String> none() {
        return Map.of();
    }
}
