-- Verbatim from the frozen reference topology/groups.py:55.
SELECT count(*) FROM (
                   SELECT 1 FROM packets
                   WHERE capture_id=? AND proto='TCP' AND (unsupported IS NULL OR unsupported='')
                   GROUP BY tuple_key, signature HAVING count(*)=1
               )
