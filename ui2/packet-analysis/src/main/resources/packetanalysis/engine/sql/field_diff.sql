-- Verbatim from the reference field_diff.py (field_diffs).
-- matched_pairs: the reference ordering (a.corrected, a.frame) with b.frame appended (DETERMINISM.md).
-- name: matched_pairs
SELECT a AS before_packet,b AS after_packet
                FROM obs a JOIN obs b ON a.packet_key=b.packet_key
                WHERE a.point=? AND b.point=? AND a.direction=? AND a.eligible AND b.eligible
                AND (? IS NULL OR a.packet_key=?)
                ORDER BY a.corrected,a.frame,b.frame LIMIT ?
