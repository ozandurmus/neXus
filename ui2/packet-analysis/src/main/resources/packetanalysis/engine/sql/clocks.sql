-- Verbatim from the reference analysis/clocks.py.
-- name: clock_range_candidates
WITH ranked AS (
        SELECT point,frame,ts,flow,direction,seq,ack,flags,
        row_number() OVER(PARTITION BY point,flow,direction,seq,ack,flags ORDER BY ts,frame) AS occurrence,
        count(*) OVER(PARTITION BY point,flow,direction,seq,ack,flags) AS n
        FROM obs WHERE point IN (?,?) AND eligible AND proto='TCP'), paired AS(
        SELECT a.ts AS time_a,b.ts AS time_b,a.direction='forward' AS forward,a.frame AS frame_a,b.frame AS frame_b
        FROM ranked a JOIN ranked b USING(flow,direction,seq,ack,flags,occurrence,n)
        WHERE a.point=? AND b.point=? AND a.direction<>'unknown')
        SELECT * FROM paired ORDER BY hash(frame_a,frame_b),frame_a,frame_b LIMIT 20000
-- name: calibration_pairs
SELECT a.ts,b.ts,a.direction='forward',a.frame,b.frame
        FROM calibration a JOIN calibration b USING(packet_key) WHERE a.point=? AND b.point=?
        AND a.eligible AND b.eligible AND a.direction<>'unknown'
        ORDER BY hash(a.frame,b.frame),a.frame,b.frame LIMIT 20000
-- name: capture_inventories
SELECT id,inventory FROM captures
-- name: jitter_noise
WITH pairs AS(SELECT b.ts-a.ts AS delta,a.ts,
            lag(b.ts-a.ts) OVER(PARTITION BY a.point,b.point,a.direction ORDER BY a.ts,a.frame,b.frame) AS previous
            FROM calibration a JOIN calibration b USING(packet_key) WHERE a.capture_id=? AND b.capture_id=? AND a.eligible AND b.eligible)
            SELECT least(.002,coalesce(quantile_cont(abs(delta-previous),.5),0)*4) FROM pairs
-- name: capture_epoch
SELECT min(ts) FROM obs WHERE capture_id=?
-- name: clock_values
CREATE OR REPLACE TEMP TABLE clock_values(capture_id VARCHAR,epoch DOUBLE,offset_s DOUBLE,drift DOUBLE)
-- name: clock_values_insert
INSERT INTO clock_values VALUES (?,?,?,?)
-- name: corrected_obs
CREATE OR REPLACE TABLE obs AS SELECT o.* REPLACE(
        c.epoch+(o.ts-c.epoch-c.offset_s)/(1+c.drift) AS corrected)
        FROM obs o LEFT JOIN clock_values c USING(capture_id)
