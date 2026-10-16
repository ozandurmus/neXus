-- Verbatim from the reference security.py (build_lifecycle, security_checks, scan_checks, pressure_checks).
-- name: session_syns
CREATE OR REPLACE TEMP TABLE session_syns AS SELECT point,flow,canon,canon_reverse,
        seq,src,dst,sport,dport,min(corrected) AS start,arg_min(frame,(corrected,frame)) AS syn_frame,
        count(*) AS syn_attempts FROM obs WHERE proto='TCP' AND eligible AND (flags&18)=2
        AND corrected IS NOT NULL GROUP BY ALL
-- name: has_syn
SELECT 1 FROM session_syns LIMIT 1
-- name: session_replies
CREATE OR REPLACE TEMP TABLE session_replies AS SELECT s.*,
        min(a.corrected) AS synack,arg_min(a.frame,(a.corrected,a.frame)) AS synack_frame,
        arg_min(a.seq,(a.corrected,a.frame)) AS server_seq FROM session_syns s LEFT JOIN {source} a
        ON a.point=s.point AND a.flow=s.flow AND a.canon=s.canon_reverse AND a.eligible
        AND (a.flags&18)=18 AND a.ack=(s.seq+1)%4294967296
        AND a.corrected>=s.start GROUP BY ALL
-- name: session_acks
CREATE OR REPLACE TEMP TABLE session_acks AS SELECT s.*,
        min(a.corrected) AS established,arg_min(a.frame,(a.corrected,a.frame)) AS ack_frame
        FROM session_replies s LEFT JOIN {source} a ON a.point=s.point AND a.flow=s.flow
        AND a.canon=s.canon AND a.eligible AND (a.flags&23)=16
        AND a.seq=(s.seq+1)%4294967296 AND a.ack=(s.server_seq+1)%4294967296
        AND a.corrected>=s.synack GROUP BY ALL
-- name: session_lifecycle
CREATE OR REPLACE TABLE session_lifecycle AS WITH ends AS(
        SELECT s.*,min(a.corrected) AS closed,arg_min(a.frame,(a.corrected,a.frame)) AS close_frame,
        bool_or((a.flags&4)>0) AS rst FROM session_acks s LEFT JOIN {source} a
        ON a.point=s.point AND a.flow=s.flow AND a.eligible AND (a.flags&5)>0
        AND a.corrected>=s.start GROUP BY ALL)
        SELECT *,CASE WHEN closed IS NOT NULL THEN 'closed' WHEN established IS NOT NULL THEN 'established'
        ELSE 'half_open' END AS status,least(coalesce(established,start+{timeout}),
            coalesce(closed,start+{timeout}),start+{timeout}) AS half_open_end FROM ends
-- name: session_points
CREATE OR REPLACE TEMP TABLE session_points(point VARCHAR,start DOUBLE,finish DOUBLE)
-- name: session_points_insert
INSERT INTO session_points VALUES (?,?,?)
-- name: session_buckets
CREATE OR REPLACE TABLE session_buckets(point VARCHAR,bucket BIGINT,start DOUBLE,"end" DOUBLE,coverage VARCHAR,syns BIGINT,synacks BIGINT,established BIGINT,closed BIGINT,half_open BIGINT)
-- name: session_buckets_insert
INSERT INTO session_buckets WITH grid AS(
            SELECT p.*,i AS bucket,{origin}+i*{width} AS lo,{origin}+(i+1)*{width} AS hi
            FROM session_points p CROSS JOIN range({count}) r(i)), stats AS(
            SELECT g.point,g.bucket,g.lo,g.hi,g.start<=g.lo AND g.finish>=g.hi AND EXISTS(SELECT 1 FROM session_lifecycle x WHERE x.point=g.point) AS covered,
            count(*) FILTER(WHERE s.start>=g.lo AND s.start<g.hi) AS syns,
            count(*) FILTER(WHERE s.synack>=g.lo AND s.synack<g.hi) AS synacks,
            count(*) FILTER(WHERE s.established>=g.lo AND s.established<g.hi) AS established,
            count(*) FILTER(WHERE s.closed>=g.lo AND s.closed<g.hi) AS closed,
            count(*) FILTER(WHERE s.start<g.hi AND s.half_open_end>=g.hi) AS half_open
            FROM grid g LEFT JOIN session_lifecycle s ON s.point=g.point AND s.start<g.hi
            AND (s.start>=g.lo OR s.half_open_end>=g.hi OR s.synack>=g.lo OR s.established>=g.lo OR s.closed>=g.lo)
            GROUP BY ALL) SELECT point,bucket,lo,hi,CASE WHEN coalesce(covered,false) THEN 'covered' ELSE 'unknown' END,
            CASE WHEN covered THEN syns END,CASE WHEN covered THEN synacks END,
            CASE WHEN covered THEN established END,CASE WHEN covered THEN closed END,
            CASE WHEN covered THEN half_open END FROM stats
-- name: lifecycle_window
CREATE OR REPLACE TEMP TABLE lifecycle_window(lo DOUBLE,hi DOUBLE)
-- name: lifecycle_window_insert
INSERT INTO lifecycle_window VALUES (?,?)
-- name: lifecycle_points
SELECT s.point,
        count(*) FILTER(WHERE (lo IS NULL OR s.start>=lo) AND (hi IS NULL OR s.start<hi)) AS syn_sent,
        count(*) FILTER(WHERE (lo IS NULL OR synack>=lo) AND (hi IS NULL OR synack<hi) AND synack IS NOT NULL) AS synack_seen,
        count(*) FILTER(WHERE (lo IS NULL OR established>=lo) AND (hi IS NULL OR established<hi) AND established IS NOT NULL) AS established,
        count(*) FILTER(WHERE (lo IS NULL OR closed>=lo) AND (hi IS NULL OR closed<hi) AND closed IS NOT NULL) AS closed,
        count(*) FILTER(WHERE rst AND (lo IS NULL OR closed>=lo) AND (hi IS NULL OR closed<hi)) AS rst_closed,
        count(*) FILTER(WHERE NOT rst AND closed IS NOT NULL AND (lo IS NULL OR closed>=lo) AND (hi IS NULL OR closed<hi)) AS fin_closed,
        arg_min(syn_frame,(start,syn_frame)) AS first_frame FROM session_lifecycle s CROSS JOIN lifecycle_window GROUP BY s.point ORDER BY s.point
-- name: handshake_stops
CREATE OR REPLACE TABLE handshake_stops AS
        SELECT flow,point_a,point_b,direction,min(ts) AS time,count(*) AS attempts,
        arg_min(packet_key,(ts,packet_key)) AS packet_key FROM events WHERE kind='handshake_blocked' OR
        (kind='confirmed_device_drop' AND EXISTS(SELECT 1 FROM obs o
            WHERE o.packet_key=events.packet_key AND (o.flags&2)>0)) GROUP BY ALL
-- name: failures
SELECT * EXCLUDE(sample_order) FROM(
        SELECT *,row_number() OVER(PARTITION BY point_a,point_b,direction ORDER BY time,flow) AS sample_order
        FROM handshake_stops) ORDER BY sample_order,time,point_a,point_b,direction,flow LIMIT 200
-- name: failure_count
SELECT count(*) FROM handshake_stops
-- name: failure_frames
SELECT point,frame,packet_key FROM obs WHERE packet_key IN (SELECT unnest(?::VARCHAR[]))
-- name: session_bucket_rows
SELECT * FROM session_buckets ORDER BY point,bucket LIMIT 200000
-- name: inventories
SELECT id,inventory FROM captures
-- name: max_sessions
SELECT coalesce(max(n),0) FROM(SELECT count(*) n FROM session_lifecycle GROUP BY point)
-- name: drop_table
DROP TABLE IF EXISTS {table}
-- name: security_sessions
CREATE OR REPLACE TEMP TABLE security_sessions AS SELECT s.*,
        bool_or(established IS NOT NULL) OVER(PARTITION BY flow,seq) AS completed_elsewhere,
        bool_or(synack IS NOT NULL) OVER(PARTITION BY flow,seq) AS reply_elsewhere
        FROM session_lifecycle s
-- name: security_cohorts
CREATE OR REPLACE TABLE security_cohorts(point VARCHAR,bucket BIGINT,start DOUBLE,"end" DOUBLE,sessions BIGINT,sources BIGINT,synack_ratio DOUBLE,established_ratio DOUBLE,syn_rate DOUBLE,usable BOOLEAN)
-- name: security_points
CREATE OR REPLACE TEMP TABLE security_points(point VARCHAR,start DOUBLE,finish DOUBLE)
-- name: security_points_insert
INSERT INTO security_points VALUES (?,?,?)
-- name: security_cohorts_insert
INSERT INTO security_cohorts SELECT point,floor((s.start-{origin})/{width})::BIGINT AS bucket,
        {origin}+bucket*{width},{origin}+(bucket+1)*{width},count(*),count(DISTINCT src),
        count(synack)*1.0/count(*),count(established)*1.0/count(*),count(*)/{width},
        point IN (SELECT unnest(?::VARCHAR[])) AND min(s.start)>=(SELECT p.start FROM security_points p WHERE p.point=s.point)
        AND max(s.start)+{timeout}<=(SELECT finish FROM security_points p WHERE p.point=s.point)
        AND NOT bool_or((established IS NULL AND completed_elsewhere) OR (synack IS NULL AND reply_elsewhere))
        FROM security_sessions s WHERE s.start>={origin} AND s.start<{series_end} GROUP BY point,bucket
-- name: waves
WITH signal AS(SELECT *,bucket-row_number() OVER(PARTITION BY point ORDER BY bucket) AS run
        FROM security_cohorts WHERE usable AND syn_rate>=? AND sources>=?
        AND least(synack_ratio,established_ratio)<=?), runs AS(
        SELECT point,run,min(start) AS start,max("end") AS finish,count(*) AS buckets,
        sum(sessions) AS sessions,min(sources) AS sources,min(syn_rate) AS syn_rate,
        max(synack_ratio) AS synack_ratio,max(established_ratio) AS established_ratio
        FROM signal GROUP BY point,run HAVING count(*)>={n}) SELECT * FROM runs ORDER BY start,point
-- name: baseline_points
SELECT point FROM pressure_baseline
-- name: security_fanout
CREATE OR REPLACE TABLE security_fanout AS SELECT a.point,a.src AS source,
        a.start-{width} AS start,a.start AS finish,count(*) AS sessions,
        count(DISTINCT struct_pack(dst:=s.dst,port:=s.dport)) AS targets,
        count(*) FILTER(WHERE s.established IS NULL)*1.0/count(*) AS failed_ratio,
        bool_and(NOT s.completed_elsewhere OR s.established IS NOT NULL) AS consistent
        FROM security_sessions a JOIN security_sessions s ON a.point=s.point AND a.src=s.src
        AND s.start>=a.start-{width} AND s.start<=a.start
        WHERE a.point IN (SELECT unnest(?::VARCHAR[])) AND a.start>={series_start}+{width}
        AND a.start+{timeout}<=(SELECT finish FROM security_points p WHERE p.point=a.point)
        AND a.start<{series_end} GROUP BY a.point,a.src,a.start
-- name: scan_candidates
SELECT point,source,min(start) AS start,max(finish) AS finish,
        max(sessions) AS sessions,max(targets) AS targets,max(failed_ratio) AS failed_ratio
        FROM security_fanout WHERE consistent AND targets>=? AND failed_ratio>=?
        GROUP BY point,source ORDER BY start,point,source LIMIT 200
-- name: pressure_baseline
CREATE OR REPLACE TEMP TABLE pressure_baseline AS WITH early AS(
        SELECT point,min(bucket) AS first_bucket FROM security_cohorts GROUP BY point)
        SELECT b.point,median(b.syns/{width}) AS baseline_rate,median(b.half_open) AS baseline_half_open,
        count(*) AS baseline_buckets FROM session_buckets b JOIN early e USING(point)
        JOIN security_cohorts c USING(point,bucket) WHERE b.coverage='covered'
        AND c.usable AND c.established_ratio>=0.8 AND b.bucket<e.first_bucket+5
        GROUP BY b.point HAVING count(*)>=3
-- name: pressure_spikes
CREATE OR REPLACE TABLE pressure_spikes AS SELECT b.*,p.baseline_rate,p.baseline_half_open,
        greatest({floor},4*p.baseline_rate) AS rate_threshold,
        greatest({half_open_floor},4*p.baseline_half_open) AS half_open_threshold
        FROM session_buckets b JOIN pressure_baseline p USING(point)
        WHERE b.coverage='covered' AND b.point IN (SELECT unnest(?::VARCHAR[]))
        AND (b.syns/{width}>=greatest({floor},4*p.baseline_rate)
        OR b.half_open>=greatest({half_open_floor},4*p.baseline_half_open))
-- name: any_spike
SELECT 1 FROM pressure_spikes LIMIT 1
-- name: segment_drops
SELECT min(ts) AS onset,count(*) AS drops,arg_min(packet_key,(ts,packet_key)) AS packet_key
            FROM events WHERE point_a=? AND point_b=? AND direction=?
            AND kind IN ('confirmed_device_drop','handshake_blocked','impactful_loss','recovered_loss','unrecovered_loss')
-- name: spike
SELECT * FROM pressure_spikes WHERE point=? AND start<=? AND "end">=? ORDER BY start LIMIT 1
-- name: positive_proofs
SELECT p.* FROM device_proofs p JOIN events e ON e.id=p.event_id
            WHERE e.point_a=? AND e.point_b=? AND e.direction=? AND p.device=?
            AND p.status='confirmed_device_drop' AND (p.time_source='corrected' OR p.time_source LIKE 'matched upstream%'
                OR p.time_source='corrected inspection clock')
            AND abs(e.ts-?)<=? ORDER BY e.ts,p.proof_id LIMIT 16
