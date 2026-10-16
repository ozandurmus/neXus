-- Verbatim from the reference host_path.py (host_path, aggregate_host, finish_host_summary,
-- classify_chronic_losses). GROUP BY outputs that become report lists get ORDER BY tie-breaks (DETERMINISM.md).
-- name: host_edges
CREATE OR REPLACE TEMP TABLE host_edges(hop VARCHAR,a VARCHAR,b VARCHAR,direction VARCHAR,role VARCHAR,uncertainty_ms DOUBLE,reason VARCHAR)
-- name: host_edges_insert
INSERT INTO host_edges VALUES (?,?,?,?,?,?,?)
-- name: host_transit
CREATE OR REPLACE TABLE host_transit AS SELECT e.hop,e.role AS direction,o.flow,m.key_a,m.key_b,m.time_a AS time,
        (m.time_b-m.time_a)*1000 AS delay_ms,m.bytes,e.uncertainty_ms,e.reason
        FROM observation_matches m JOIN host_edges e ON m.point_a=e.a AND m.point_b=e.b AND m.direction=e.direction
        JOIN obs o ON o.point=m.point_a AND o.packet_key=m.key_a WHERE o.proto='TCP' AND o.length>0
        AND (? IS NULL OR m.time_a>=?) AND (? IS NULL OR m.time_a<=?)
-- name: host_path_series
CREATE OR REPLACE TABLE host_path_series AS SELECT hop,direction,flow,floor(time) AS start,floor(time)+1 AS "end",
        count(*) AS matched_ranges,sum(bytes) AS bytes,quantile_cont(delay_ms,.5) AS p50_ms,quantile_cont(delay_ms,.95) AS p95_ms,max(delay_ms) AS max_ms,
        sum(bytes) FILTER(WHERE delay_ms>greatest(1,2*uncertainty_ms)) AS delayed_bytes,
        sum(greatest(0,delay_ms-uncertainty_ms)*bytes ORDER BY time,key_a,key_b,delay_ms,bytes) AS delay_byte_ms,
        max(uncertainty_ms) AS uncertainty_ms,min(reason) AS reason,arg_max(key_a,CASE WHEN delay_ms IS NOT NULL THEN (delay_ms,key_a,key_b) END) AS evidence_key
        FROM host_transit GROUP BY ALL
-- name: service
SELECT point,flow,count(*) AS samples,quantile_cont((response-complete)*1000,.95) AS p95_ms,
        max((response-complete)*1000) AS max_ms,min(last_frame) AS request_frame,arg_min(response_frame,(last_frame,response_frame)) AS response_frame
        FROM proxy_requests WHERE kind='http' AND ready AND response IS NOT NULL AND response>=complete AND point IN(SELECT unnest(?::VARCHAR[])) GROUP BY point,flow
        ORDER BY point,flow
-- name: host_bursts
CREATE OR REPLACE TEMP TABLE host_bursts AS WITH ordered AS (
        SELECT *,lag(direction) OVER(PARTITION BY point,flow ORDER BY ts,frame) AS previous FROM obs
        WHERE point IN(SELECT unnest(?::VARCHAR[])) AND proto='TCP' AND length>0 AND eligible), numbered AS(
        SELECT *,sum(CASE WHEN direction IS DISTINCT FROM previous THEN 1 ELSE 0 END) OVER(PARTITION BY point,flow ORDER BY ts,frame) AS burst FROM ordered)
        SELECT point,flow,burst,min(direction) AS direction,min(ts) AS first_time,max(ts) AS last_time,
        arg_max((seq+length)%4294967296,(ts,-frame)) AS end_seq,arg_min(ack,(ts,frame)) AS ack,
        arg_min(frame,(ts,frame)) AS first_frame,arg_max(frame,(ts,-frame)) AS last_frame FROM numbered GROUP BY point,flow,burst
-- name: turnaround
SELECT q.point,q.flow,count(*) AS samples,quantile_cont((r.first_time-q.last_time)*1000,.95) AS p95_ms,
        max((r.first_time-q.last_time)*1000) AS max_ms,min(q.last_frame) AS request_frame,arg_min(r.first_frame,(q.last_frame,r.first_frame)) AS response_frame
        FROM host_bursts q JOIN host_bursts r ON q.point=r.point AND q.flow=r.flow AND r.burst=q.burst+1
        WHERE q.direction='forward' AND r.direction='reverse' AND r.ack=q.end_seq AND r.first_time>=q.last_time GROUP BY q.point,q.flow
        ORDER BY q.point,q.flow
-- name: service_aggregate
SELECT count(*),quantile_cont((response-complete)*1000,.95) FROM proxy_requests
        WHERE kind='http' AND ready AND response>=complete AND point IN(SELECT unnest(?::VARCHAR[]))
-- name: streams
SELECT hop,direction,flow,count(*) AS matched_ranges,sum(bytes) AS bytes,quantile_cont(delay_ms,.95) AS p95_ms,
        max(delay_ms) AS max_ms,max(uncertainty_ms) AS uncertainty_ms,min(reason) AS reason,
        sum(bytes) FILTER(WHERE delay_ms>greatest(1,2*uncertainty_ms)) AS delayed_bytes,
        sum(greatest(0,delay_ms-uncertainty_ms)*bytes ORDER BY time,key_a,key_b,delay_ms,bytes) AS delay_byte_ms,arg_max(key_a,CASE WHEN delay_ms IS NOT NULL THEN (delay_ms,key_a,key_b) END) AS evidence_key,arg_max(key_b,CASE WHEN delay_ms IS NOT NULL THEN (delay_ms,key_a,key_b) END) AS evidence_key_b
        FROM host_transit GROUP BY hop,direction,flow
        ORDER BY hop,direction,flow
-- name: series_rows
SELECT * FROM host_path_series ORDER BY start,hop,flow LIMIT 20000
-- name: global_series
SELECT start,max("end") AS "end",max(p95_ms) AS latency_p95_ms FROM host_path_series WHERE reason IS NULL GROUP BY start ORDER BY start
-- name: direction_item
SELECT count(*) AS matched_ranges,sum(bytes) AS bytes,
            quantile_cont(delay_ms,.95) AS p95_ms,max(delay_ms) AS max_ms,
            max(uncertainty_ms) AS uncertainty_ms,sum(bytes) FILTER(WHERE delay_ms>greatest(1,2*uncertainty_ms)) AS delayed_bytes,
            sum(greatest(0,delay_ms-uncertainty_ms)*bytes ORDER BY time,key_a,key_b,delay_ms,bytes) AS delay_byte_ms
            FROM host_transit WHERE direction=? AND reason IS NULL
-- name: direction_series
SELECT floor(time) AS start,floor(time)+1 AS "end",
            quantile_cont(delay_ms,.95) AS p95_ms,max(delay_ms) AS max_ms,
            arg_max(key_a,CASE WHEN delay_ms IS NOT NULL THEN (delay_ms,key_a,key_b,hop) END) AS evidence_key,arg_max(key_b,CASE WHEN delay_ms IS NOT NULL THEN (delay_ms,key_a,key_b,hop) END) AS evidence_key_b,arg_max(hop,CASE WHEN delay_ms IS NOT NULL THEN (delay_ms,key_a,key_b,hop) END) AS evidence_hop FROM host_transit
            WHERE direction=? AND reason IS NULL GROUP BY ALL ORDER BY start
-- name: periods
SELECT CASE WHEN time<? THEN 'before' WHEN time<? THEN 'event' ELSE 'recovery' END AS period,
            count(*) AS matched_ranges,sum(bytes) AS bytes,quantile_cont(delay_ms,.95) AS p95_ms,max(delay_ms) AS max_ms
            FROM host_transit WHERE direction=? AND reason IS NULL GROUP BY period ORDER BY min(time)
-- name: cycle_aggregate
SELECT count(*) AS samples,quantile_cont(turnaround_ms,.95) AS p95_ms
        FROM cycle_points WHERE point IN(SELECT unnest(?::VARCHAR[])) AND reason IS NULL
-- name: chronic_stats
SELECT count(*) AS total_drops,
            count(*) FILTER(WHERE ts < ?) AS drops_before,
            count(*) FILTER(WHERE ts >= ? AND ts < ?) AS drops_during,
            count(*) FILTER(WHERE ts >= ?) AS drops_after,
            median(recovery_ms) AS median_recovery_ms
            FROM events WHERE point_a=? AND point_b=? AND direction=?
            AND kind IN ('impactful_loss','recovered_loss','unrecovered_loss','confirmed_device_drop')
-- name: chronic_totals
SELECT
            count(*) FILTER(WHERE coalesce(corrected,ts) < ?) AS total_before,
            count(*) FILTER(WHERE coalesce(corrected,ts) >= ? AND coalesce(corrected,ts) < ?) AS total_during,
            count(*) FILTER(WHERE coalesce(corrected,ts) >= ?) AS total_after
            FROM obs WHERE point=? AND direction=? AND eligible
            AND (length>0 OR (proto='TCP' AND (flags&7)>0) OR proto IN ('UDP','ICMP'))
            AND (? IS NULL OR coalesce(corrected,ts)>=?) AND (? IS NULL OR coalesce(corrected,ts)<=?)
