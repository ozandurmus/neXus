-- Verbatim from the frozen reference topology/order.py:49; {fields} are filled like the f-string.
-- Tie-break: arg_min keys extended to (abs delta, a.frame, b.frame), one row for both frames (DETERMINISM.md).
WITH unique_packets AS (
            SELECT capture_id, tuple_key, signature, ts, ttl, frame,
                   CASE WHEN src IN ({ip_placeholders}) THEN 'forward'
                        WHEN dst IN ({ip_placeholders}) THEN 'reverse'
                        ELSE 'unknown' END AS dir
            FROM packets
            WHERE capture_id IN ({placeholders}) AND proto='TCP' AND (unsupported IS NULL OR unsupported='')
            QUALIFY count(*) OVER (PARTITION BY capture_id, tuple_key, signature) = 1
        )
        SELECT a.capture_id AS a, b.capture_id AS b, a.dir,
               count(*) AS n,
               median((b.ts - a.ts) * 1e6) AS delta_us,
               quantile_cont((b.ts - a.ts) * 1e6, 0.95) - quantile_cont((b.ts - a.ts) * 1e6, 0.05) AS spread_us,
               median(a.ttl - b.ttl) AS ttl_step,
               arg_min(a.frame, (abs((b.ts - a.ts) * 1e6), a.frame, b.frame)) AS frame_a,
               arg_min(b.frame, (abs((b.ts - a.ts) * 1e6), a.frame, b.frame)) AS frame_b
        FROM unique_packets a
        JOIN unique_packets b USING (tuple_key, signature, dir)
        WHERE a.capture_id <> b.capture_id AND a.dir IN ('forward', 'reverse')
        GROUP BY a.capture_id, b.capture_id, a.dir
