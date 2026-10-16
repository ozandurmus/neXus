-- Verbatim from the frozen reference topology/suggest.py:62; tie-break ",a,b" appended to the final ORDER BY (DETERMINISM.md).
WITH unique_packets AS(SELECT *,tuple_key<reverse_tuple AS direction FROM packets
            WHERE proto='TCP' QUALIFY count(*) OVER(PARTITION BY capture_id,tuple_key,signature)=1),
            paired AS(SELECT a.capture_id AS a,b.capture_id AS b,a.direction,(b.ts-a.ts)*1e6 AS delta,a.ttl-b.ttl AS ttl,
                a.frame AS frame_a,b.frame AS frame_b FROM unique_packets a JOIN unique_packets b USING(tuple_key,signature)
                WHERE a.capture_id<b.capture_id), med AS(SELECT a,b,direction,count(*) AS n,median(delta) AS us,
                quantile_cont(delta,.95)-quantile_cont(delta,.05) AS spread_us,median(ttl) AS ttl,
                arg_min(frame_a,abs(delta)) AS frame_a,arg_min(frame_b,abs(delta)) AS frame_b
                FROM paired GROUP BY a,b,direction)
            SELECT a,b,min(n) AS samples,list(us ORDER BY direction) AS deltas_us,max(spread_us) AS spread_us,
                list(ttl ORDER BY direction) AS ttl_steps,min(frame_a) AS frame_a,min(frame_b) AS frame_b
            FROM med GROUP BY a,b HAVING count(*)=2 AND min(n)>=3 AND max(abs(us))<=500 AND max(spread_us)<=500
            ORDER BY spread_us,a,b LIMIT 200
