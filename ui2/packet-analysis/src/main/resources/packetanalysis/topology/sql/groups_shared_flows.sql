-- Verbatim from the frozen reference topology/groups.py:99; {fields} are filled like the f-string.
WITH shared_flows AS (
            SELECT least(tuple_key, reverse_tuple) AS canon
            FROM packets
            WHERE capture_id IN ({placeholders}) AND proto='TCP' AND (unsupported IS NULL OR unsupported='')
            GROUP BY 1 HAVING count(DISTINCT capture_id) >= 2
        )
        SELECT DISTINCT p.capture_id, sf.canon
        FROM packets p
        JOIN shared_flows sf ON least(p.tuple_key, p.reverse_tuple) = sf.canon
        WHERE p.capture_id IN ({placeholders}) AND p.proto='TCP'
          AND (p.unsupported IS NULL OR p.unsupported='')
