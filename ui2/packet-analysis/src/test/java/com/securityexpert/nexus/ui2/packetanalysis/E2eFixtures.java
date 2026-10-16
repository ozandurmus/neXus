package com.securityexpert.nexus.ui2.packetanalysis;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyRound;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The synthetic captures of {@link PacketAnalysisEndToEndTest}, built at test time (captures are never committed):
 *
 * <ul>
 * <li>{@code folder_b}: byte for byte the reference golden test's {@code _build_folder_b} (six captures, two
 * paths, a host capture with offload superframes); case mode, automatic assembly;</li>
 * <li>{@code chain_loss}: one TCP connection through client, firewall and server captures (TTL steps, 30 us
 * transit), a request lost between firewall and server and retransmitted; analysed with a three-point topology;</li>
 * <li>{@code nat_confirm}: the same connection through a source-NAT firewall captured on both sides; analysed,
 * every NAT suggestion confirmed, analysed again.</li>
 * </ul>
 *
 * {@code main(OUT)} writes every fixture to {@code OUT/<name>/} (captures, {@code topology.json} for the topology
 * fixtures) for {@code tools/packet_analysis/parity/e2e_fixtures.py}, which runs the reference on the same bytes.
 * Synthetic documentation-range addresses only (the reference builder's own addresses for {@code folder_b}).
 */
public final class E2eFixtures {
    public static final List<String> NAMES = List.of("folder_b", "chain_loss", "nat_confirm");
    static final String CLIENT = "198.51.100.10";
    static final String SERVER = "203.0.113.20";
    static final String NAT_SOURCE = "192.0.2.50";

    /** One fixture on disk: capture files in ingest order, the topology (null in case mode). */
    public record Fixture(Path dir, List<Path> files, Path topology, boolean confirmNat) {
    }

    private record Frame(double ts, byte[] bytes) {
    }

    private E2eFixtures() {
    }

    public static void main(String[] args) throws IOException {
        for (String name : NAMES) {
            write(name, Path.of(args[0]).resolve(name));
        }
    }

    public static Fixture write(String name, Path dir) throws IOException {
        Files.createDirectories(dir);
        return switch (name) {
            case "folder_b" -> folderB(dir);
            case "chain_loss" -> chainLoss(dir);
            case "nat_confirm" -> natConfirm(dir);
            default -> throw new IllegalArgumentException(name);
        };
    }

    // ------------------------------------------------------------------ folder_b (reference _build_folder_b)

    private static Fixture folderB(Path dir) throws IOException {
        List<Frame> gen1 = new ArrayList<>();
        List<Frame> gen2 = new ArrayList<>();
        List<Frame> lb = new ArrayList<>();
        List<Frame> segin = new ArrayList<>();
        List<Frame> segout = new ArrayList<>();
        List<Frame> host = new ArrayList<>();
        String c = "10.1.0.10";
        for (int i = 0; i < 12; i++) {
            double t = 1700000100.0 + i * 0.5;
            int fwd = i == 0 ? 2 : 24;
            int rev = i == 0 ? 18 : 24;
            byte[] payFwd = i == 0 ? new byte[0] : concat(String.format("req_x_%02d_", i), "x", 60);
            byte[] payRev = i == 0 ? new byte[0] : concat(String.format("rsp_x_%02d_", i), "y", 60);
            int seqF = 1 + i * 100;
            gen1.add(new Frame(t, tcp(c, SERVER, 42100, 443, seqF, seqF, fwd, payFwd, 100 + i, 62)));
            gen2.add(new Frame(t + 0.000025, tcp(c, SERVER, 42100, 443, seqF, seqF, fwd, payFwd, 100 + i, 61)));
            host.add(new Frame(t + 0.00005, tcp(c, SERVER, 42100, 443, seqF, seqF, fwd, payFwd, 100 + i, 60)));
            double tr = t + 0.002;
            byte[] hostRev = i == 1 ? repeat("S", 2400) : payRev;
            int seqR = 500 + i * 100;
            int ackR = 50 + i * 100;
            host.add(new Frame(tr, tcp(SERVER, c, 443, 42100, seqR, ackR, rev, hostRev, 300 + i, 64)));
            gen2.add(new Frame(tr + 0.00002, tcp(SERVER, c, 443, 42100, seqR, ackR, rev, payRev, 300 + i, 61)));
            gen1.add(new Frame(tr + 0.000045, tcp(SERVER, c, 443, 42100, seqR, ackR, rev, payRev, 300 + i, 60)));
        }
        double lbOffset = -0.000120;
        String c2 = "198.51.100.10";
        for (int i = 0; i < 4; i++) {
            double t = 1700000100.005 + i * 2.0;
            int fwd = i == 0 ? 2 : 24;
            int rev = i == 0 ? 18 : 24;
            byte[] payFwd = i == 0 ? new byte[0] : concat(String.format("req_y_%02d_", i), "a", 50);
            byte[] payRev = i == 0 ? new byte[0] : concat(String.format("rsp_y_%02d_", i), "b", 50);
            int seqF = 1 + i * 80;
            lb.add(new Frame(t + lbOffset, tcp(c2, SERVER, 43100, 443, seqF, seqF, fwd, payFwd, 500 + i, 63)));
            segin.add(new Frame(t + 0.000028, tcp(c2, SERVER, 43100, 443, seqF, seqF, fwd, payFwd, 500 + i, 55)));
            segout.add(new Frame(t + 0.000048, tcp(c2, SERVER, 43100, 443, seqF, seqF, fwd, payFwd, 500 + i, 54)));
            host.add(new Frame(t + 0.000070, tcp(c2, SERVER, 43100, 443, seqF, seqF, fwd, payFwd, 500 + i, 53)));
            double tr = t + 0.002;
            byte[] hostRev = i == 1 ? repeat("T", 2200) : payRev;
            int seqR = 800 + i * 80;
            int ackR = 40 + i * 80;
            host.add(new Frame(tr, tcp(SERVER, c2, 443, 43100, seqR, ackR, rev, hostRev, 700 + i, 64)));
            segout.add(new Frame(tr + 0.000020, tcp(SERVER, c2, 443, 43100, seqR, ackR, rev, payRev, 700 + i, 63)));
            segin.add(new Frame(tr + 0.000036, tcp(SERVER, c2, 443, 43100, seqR, ackR, rev, payRev, 700 + i, 62)));
            lb.add(new Frame(tr + 0.000064 + lbOffset, tcp(SERVER, c2, 443, 43100, seqR, ackR, rev, payRev, 700 + i, 54)));
        }
        for (int i = 0; i < 15; i++) {
            host.add(new Frame(1700000100.2 + i * 0.001, tcp("192.0.2." + ((i % 20) + 1), "198.51.100.250", 54000 + i,
                    80, 10 + i, 1, 16, repeat("n", 20), 900 + i, 60)));
        }
        List<Path> files = new ArrayList<>();
        files.add(pcap(dir.resolve("GenisSegment1.pcap"), gen1));
        files.add(pcap(dir.resolve("GenisSegment2.pcap"), gen2));
        files.add(pcap(dir.resolve("LB.pcap"), lb));
        files.add(pcap(dir.resolve("Segin.pcap"), segin));
        files.add(pcap(dir.resolve("Segout.pcap"), segout));
        files.add(pcap(dir.resolve("host.pcap"), host));
        return new Fixture(dir, files, null, false);
    }

    // ------------------------------------------------------------------ chain_loss

    private static Fixture chainLoss(Path dir) throws IOException {
        List<Frame> client = new ArrayList<>();
        List<Frame> fw = new ArrayList<>();
        List<Frame> server = new ArrayList<>();
        int lost = 5;
        for (int i = 0; i < 12; i++) {
            double t = 1700000000.0 + i * 0.5;
            boolean syn = i == 0;
            int seqF = syn ? 1000 : 1001 + (i - 1) * 100;
            int ackF = syn ? 0 : 5001 + (i - 1) * 100;
            byte[] req = syn ? new byte[0] : concat(String.format("GET /item/%02d ", i), "q", 86);
            int seqR = syn ? 5000 : 5001 + (i - 1) * 100;
            int ackR = syn ? 1001 : 1001 + i * 100;
            byte[] rsp = syn ? new byte[0] : concat(String.format("200 item %02d ", i), "r", 88);
            double tf = t;
            client.add(new Frame(tf, tcp(CLIENT, SERVER, 41000, 443, seqF, ackF, syn ? 2 : 24, req, 100 + i, 64)));
            fw.add(new Frame(tf + 0.00003, tcp(CLIENT, SERVER, 41000, 443, seqF, ackF, syn ? 2 : 24, req, 100 + i, 63)));
            if (i == lost) {
                tf = t + 0.25; // retransmission after the request disappeared between firewall and server
                client.add(new Frame(tf, tcp(CLIENT, SERVER, 41000, 443, seqF, ackF, 24, req, 150, 64)));
                fw.add(new Frame(tf + 0.00003, tcp(CLIENT, SERVER, 41000, 443, seqF, ackF, 24, req, 150, 63)));
                server.add(new Frame(tf + 0.00006, tcp(CLIENT, SERVER, 41000, 443, seqF, ackF, 24, req, 150, 62)));
            } else {
                server.add(new Frame(tf + 0.00006, tcp(CLIENT, SERVER, 41000, 443, seqF, ackF, syn ? 2 : 24, req, 100 + i, 62)));
            }
            double tr = tf + 0.002;
            int flagsR = syn ? 18 : 24;
            server.add(new Frame(tr, tcp(SERVER, CLIENT, 443, 41000, seqR, ackR, flagsR, rsp, 300 + i, 64)));
            fw.add(new Frame(tr + 0.00003, tcp(SERVER, CLIENT, 443, 41000, seqR, ackR, flagsR, rsp, 300 + i, 63)));
            client.add(new Frame(tr + 0.00006, tcp(SERVER, CLIENT, 443, 41000, seqR, ackR, flagsR, rsp, 300 + i, 62)));
        }
        List<Path> files = List.of(pcap(dir.resolve("client.pcap"), client), pcap(dir.resolve("fw.pcap"), fw),
                pcap(dir.resolve("server.pcap"), server));
        String topology = """
                {"points": [
                  {"id": "c", "label": "Client", "device": "Client", "kind": "Client", "side": "both", "capture_id": "client.pcap", "translation": "none"},
                  {"id": "f", "label": "Firewall", "device": "FW", "kind": "Firewall", "side": "both", "capture_id": "fw.pcap", "translation": "none"},
                  {"id": "s", "label": "Server", "device": "Server", "kind": "Server", "side": "both", "capture_id": "server.pcap", "translation": "none"}],
                 "forward": ["c", "f", "s"], "reverse": [], "client_cidrs": ["198.51.100.0/24"]}
                """;
        Path topologyFile = Files.writeString(dir.resolve("topology.json"), topology, StandardCharsets.UTF_8);
        return new Fixture(dir, files, topologyFile, false);
    }

    // ------------------------------------------------------------------ nat_confirm

    private static Fixture natConfirm(Path dir) throws IOException {
        List<Frame> client = new ArrayList<>();
        List<Frame> fwIn = new ArrayList<>();
        List<Frame> fwOut = new ArrayList<>();
        List<Frame> server = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double t = 1700000000.0 + i * 0.5;
            boolean syn = i == 0;
            int seqF = syn ? 1000 : 1001 + (i - 1) * 100;
            int ackF = syn ? 0 : 7001 + (i - 1) * 100;
            byte[] req = syn ? new byte[0] : concat(String.format("POST /api/%02d ", i), "p", 86);
            int seqR = syn ? 7000 : 7001 + (i - 1) * 100;
            int ackR = syn ? 1001 : 1001 + i * 100;
            byte[] rsp = syn ? new byte[0] : concat(String.format("201 api %02d ", i), "s", 89);
            int fl = syn ? 2 : 24;
            client.add(new Frame(t, tcp(CLIENT, SERVER, 42100, 443, seqF, ackF, fl, req, 100 + i, 64)));
            fwIn.add(new Frame(t + 0.00002, tcp(CLIENT, SERVER, 42100, 443, seqF, ackF, fl, req, 100 + i, 63)));
            fwOut.add(new Frame(t + 0.00005, tcp(NAT_SOURCE, SERVER, 62100, 443, seqF, ackF, fl, req, 100 + i, 62)));
            server.add(new Frame(t + 0.00007, tcp(NAT_SOURCE, SERVER, 62100, 443, seqF, ackF, fl, req, 100 + i, 61)));
            double tr = t + 0.003;
            int flR = syn ? 18 : 24;
            server.add(new Frame(tr, tcp(SERVER, NAT_SOURCE, 443, 62100, seqR, ackR, flR, rsp, 400 + i, 64)));
            fwOut.add(new Frame(tr + 0.00002, tcp(SERVER, NAT_SOURCE, 443, 62100, seqR, ackR, flR, rsp, 400 + i, 63)));
            fwIn.add(new Frame(tr + 0.00005, tcp(SERVER, CLIENT, 443, 42100, seqR, ackR, flR, rsp, 400 + i, 62)));
            client.add(new Frame(tr + 0.00007, tcp(SERVER, CLIENT, 443, 42100, seqR, ackR, flR, rsp, 400 + i, 61)));
        }
        List<Path> files = List.of(pcap(dir.resolve("client.pcap"), client), pcap(dir.resolve("fw-in.pcap"), fwIn),
                pcap(dir.resolve("fw-out.pcap"), fwOut), pcap(dir.resolve("server.pcap"), server));
        String topology = """
                {"points": [
                  {"id": "c", "label": "Client", "device": "Client", "kind": "Client", "side": "both", "capture_id": "client.pcap", "translation": "none"},
                  {"id": "fi", "label": "FW ingress", "device": "FW", "kind": "Firewall", "side": "ingress", "capture_id": "fw-in.pcap", "translation": "nat"},
                  {"id": "fo", "label": "FW egress", "device": "FW", "kind": "Firewall", "side": "egress", "capture_id": "fw-out.pcap", "translation": "nat"},
                  {"id": "s", "label": "Server", "device": "Server", "kind": "Server", "side": "both", "capture_id": "server.pcap", "translation": "none"}],
                 "forward": ["c", "fi", "fo", "s"], "reverse": [], "client_cidrs": ["198.51.100.10/32"]}
                """;
        Path topologyFile = Files.writeString(dir.resolve("topology.json"), topology, StandardCharsets.UTF_8);
        return new Fixture(dir, files, topologyFile, true);
    }

    // ------------------------------------------------------------------ frames (reference synthetic.tcp_packet / write_pcap)

    private static byte[] concat(String prefix, String fill, int n) {
        byte[] head = prefix.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[head.length + n];
        System.arraycopy(head, 0, out, 0, head.length);
        java.util.Arrays.fill(out, head.length, out.length, (byte) fill.charAt(0));
        return out;
    }

    private static byte[] repeat(String fill, int n) {
        return concat("", fill, n);
    }

    /** {@code synthetic.tcp_packet} for IPv4 (window 65535, no options, DSCP 0). */
    static byte[] tcp(String src, String dst, int sport, int dport, long seq, long ack, int flags, byte[] payload,
            int ipid, int ttl) throws IOException {
        byte[] a = InetAddress.getByName(src).getAddress();
        byte[] b = InetAddress.getByName(dst).getAddress();
        ByteBuffer tcp = ByteBuffer.allocate(20 + payload.length).order(ByteOrder.BIG_ENDIAN);
        tcp.putShort((short) sport).putShort((short) dport).putInt((int) seq).putInt((int) ack).put((byte) (5 << 4))
                .put((byte) flags).putShort((short) 65535).putShort((short) 0).putShort((short) 0).put(payload);
        byte[] seg = tcp.array();
        ByteBuffer pseudo = ByteBuffer.allocate(12 + seg.length).order(ByteOrder.BIG_ENDIAN);
        pseudo.put(a).put(b).put((byte) 0).put((byte) 6).putShort((short) seg.length).put(seg);
        int sum = checksum(pseudo.array());
        seg[16] = (byte) (sum >>> 8);
        seg[17] = (byte) sum;
        ByteBuffer ip = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        ip.put((byte) 0x45).put((byte) 0).putShort((short) (20 + seg.length)).putShort((short) (ipid % 65536))
                .putShort((short) 0x4000).put((byte) ttl).put((byte) 6).putShort((short) 0).put(a).put(b);
        byte[] header = ip.array();
        int ipSum = checksum(header);
        header[10] = (byte) (ipSum >>> 8);
        header[11] = (byte) ipSum;
        ByteBuffer frame = ByteBuffer.allocate(14 + header.length + seg.length);
        frame.put(new byte[] {0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88, (byte) 0x99, (byte) 0xAA,
                (byte) 0xBB, 0x08, 0x00});
        frame.put(header).put(seg);
        return frame.array();
    }

    private static int checksum(byte[] data) {
        long sum = 0;
        for (int i = 0; i < data.length; i += 2) {
            int hi = data[i] & 0xFF;
            int lo = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        return (int) (~sum & 0xFFFF);
    }

    /** {@code synthetic.write_pcap}: classic pcap, microseconds, frames sorted by time (stable). */
    private static Path pcap(Path path, List<Frame> frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0xA1B2C3D4).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(1);
        out.writeBytes(h.array());
        List<Frame> sorted = new ArrayList<>(frames);
        sorted.sort(Comparator.comparingDouble(Frame::ts));
        for (Frame f : sorted) {
            long sec = (long) f.ts();
            long usec = PyRound.round((f.ts() - sec) * 1e6);
            if (usec == 1_000_000L) {
                sec += 1;
                usec = 0;
            }
            ByteBuffer r = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            r.putInt((int) sec).putInt((int) usec).putInt(f.bytes().length).putInt(f.bytes().length);
            out.writeBytes(r.array());
            out.writeBytes(f.bytes());
        }
        return Files.write(path, out.toByteArray());
    }
}
