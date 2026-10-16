-- Verbatim from the frozen reference topology/suggest.py:121.
SELECT capture_id,count(*) FILTER(WHERE length>1500) AS superframes,
            min(frame) FILTER(WHERE length>1500 AND ttl IN(64,128)) AS frame,max(length) AS max_tcp_bytes
            FROM packets WHERE proto='TCP' GROUP BY capture_id HAVING superframes>0 AND frame IS NOT NULL
