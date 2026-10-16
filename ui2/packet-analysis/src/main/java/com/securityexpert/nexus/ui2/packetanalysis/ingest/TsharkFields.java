package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The tshark field lists of the reference ingest, in their exact order. */
public final class TsharkFields {

    /** Bumped whenever the parsed row changes; part of every capture identity. */
    public static final int PARSER_VERSION = 13;

    /** {@code BASE_FIELDS}: the 77 core fields. */
    public static final List<String> BASE_FIELDS = split("""
            frame.number frame.time_epoch frame.interface_id ip.src ipv6.src ip.dst ipv6.dst
            ip.proto ipv6.nxt ip.id ipv6.flow tcp.srcport udp.srcport tcp.dstport udp.dstport
            tcp.seq_raw tcp.ack_raw tcp.flags tcp.len udp.length ip.len ipv6.plen frame.len frame.cap_len
            tcp.payload udp.payload icmp.ident icmp.seq icmp.type icmpv6.type tcp.stream
            ip.ttl ipv6.hlim ip.dsfield.dscp tcp.options.mss_val tcp.window_size_value
            tcp.analysis.retransmission tcp.analysis.fast_retransmission tcp.analysis.spurious_retransmission
            tcp.analysis.out_of_order tcp.analysis.ack_lost_segment tcp.analysis.zero_window tcp.analysis.ack_rtt
            ip.flags.mf ip.frag_offset ipv6.fraghdr.offset ipv6.fraghdr.more frame.protocols frame.md5_hash dns.id
            dns.flags.response
            ipv6.tclass.dscp tcp.options tcp.options.wscale.shift tcp.options.sack_perm
            tcp.options.timestamp.tsval tcp.options.timestamp.tsecr ip.flags.df icmp.code icmp.mtu
            icmpv6.code icmpv6.mtu tls.record.content_type
            tcp.analysis.window_full tcp.analysis.duplicate_ack tcp.analysis.zero_window_probe
            tcp.analysis.bytes_in_flight tcp.analysis.rto tcp.analysis.rto_frame tcp.options.sack_le
            tcp.options.sack_re http.request.method
            dns.qry.name dns.qry.type dns.qry.class dns.flags.rcode dns.count.queries
            """);

    /** Check Point FW-1 monitor fields, added for snoop captures. */
    public static final List<String> CHECKPOINT_FIELDS = List.of("fw1.direction", "fw1.chain", "fw1.interface",
            "fw1.uuid");

    /** F5 Ethernet trailer fields, added when trailer decoding is enabled. */
    public static final List<String> F5_FIELDS = List.of("flowid", "peerid", "peeraddr", "peeraddr6", "peerport",
            "peerremoteaddr", "peerremoteaddr6", "peerremoteport", "peerlocaladdr", "peerlocaladdr6", "peerlocalport",
            "ingress", "vip", "tmm", "slot", "rstcause", "rstcauseval", "rstcausetxt").stream()
            .map(n -> "f5ethtrailer." + n).toList();

    /** HTTP request fields added together with the F5 fields. */
    public static final List<String> HTTP_FIELDS = List.of("http.request.method", "http.request.uri",
            "http.request.version");

    /** {@code VENDOR_FIELDS}. */
    public static final List<String> VENDOR_FIELDS = concat(CHECKPOINT_FIELDS, F5_FIELDS, HTTP_FIELDS);

    private TsharkFields() {
    }

    private static List<String> split(String s) {
        return List.of(s.trim().split("\\s+"));
    }

    @SafeVarargs
    static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        Arrays.stream(lists).forEach(out::addAll);
        return List.copyOf(out);
    }
}
