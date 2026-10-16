-- Verbatim from the reference microbursts.py (build_microbursts).
-- name: burst_clocks
CREATE OR REPLACE TEMP TABLE burst_clocks(point VARCHAR,offset_s DOUBLE,drift DOUBLE,epoch DOUBLE)
-- name: burst_clocks_insert
INSERT INTO burst_clocks VALUES (?,?,?,?)
-- name: burst_wire
CREATE OR REPLACE TEMP TABLE burst_wire AS WITH w AS (
        SELECT w.*,lag(ts) OVER(PARTITION BY point ORDER BY ts,frame) AS prev_ts,
        lag(frame_hash) OVER(PARTITION BY point ORDER BY ts,frame) AS prev_hash,
        c.epoch+(w.ts-c.epoch-c.offset_s)/(1+c.drift) AS corrected
        FROM wire_observations w JOIN burst_clocks c USING(point))
        SELECT * FROM w WHERE NOT coalesce(caplen=wirelen AND frame_hash<>'' AND frame_hash=prev_hash AND ts-prev_ts<=?,false)
        AND (corrected IS NULL OR ((? IS NULL OR corrected>=?) AND (? IS NULL OR corrected<=?)))
-- name: microburst_windows
CREATE OR REPLACE TABLE microburst_windows AS SELECT *,
        sum(wirelen*8) OVER w/{width} AS bps,count(*) OVER w AS packets,
        min(ts) OVER w AS lo,arg_min(frame,(ts,frame)) OVER w AS first_frame,
        min((ts-prev_ts)*1000000) OVER w AS min_gap_us,
        count(*) FILTER(WHERE transport_length>9000) OVER w AS offload,
        min(ts) OVER(PARTITION BY point) AS capture_start
        FROM burst_wire WINDOW w AS(PARTITION BY point ORDER BY ts RANGE BETWEEN {width} PRECEDING AND CURRENT ROW)
-- name: burst_baseline
CREATE OR REPLACE TEMP TABLE burst_baseline AS WITH bins AS(
        SELECT point,floor(ts/{width}) AS bucket,sum(wirelen*8)/{width} AS bps FROM burst_wire GROUP BY ALL)
        SELECT point,median(bps) AS baseline_bps,greatest(1000000,4*median(bps)) AS threshold_bps FROM bins GROUP BY point
-- name: burst_candidates
CREATE OR REPLACE TEMP TABLE burst_candidates AS WITH b AS(
        SELECT w.*,b.baseline_bps,b.threshold_bps FROM microburst_windows w JOIN burst_baseline b USING(point)
        WHERE packets>=8 AND bps>=b.threshold_bps AND ts-capture_start>={width} AND offload=0),
        gaps AS(SELECT *,lag(ts) OVER(PARTITION BY point ORDER BY ts,frame) AS previous FROM b)
        SELECT *,sum(CASE WHEN previous IS NULL OR lo>previous THEN 1 ELSE 0 END)
            OVER(PARTITION BY point ORDER BY ts,frame) AS episode FROM gaps
-- name: microbursts
CREATE OR REPLACE TABLE microbursts AS SELECT point,episode,min(lo) AS start,max(ts) AS "end",
        max(bps)/1000000 AS peak_mbps,min(baseline_bps)/1000000 AS baseline_mbps,min(threshold_bps)/1000000 AS threshold_mbps,
        (max(ts)-min(lo))*1000 AS duration_ms,min(min_gap_us) AS min_interarrival_us,
        arg_min(first_frame,(lo,first_frame)) AS first_frame,arg_max(frame,(bps,-frame)) AS peak_frame,arg_max(frame,(ts,-frame)) AS last_frame
        FROM burst_candidates GROUP BY point,episode
-- name: points
SELECT w.point,min(w.ts) AS start,max(w.ts) AS "end",max(w.ts)-min(w.ts) AS observed_seconds,
        max(w.bps) FILTER(WHERE w.ts-w.capture_start>={width} AND w.offload=0)/1000000 AS peak_observed_mbps,min(b.baseline_bps)/1000000 AS baseline_mbps,
        min(b.threshold_bps)/1000000 AS threshold_mbps,max(x.n) AS burst_count,max(x.duration_ms) AS max_duration_ms,
        max(x.n)/nullif(max(w.ts)-min(w.ts),0) AS frequency_hz,
        arg_min(w.frame,(w.ts,w.frame)) AS first_frame,arg_max(w.frame,(w.ts,-w.frame)) AS last_frame,arg_max(w.frame,(w.bps,-w.frame)) FILTER(WHERE w.ts-w.capture_start>={width} AND w.offload=0) AS peak_frame
        FROM microburst_windows w JOIN burst_baseline b USING(point)
        LEFT JOIN(SELECT point,count(*) AS n,max(duration_ms) AS duration_ms FROM microbursts GROUP BY point) x USING(point)
        GROUP BY w.point ORDER BY w.point
-- name: total
SELECT count(*) FROM microbursts
-- name: items
SELECT * FROM microbursts ORDER BY peak_mbps DESC,point,start,episode LIMIT 200
-- name: proof
SELECT w.*,c.name AS file FROM wire_observations w
        JOIN (SELECT unnest(from_json(?, '[{"point":"VARCHAR","frame":"BIGINT"}]')) k) refs
        ON w.point=refs.k.point AND w.frame=refs.k.frame JOIN captures c ON c.id=w.capture_id
