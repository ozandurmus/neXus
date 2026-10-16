-- Verbatim from the frozen reference topology/suggest.py:153; {fields} are filled like the f-string.
WITH shared_flows AS (
            SELECT least(tuple_key, reverse_tuple) AS canon
            FROM packets
            WHERE capture_id IN ({placeholders}) AND proto='TCP' AND (unsupported IS NULL OR unsupported='')
            GROUP BY 1 HAVING count(DISTINCT capture_id) >= 2
        )
        SELECT p.capture_id, p.frame, p.src, p.dst, p.sport, p.dport, p.flags
        FROM packets p
        JOIN shared_flows sf ON least(p.tuple_key, p.reverse_tuple) = sf.canon
        WHERE p.capture_id IN ({placeholders}) AND p.proto='TCP'
          AND (p.unsupported IS NULL OR p.unsupported='')
          AND (p.flags & 18) IN (2, 18)
        ORDER BY p.ts, p.frame
