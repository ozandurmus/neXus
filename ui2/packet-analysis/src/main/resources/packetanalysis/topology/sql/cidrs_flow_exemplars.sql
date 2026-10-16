-- Verbatim from the frozen reference topology/suggest.py:216; {fields} are filled like the f-string.
WITH shared_flows AS (
            SELECT least(tuple_key, reverse_tuple) AS canon
            FROM packets
            WHERE capture_id IN ({placeholders}) AND proto='TCP' AND (unsupported IS NULL OR unsupported='')
            GROUP BY 1 HAVING count(DISTINCT capture_id) >= 2
        ),
        flow_exemplars AS (
            SELECT least(p.tuple_key, p.reverse_tuple) AS canon,
                   arg_min(p.capture_id, p.ts) AS capture_id,
                   arg_min(p.frame, p.ts) AS frame,
                   arg_min(p.src, p.ts) AS src,
                   arg_min(p.dst, p.ts) AS dst,
                   arg_min(p.sport, p.ts) AS sport,
                   arg_min(p.dport, p.ts) AS dport
            FROM packets p
            JOIN shared_flows sf ON least(p.tuple_key, p.reverse_tuple) = sf.canon
            WHERE p.capture_id IN ({placeholders}) AND p.proto='TCP'
              AND (p.unsupported IS NULL OR p.unsupported='')
            GROUP BY 1
        )
        SELECT * FROM flow_exemplars ORDER BY canon
