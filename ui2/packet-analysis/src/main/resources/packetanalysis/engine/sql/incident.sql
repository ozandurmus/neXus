-- Verbatim from the reference incident.py (build_incident). GROUP BY outputs used as report lists keep the
-- reference ORDER BY with appended keys (DETERMINISM.md).
-- name: host_path_series_exists
SELECT count(*) FROM duckdb_tables() WHERE table_name='host_path_series'
-- name: timeline
WITH times AS(SELECT DISTINCT start FROM host_path_series UNION SELECT DISTINCT start FROM delivery_series),
        delays AS(SELECT start,max(max_ms) FILTER(WHERE direction='rx') AS rx_max_ms,
         max(max_ms) FILTER(WHERE direction='tx') AS tx_max_ms FROM host_path_series WHERE reason IS NULL GROUP BY start),
        drops AS(SELECT start,sum(dropped) AS tx_dropped,sum(delayed) AS tx_delayed FROM delivery_series
         WHERE point_a IN(SELECT unnest(?::VARCHAR[])) AND direction='reverse' GROUP BY start),
        retrans AS(SELECT floor(coalesce(corrected,ts)) AS start,count(*) AS retransmissions,arg_min(frame,(coalesce(corrected,ts),frame,point)) AS frame,min(point) AS point
         FROM obs WHERE retrans AND point IN(SELECT unnest(?::VARCHAR[])) GROUP BY start)
        SELECT t.start,d.rx_max_ms,d.tx_max_ms,p.tx_dropped AS tx_dropped,
         p.tx_delayed AS tx_delayed,coalesce(r.retransmissions,0) AS retransmissions,r.point,r.frame
         FROM times t LEFT JOIN delays d USING(start) LEFT JOIN drops p USING(start) LEFT JOIN retrans r USING(start)
         ORDER BY start LIMIT 10000
-- name: tx_keys
SELECT start,point_a,point_b,min(evidence_frame_a) AS fa,min(evidence_frame_b) AS fb FROM delivery_series
        WHERE point_a IN(SELECT unnest(?::VARCHAR[])) AND direction='reverse' GROUP BY start,point_a,point_b
        ORDER BY start,point_a,point_b
-- name: cycle_details
CREATE OR REPLACE VIEW cycle_details AS SELECT p.*,
        CASE WHEN NOT p.clock_aligned OR w.reason IS NOT NULL OR w.start IS NULL THEN 'unknown'
             WHEN p.request_first<w.start THEN 'before' WHEN p.request_first<w.stop THEN 'event' ELSE 'recovery' END AS period,
        w.start AS event_start,w.stop AS event_end FROM cycle_top_points p LEFT JOIN cycle_windows w USING(flow)
-- name: detail
SELECT * FROM cycle_details
-- name: top_pairs
SELECT p.* FROM cycle_pairs p JOIN cycle_top t USING(cycle_id) ORDER BY t.max_ms DESC,p.hop,t.cycle_id,p.point_a,p.point_b,p.frame_a,p.frame_b
-- name: edges
SELECT * FROM cycle_edges
-- name: cycle_flow_detail
CREATE OR REPLACE VIEW cycle_flow_detail AS SELECT f.*,
        p.before_p95_ms,p.event_p95_ms,p.recovery_p95_ms,p.unknown_p95_ms,h.per_hop_added
        FROM cycle_flow_ports f LEFT JOIN(
          SELECT point,flow,max(p95_ms) FILTER(WHERE period='before') AS before_p95_ms,
          max(p95_ms) FILTER(WHERE period='event') AS event_p95_ms,max(p95_ms) FILTER(WHERE period='recovery') AS recovery_p95_ms,
          max(p95_ms) FILTER(WHERE period='unknown') AS unknown_p95_ms FROM cycle_periods GROUP BY point,flow) p USING(point,flow)
        LEFT JOIN(SELECT flow,to_json(list(struct_pack(hop:=hop,component:=component,p95_ms:=p95_ms) ORDER BY hop,component))::VARCHAR AS per_hop_added
          FROM(SELECT flow,hop,component,quantile_cont(added_ms,.95) AS p95_ms FROM cycle_pairs GROUP BY flow,hop,component) GROUP BY flow) h USING(flow)
-- name: flow_detail_rows
SELECT * FROM cycle_flow_detail ORDER BY max_ms DESC NULLS LAST,point,flow,port LIMIT 2000
-- name: quality
SELECT point_a,point_b,direction,start,sum(observations) AS observations,sum(dropped) AS loss,
        sum(capture_misses) AS capture_misses,sum(unknown) AS unknown FROM adjacent_match_rates GROUP BY ALL ORDER BY start,point_a,point_b,direction LIMIT 10000
-- name: offload
SELECT e.point_a,e.point_b,e.direction,floor(e.time_a) AS start,count(DISTINCT e.key_a) AS offload_mismatch
       FROM delivery_events e JOIN obs a ON a.point=e.point_a AND a.packet_key=e.key_a
       JOIN obs b ON b.point=e.point_b AND b.packet_key=e.key_b
       WHERE e.classification='unknown' AND a.length<>b.length AND greatest(a.length,b.length)>1500 GROUP BY ALL
-- name: quality_refs
SELECT point_a,point_b,direction,start,min(evidence_frame_a) AS fa,min(evidence_frame_b) AS fb
        FROM adjacent_match_rates GROUP BY ALL
-- name: delivery_rows
SELECT point_a,point_b,direction,start,sum(delayed) AS delayed,sum(dropped) AS dropped,
        sum(capture_misses) AS capture_misses,sum(unknown) AS unknown,min(evidence_frame_a) AS fa,min(evidence_frame_b) AS fb
        FROM adjacent_match_rates GROUP BY ALL ORDER BY start,point_a,point_b,direction LIMIT 10000
