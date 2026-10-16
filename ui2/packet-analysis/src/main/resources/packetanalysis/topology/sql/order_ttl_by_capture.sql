-- Verbatim from the frozen reference topology/order.py:12; {fields} are filled like the f-string.
WITH shared_flows AS (
            SELECT least(tuple_key, reverse_tuple) AS canon
            FROM packets
            WHERE capture_id IN ({placeholders}) AND proto='TCP' AND (unsupported IS NULL OR unsupported='')
            GROUP BY 1 HAVING count(DISTINCT capture_id) >= 2
        )
        SELECT p.capture_id,
               median(p.ttl) FILTER (WHERE p.src IN ({ip_placeholders})) AS fwd_ttl,
               median(p.ttl) FILTER (WHERE p.dst IN ({ip_placeholders})) AS rev_ttl,
               count(*) FILTER (WHERE p.src IN ({ip_placeholders})) AS fwd_n,
               count(*) FILTER (WHERE p.dst IN ({ip_placeholders})) AS rev_n,
               min(p.frame) FILTER (WHERE p.src IN ({ip_placeholders})) AS fwd_frame,
               min(p.frame) FILTER (WHERE p.dst IN ({ip_placeholders})) AS rev_frame,
               count(*) FILTER (WHERE p.length > 1500 AND p.ttl IN (64, 128) AND p.src IN ({ip_placeholders})) AS fwd_super,
               count(*) FILTER (WHERE p.length > 1500 AND p.ttl IN (64, 128) AND p.dst IN ({ip_placeholders})) AS rev_super
        FROM packets p
        JOIN shared_flows sf ON least(p.tuple_key, p.reverse_tuple) = sf.canon
        WHERE p.capture_id IN ({placeholders}) AND p.proto='TCP'
          AND (p.unsupported IS NULL OR p.unsupported='')
        GROUP BY p.capture_id
