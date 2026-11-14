package com.securityexpert.nexus.ui2.service.zonematrix;

import java.math.BigInteger;
import java.util.*;

/**
 * The address partition of one zone-matrix version (ZONE_MATRIX_CONTRACT.md section 3.2, FROZEN 2026-11-13).
 *
 * <p>Every address of a family belongs to exactly one zone or to {@code UNASSIGNED} (a {@code null} zone id). Nesting is
 * allowed and the longest prefix wins; the same block in two zones is refused ({@link DuplicateBlock}). CIDR blocks are
 * either nested or disjoint, so a single sweep over the blocks sorted by start and prefix length gives the partition
 * as non-overlapping segments, in order, covering the whole family.</p>
 */
public final class ZonePartition {
    /** One maximal run of addresses with one owner; {@code zoneId == null} means UNASSIGNED. */
    public record Segment(ZoneBlock.Family family, BigInteger start, BigInteger end, String zoneId) {
        public BigInteger size() { return end.subtract(start).add(BigInteger.ONE); }
    }
    /** One block of one zone, the input of the partition. */
    public record Placed(String zoneId, ZoneBlock block) {}
    /** The same block was given to two different zones. */
    public static final class DuplicateBlock extends IllegalArgumentException {
        public final String block, firstZoneId, secondZoneId;
        DuplicateBlock(String block, String first, String second) {
            super("ZONE_BLOCK_DUPLICATE"); this.block = block; this.firstZoneId = first; this.secondZoneId = second;
        }
    }
    /** A block of a zone that duplicates or nests inside another block of the same zone (accepted, reported). */
    public record RedundantBlock(String zoneId, String block, String within) {}

    private final Map<ZoneBlock.Family, List<Segment>> segments;
    private final List<RedundantBlock> redundant;

    private ZonePartition(Map<ZoneBlock.Family, List<Segment>> segments, List<RedundantBlock> redundant) {
        this.segments = segments; this.redundant = redundant;
    }

    public static ZonePartition of(Collection<Placed> placed) {
        Map<ZoneBlock.Family, List<Segment>> result = new EnumMap<>(ZoneBlock.Family.class);
        List<RedundantBlock> redundant = new ArrayList<>();
        for (ZoneBlock.Family family : ZoneBlock.Family.values()) {
            List<Placed> blocks = new ArrayList<>(placed.stream().filter(p -> p.block().family() == family).toList());
            blocks.sort(Comparator.comparing((Placed p) -> p.block().start()).thenComparingInt(p -> p.block().prefix())
                    .thenComparing(Placed::zoneId));
            result.put(family, sweep(family, blocks, redundant));
        }
        return new ZonePartition(result, List.copyOf(redundant));
    }

    private static List<Segment> sweep(ZoneBlock.Family family, List<Placed> blocks, List<RedundantBlock> redundant) {
        List<Segment> out = new ArrayList<>();
        Deque<Placed> open = new ArrayDeque<>();
        BigInteger cursor = BigInteger.ZERO;
        for (Placed next : blocks) {
            cursor = close(open, next.block().start(), cursor, out);
            Placed top = open.peek();
            if (top != null && top.block().equals(next.block())) {
                if (!top.zoneId().equals(next.zoneId())) throw new DuplicateBlock(next.block().toString(), top.zoneId(), next.zoneId());
                redundant.add(new RedundantBlock(next.zoneId(), next.block().toString(), top.block().toString()));
                continue;
            }
            if (top != null && top.zoneId().equals(next.zoneId()))
                redundant.add(new RedundantBlock(next.zoneId(), next.block().toString(), top.block().toString()));
            if (cursor.compareTo(next.block().start()) < 0) {
                emit(out, family, cursor, next.block().start().subtract(BigInteger.ONE), top == null ? null : top.zoneId());
                cursor = next.block().start();
            }
            open.push(next);
        }
        cursor = close(open, family.max().add(BigInteger.ONE), cursor, out);
        if (cursor.compareTo(family.max()) <= 0) emit(out, family, cursor, family.max(), null);
        return List.copyOf(out);
    }

    /** Closes every open block that ends before {@code limit}, emitting its remaining addresses; returns the new cursor. */
    private static BigInteger close(Deque<Placed> open, BigInteger limit, BigInteger cursor, List<Segment> out) {
        while (!open.isEmpty() && open.peek().block().end().compareTo(limit) < 0) {
            Placed done = open.pop();
            if (cursor.compareTo(done.block().end()) <= 0) {
                emit(out, done.block().family(), cursor, done.block().end(), done.zoneId());
                cursor = done.block().end().add(BigInteger.ONE);
            }
        }
        return cursor;
    }

    private static void emit(List<Segment> out, ZoneBlock.Family family, BigInteger start, BigInteger end, String zoneId) {
        if (start.compareTo(end) > 0) return;
        if (!out.isEmpty()) {
            Segment last = out.get(out.size() - 1);
            if (Objects.equals(last.zoneId(), zoneId) && last.end().add(BigInteger.ONE).equals(start)) {
                out.set(out.size() - 1, new Segment(family, last.start(), end, zoneId));
                return;
            }
        }
        out.add(new Segment(family, start, end, zoneId));
    }

    public List<Segment> segments(ZoneBlock.Family family) { return segments.get(family); }

    public List<RedundantBlock> redundant() { return redundant; }

    /** The zone id of one address, or {@code null} for UNASSIGNED. */
    public String zoneOf(ZoneBlock address) {
        List<Segment> list = segments.get(address.family());
        int low = 0, high = list.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Segment s = list.get(mid);
            if (s.end().compareTo(address.start()) < 0) low = mid + 1;
            else if (s.start().compareTo(address.start()) > 0) high = mid - 1;
            else return s.zoneId();
        }
        throw new IllegalStateException("partition does not cover the address family");
    }

    /** Effective address count per zone and family after nesting; UNASSIGNED under the key {@code null}. */
    public Map<String, Map<ZoneBlock.Family, BigInteger>> effectiveSizes() {
        Map<String, Map<ZoneBlock.Family, BigInteger>> sizes = new HashMap<>();
        for (var entry : segments.entrySet())
            for (Segment s : entry.getValue())
                sizes.computeIfAbsent(s.zoneId(), k -> new EnumMap<>(ZoneBlock.Family.class)).merge(entry.getKey(), s.size(), BigInteger::add);
        return sizes;
    }

    /** One movement of addresses between owners from {@code before} to {@code after} (null = UNASSIGNED). */
    public record Move(String fromZoneId, String toZoneId, ZoneBlock.Family family, BigInteger addresses) {}

    /** Addresses whose owner differs between two partitions, summed per (from, to, family) (section 3.2, ZM-29). */
    public static List<Move> moves(ZonePartition before, ZonePartition after) {
        Map<List<Object>, BigInteger> sums = new LinkedHashMap<>();
        for (ZoneBlock.Family family : ZoneBlock.Family.values()) {
            List<Segment> a = before.segments(family), b = after.segments(family);
            int i = 0, j = 0;
            BigInteger cursor = BigInteger.ZERO;
            while (i < a.size() && j < b.size()) {
                Segment x = a.get(i), y = b.get(j);
                BigInteger end = x.end().min(y.end());
                if (!Objects.equals(x.zoneId(), y.zoneId()))
                    sums.merge(Arrays.asList(x.zoneId(), y.zoneId(), family), end.subtract(cursor).add(BigInteger.ONE), BigInteger::add);
                cursor = end.add(BigInteger.ONE);
                if (x.end().equals(end)) i++;
                if (y.end().equals(end)) j++;
            }
        }
        List<Move> moves = new ArrayList<>();
        sums.forEach((key, count) -> moves.add(new Move((String) key.get(0), (String) key.get(1), (ZoneBlock.Family) key.get(2), count)));
        return List.copyOf(moves);
    }
}
