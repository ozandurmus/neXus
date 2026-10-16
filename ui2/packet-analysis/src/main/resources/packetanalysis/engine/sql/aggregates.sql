-- Verbatim from the reference aggregates.py (add_aggregates).
-- name: aggregate_boundaries
CREATE OR REPLACE TEMP TABLE aggregate_boundaries(hop VARCHAR,host VARCHAR)
-- name: aggregate_boundaries_insert
INSERT INTO aggregate_boundaries VALUES (?,?)
-- name: aggregate_cycles
CREATE OR REPLACE TEMP TABLE aggregate_cycles AS SELECT cycle_id,min(flow) AS flow,min(port) AS port,
        min(request_first) FILTER(WHERE clock_aligned) AS time,bool_or(stalled) AS stalled
        FROM cycle_points WHERE reason IS NULL GROUP BY cycle_id
-- name: aggregate_added
CREATE OR REPLACE TEMP TABLE aggregate_added AS SELECT p.cycle_id,p.flow,p.port,e.host,min(p.time) AS time,
        sum(greatest(0,p.added_ms)) FILTER(WHERE component='RX') AS rx_ms,
        sum(greatest(0,p.added_ms)) FILTER(WHERE component='TX') AS tx_ms,
        sum(greatest(0,p.added_ms)) AS added_ms,sum(p.uncertainty_ms) AS uncertainty_ms,
        list(struct_pack(point_a:=p.point_a,point_b:=p.point_b,frame_a:=p.frame_a,frame_b:=p.frame_b) ORDER BY p.frame_a,p.frame_b,p.point_a,p.point_b,p.hop) AS frames
        FROM cycle_pairs p JOIN aggregate_boundaries e USING(hop) GROUP BY p.cycle_id,p.flow,p.port,e.host
        HAVING count(*)=2 AND count(DISTINCT component)=2 AND bool_and(p.reason IS NULL AND p.added_ms IS NOT NULL)
-- name: atom_sql
SELECT point,flow,direction,lo,hi,source_frame AS frame,corrected AS time FROM byte_obs
        WHERE is_atom AND eligible AND kind='data' UNION ALL 
-- name: ordinary
SELECT point,flow,direction,seq AS lo,seq+length AS hi,frame,corrected AS time FROM obs
        WHERE proto='TCP' AND length>0 AND eligible
-- name: ordinary_bytes
 AND NOT EXISTS(SELECT 1 FROM byte_flows b WHERE b.flow=obs.flow AND b.direction=obs.direction)
-- name: aggregate_copies
CREATE OR REPLACE TEMP TABLE aggregate_copies AS 
-- name: aggregate_host_clocks
CREATE OR REPLACE TEMP TABLE aggregate_host_clocks(point VARCHAR,capture_id VARCHAR,epoch DOUBLE,offset_s DOUBLE,drift DOUBLE)
-- name: aggregate_host_clocks_insert
INSERT INTO aggregate_host_clocks VALUES (?,?,?,?,?)
-- name: aggregate_host_packets
CREATE OR REPLACE TEMP TABLE aggregate_host_packets AS SELECT c.point,p.frame,
        c.epoch+(p.ts-c.epoch-c.offset_s)/(1+c.drift) AS time
        FROM packets p JOIN aggregate_host_clocks c USING(capture_id)
-- name: cycle_counts
SELECT count(*),count(*) FILTER(WHERE stalled) FROM aggregate_cycles
-- name: best_added
SELECT * FROM aggregate_added WHERE {predicate} ORDER BY added_ms DESC,cycle_id,host LIMIT 1
-- name: repeats
SELECT direction,count(*) AS groups FROM(
            SELECT DISTINCT flow,direction,lo,hi FROM aggregate_copies GROUP BY point,flow,direction,lo,hi
            HAVING count(DISTINCT frame)>1{event_filter}) GROUP BY direction
-- name: rx_share
SELECT sum(rx_ms),sum(added_ms) FROM aggregate_added WHERE {predicate}
-- name: host_coverage
SELECT min(time),max(time),count(time) FROM aggregate_host_packets WHERE point=?
-- name: silence
WITH ordered AS(SELECT frame,time,lag(time) OVER(ORDER BY time,frame) AS previous,
                lag(frame) OVER(ORDER BY time,frame) AS previous_frame FROM aggregate_host_packets WHERE point=?)
                SELECT (least(time,?)-greatest(previous,?))*1000 AS max_silence_ms,
                greatest(previous,?) AS start,least(time,?) AS "end",frame,previous_frame FROM ordered
                WHERE previous IS NOT NULL AND previous<? AND time>? ORDER BY max_silence_ms DESC,previous,frame LIMIT 1
-- name: retrans_peak
SELECT point,floor(corrected) AS time,count(*) AS count,min(frame) AS frame FROM obs
            WHERE retrans AND eligible AND corrected IS NOT NULL AND point IN(SELECT unnest(?::VARCHAR[]))
            AND (? IS NULL OR corrected>=?) AND (? IS NULL OR corrected<?) GROUP BY point,floor(corrected)
            ORDER BY count DESC,time,point LIMIT 1
-- name: retrans_unknown
SELECT count(*) FROM obs WHERE retrans AND corrected IS NULL AND point IN(SELECT unnest(?::VARCHAR[]))
-- name: stall_flows
SELECT count(DISTINCT flow) FROM host_transit WHERE direction=? AND reason IS NULL AND delay_ms>=? AND {predicate}
