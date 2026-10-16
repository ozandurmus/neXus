-- Verbatim from the reference byte_ranges.py ({MOD} is the module constant 4294967296).
-- name: byte_calibration
CREATE OR REPLACE TEMP TABLE byte_calibration AS
        WITH source AS (SELECT *, (seq+CASE WHEN (flags&2)>0 THEN 1 ELSE 0 END)%4294967296 AS data_seq FROM obs),
        candidates AS (SELECT *,min(length(coalesce(prefix,''))) OVER(PARTITION BY flow,direction,data_seq) AS n
            FROM source WHERE eligible AND proto='TCP' AND length>0 AND (flags&4)=0
            AND flow IN (SELECT flow FROM obs WHERE proto='TCP' AND length>1500)),
        keyed AS (SELECT *,md5('range-start:'||flow||direction||data_seq||left(coalesce(prefix,''),n)) AS key FROM candidates)
        SELECT point,capture_id,frame,ts,key AS packet_key,direction,eligible FROM keyed
        QUALIFY count(*) OVER(PARTITION BY point,key)=1
-- name: calibration_insert
INSERT INTO calibration SELECT * FROM byte_calibration
-- name: candidate
SELECT count(*) FROM obs WHERE proto='TCP' AND length>1500 AND eligible
-- name: byte_flows
CREATE OR REPLACE TABLE byte_flows(flow VARCHAR,direction VARCHAR)
-- name: range_intervals
CREATE OR REPLACE TEMP TABLE range_intervals AS
        WITH source AS (SELECT * FROM obs WHERE proto='TCP' AND eligible AND (flags&4)=0
            AND flow IN (SELECT flow FROM obs WHERE proto='TCP' AND length>1500 AND eligible)),
        base AS (
            SELECT point,frame,flow,direction,(seq+CASE WHEN (flags&2)>0 THEN 1 ELSE 0 END)%{MOD} AS lo,
                (seq+CASE WHEN (flags&2)>0 THEN 1 ELSE 0 END)%{MOD}+length AS hi,'data' AS kind FROM source WHERE length>0
            UNION ALL SELECT point,frame,flow,direction,seq,seq+1,'syn' FROM source WHERE (flags&2)>0
            UNION ALL SELECT point,frame,flow,direction,(seq+length+CASE WHEN (flags&2)>0 THEN 1 ELSE 0 END)%{MOD},
                (seq+length+CASE WHEN (flags&2)>0 THEN 1 ELSE 0 END)%{MOD}+1,'fin' FROM source WHERE (flags&1)>0)
        SELECT point,frame,flow,direction,lo,least(hi,{MOD}) AS hi,kind FROM base
        UNION ALL SELECT point,frame,flow,direction,0,hi-{MOD},kind FROM base WHERE hi>{MOD}
-- name: byte_flows_insert
INSERT INTO byte_flows SELECT DISTINCT a.flow,a.direction FROM range_intervals a
        JOIN range_intervals b ON a.flow=b.flow AND a.direction=b.direction AND a.point<>b.point
        AND a.lo<=b.lo AND a.hi>=b.hi AND a.hi-a.lo>b.hi-b.lo AND a.kind='data' AND b.kind='data'
-- name: byte_flow_count
SELECT count(*) FROM byte_flows
-- name: range_atoms
CREATE OR REPLACE TEMP TABLE range_atoms AS WITH bounds AS (
        SELECT r.flow,r.direction,lo AS edge FROM range_intervals r JOIN byte_flows USING(flow,direction)
        UNION SELECT r.flow,r.direction,hi FROM range_intervals r JOIN byte_flows USING(flow,direction))
        SELECT flow,direction,edge AS lo,lead(edge) OVER(PARTITION BY flow,direction ORDER BY edge) AS hi FROM bounds
-- name: byte_work_units
CREATE OR REPLACE TABLE byte_work_units(flow VARCHAR, atoms BIGINT)
-- name: drop_all_byte_obs
DROP TABLE IF EXISTS all_byte_obs
-- name: byte_flow_list
SELECT DISTINCT flow FROM byte_flows ORDER BY flow
-- name: work_unit_insert
INSERT INTO byte_work_units SELECT ?,count(*) FROM byte_obs
-- name: work_unit_count
SELECT count(*) FROM byte_work_units
-- name: all_byte_obs_create
CREATE TABLE all_byte_obs AS SELECT * FROM byte_obs
-- name: all_byte_obs_insert
INSERT INTO all_byte_obs SELECT * FROM byte_obs
-- name: drop_byte_obs
DROP TABLE byte_obs
-- name: rename_byte_obs
ALTER TABLE all_byte_obs RENAME TO byte_obs
-- name: payload_conflicts
SELECT DISTINCT a.flow FROM byte_obs a JOIN byte_obs b USING(base_key)
        WHERE a.is_atom AND b.is_atom AND a.eligible AND b.eligible AND a.point<>b.point
        AND length(a.prefix)>0 AND length(b.prefix)>0
        AND left(a.prefix,least(length(a.prefix),length(b.prefix)))<>left(b.prefix,least(length(a.prefix),length(b.prefix)))
-- name: conflict_obs
UPDATE obs SET eligible=false,excluded_reason='byte_range_payload_conflict',
            range_reason='Conflicting captured TCP bytes in the same sequence range' WHERE flow IN (SELECT unnest(?::VARCHAR[]))
-- name: conflict_byte_obs
UPDATE byte_obs SET eligible=false WHERE flow IN (SELECT unnest(?::VARCHAR[]))
-- name: byte_pairs
CREATE OR REPLACE TEMP TABLE byte_pairs(a VARCHAR,b VARCHAR,direction VARCHAR)
-- name: byte_pairs_insert
INSERT INTO byte_pairs VALUES (?,?,?)
-- name: byte_links
CREATE OR REPLACE TABLE byte_links AS SELECT p.a AS point_a,p.b AS point_b,p.direction,
        a.source_frame AS frame_a,b.source_frame AS frame_b,a.source_packet_key AS key_a,b.source_packet_key AS key_b,
        a.lo,a.hi,a.range_length AS bytes,a.corrected AS time_a,b.corrected AS time_b
        FROM byte_pairs p JOIN byte_obs a ON a.point=p.a AND a.direction=p.direction AND a.is_atom AND a.eligible
        JOIN byte_obs b ON b.point=p.b AND b.packet_key=a.packet_key AND b.is_atom AND b.eligible
-- name: byte_missing_atoms
CREATE OR REPLACE TABLE byte_missing_atoms AS SELECT p.a AS point_a,p.b AS point_b,p.direction,a.*
        EXCLUDE(direction) FROM byte_pairs p JOIN byte_obs a ON a.point=p.a AND a.direction=p.direction AND a.is_atom AND a.eligible
        WHERE NOT EXISTS(SELECT 1 FROM byte_obs b WHERE b.point=p.b AND b.packet_key=a.packet_key AND b.is_atom AND b.eligible)
-- name: byte_missing
CREATE OR REPLACE TABLE byte_missing AS SELECT point_a,point_b,direction,source_frame,source_packet_key,
        sum(range_length)::BIGINT AS missing_bytes,to_json(list(struct_pack(start_seq:=lo,end_seq:=hi,bytes:=range_length,kind:=kind) ORDER BY lo,hi,kind)) AS byte_ranges
        FROM byte_missing_atoms GROUP BY point_a,point_b,direction,source_frame,source_packet_key
-- name: byte_recovered_atoms
CREATE OR REPLACE TABLE byte_recovered_atoms AS SELECT m.point_a,m.point_b,m.source_packet_key,m.frame,
        min(r.corrected) AS recovery_time,arg_min(r.source_packet_key,(r.corrected,r.source_packet_key)) AS recovery_key,
        count(DISTINCT u.source_frame) AS attempts
        FROM byte_missing_atoms m JOIN byte_obs r ON r.point=m.point_b AND r.base_key=m.base_key AND r.is_atom AND r.eligible
            AND r.corrected>m.corrected AND r.corrected<=m.corrected+60 AND r.packet_key<>m.packet_key
        JOIN byte_obs u ON u.point=m.point_a AND u.packet_key=r.packet_key AND u.is_atom AND u.eligible
            AND u.corrected>m.corrected
        GROUP BY m.point_a,m.point_b,m.source_packet_key,m.frame
-- name: byte_recovered_attempts
UPDATE byte_recovered_atoms r SET attempts=(
        SELECT count(DISTINCT u.source_frame) FROM byte_missing_atoms m JOIN byte_obs u
        ON u.point=m.point_a AND u.base_key=m.base_key AND u.is_atom AND u.eligible
        AND u.corrected>m.corrected AND u.corrected<=r.recovery_time
        WHERE m.point_a=r.point_a AND m.point_b=r.point_b AND m.frame=r.frame)
-- name: byte_recoveries
CREATE OR REPLACE TABLE byte_recoveries AS SELECT m.point_a,m.point_b,m.source_packet_key AS packet_key,
        max(r.recovery_time) AS recovery_time,arg_max(r.recovery_key,(r.recovery_time,r.recovery_key)) AS recovery_key,max(r.attempts) AS attempts
        FROM byte_missing_atoms m LEFT JOIN byte_recovered_atoms r ON m.point_a=r.point_a AND m.point_b=r.point_b AND m.frame=r.frame
        GROUP BY m.point_a,m.point_b,m.source_packet_key HAVING count(r.frame)=count(*)
-- name: largest_frame
SELECT coalesce(sum(n),0),coalesce(max(n),0) FROM (
        SELECT r.point,r.frame,count(*) AS n FROM range_intervals r JOIN range_atoms a
        ON r.flow=a.flow AND r.direction=a.direction AND a.lo>=r.lo AND a.hi<=r.hi AND a.hi>a.lo
        WHERE r.flow=? GROUP BY r.point,r.frame)
-- name: flow_byte_obs
CREATE OR REPLACE TABLE byte_obs AS
        SELECT o.point,-(o.frame*65536+row_number() OVER(PARTITION BY o.point,o.frame ORDER BY a.lo,a.hi,r.kind)) AS frame,
            o.frame AS source_frame,o.packet_key AS source_packet_key,o.flow,o.direction,o.ts,o.corrected,o.stream,
            a.lo,a.hi,CASE WHEN r.kind='data' THEN a.hi-a.lo ELSE 0 END AS range_length,
            CASE WHEN r.kind='data' THEN substr(coalesce(o.prefix,''),2*((a.lo-o.seq-CASE WHEN (o.flags&2)>0 THEN 1 ELSE 0 END+{MOD})%{MOD})+1,2*(a.hi-a.lo)) ELSE '' END AS prefix,
            md5(o.flow||o.direction||':'||a.lo||':'||a.hi||':'||r.kind) AS base_key,
            md5(o.flow||o.direction||':'||a.lo||':'||a.hi||':'||r.kind) AS packet_key,
            NULL::BIGINT AS occurrence,true AS eligible,NULL::VARCHAR AS excluded_reason,true AS is_atom,r.kind
        FROM range_intervals r JOIN range_atoms a ON r.flow=a.flow AND r.direction=a.direction
            AND a.lo>=r.lo AND a.hi<=r.hi AND a.hi>a.lo
        JOIN obs o ON o.point=r.point AND o.frame=r.frame WHERE r.flow=?
-- name: flow_clock_atoms
INSERT INTO byte_obs WITH chosen AS (
        SELECT c.* FROM calibration c WHERE eligible AND EXISTS(SELECT 1 FROM obs o WHERE o.point=c.point AND o.frame=c.frame AND o.proto='TCP' AND o.flow=?)
        QUALIFY row_number() OVER(PARTITION BY point,frame ORDER BY
            CASE WHEN EXISTS(SELECT 1 FROM byte_calibration b WHERE b.point=c.point AND b.frame=c.frame AND b.packet_key=c.packet_key) THEN 0 ELSE 1 END,c.packet_key)=1)
        SELECT o.point,o.frame,o.frame,o.packet_key,o.flow,o.direction,o.ts,o.corrected,o.stream,
            NULL,NULL,0,'',c.packet_key,c.packet_key,NULL,o.eligible,o.excluded_reason,false,'clock'
        FROM chosen c JOIN obs o ON o.point=c.point AND o.frame=c.frame
        QUALIFY row_number() OVER(PARTITION BY o.point,o.direction ORDER BY hash(c.packet_key),c.packet_key,o.frame)<=256
-- name: matches_view
CREATE OR REPLACE VIEW observation_matches AS
        SELECT a.point AS point_a,b.point AS point_b,a.direction,a.corrected AS time_a,b.corrected AS time_b,
        a.packet_key AS key_a,b.packet_key AS key_b,a.length AS bytes,false AS byte_range
        FROM obs a JOIN obs b USING(packet_key) WHERE a.point<>b.point AND (a.proto<>'TCP' OR a.flow=b.flow) AND a.eligible AND b.eligible {exclusion}{extra}
-- name: matches_exclusion
AND NOT (a.proto='TCP' AND (a.length>0 OR (a.flags&3)>0) AND (a.flags&4)=0 AND EXISTS(SELECT 1 FROM byte_flows f WHERE f.flow=a.flow AND f.direction=a.direction))
-- name: matches_extra
 UNION ALL SELECT point_a,point_b,direction,time_a,time_b,key_a,key_b,bytes,true FROM byte_links
-- name: range_condition
({a_alias}.proto='TCP' AND ({a_alias}.length>0 OR ({a_alias}.flags&3)>0) AND ({a_alias}.flags&4)=0 AND EXISTS(SELECT 1 FROM byte_flows f WHERE f.flow={a_alias}.flow AND f.direction={a_alias}.direction))
-- name: byte_later
CREATE OR REPLACE TEMP TABLE byte_later AS SELECT m.source_packet_key,
        bool_and(EXISTS(SELECT 1 FROM byte_obs later WHERE later.packet_key=m.packet_key AND later.is_atom AND later.eligible
            AND later.point IN (SELECT unnest(?::VARCHAR[])))) AS later_seen
        ,bool_or(EXISTS(SELECT 1 FROM byte_obs later WHERE later.packet_key=m.packet_key AND later.is_atom AND later.eligible
            AND later.point IN (SELECT unnest(?::VARCHAR[])))) AS partly_seen
        FROM byte_missing_atoms m WHERE m.point_a=? AND m.point_b=? AND m.direction=? GROUP BY m.source_packet_key
-- name: byte_later_update
UPDATE missing SET later_seen=l.later_seen,
        range_unknown=CASE WHEN l.partly_seen AND NOT l.later_seen THEN 'Mixed byte-range delivery evidence within one captured frame; partial capture visibility prevents whole-frame loss attribution' END
        FROM byte_later l WHERE missing.packet_key=l.source_packet_key
-- name: range_recoveries_delete
DELETE FROM recoveries WHERE packet_key IN (SELECT source_packet_key FROM byte_missing WHERE point_a=? AND point_b=?)
-- name: range_recoveries_insert
INSERT INTO recoveries SELECT packet_key,recovery_time,recovery_key,attempts FROM byte_recoveries WHERE point_a=? AND point_b=?
-- name: ack_guard_flows
SELECT DISTINCT m.flow FROM byte_missing_atoms m JOIN acked k
        ON k.packet_key=m.source_packet_key WHERE m.point_a=? AND m.point_b=?
-- name: ack_guard_missing
CREATE OR REPLACE TEMP TABLE ack_guard_missing AS
            SELECT m.source_packet_key,m.base_key,m.corrected,k.ack_key FROM byte_missing_atoms m
            JOIN acked k ON k.packet_key=m.source_packet_key
            WHERE m.point_a=? AND m.point_b=? AND m.flow=?
-- name: ack_guard_times
CREATE OR REPLACE TEMP TABLE ack_guard_times AS SELECT packet_key,corrected
            FROM obs WHERE point=? AND flow=?
-- name: ack_guard_retries
CREATE OR REPLACE TEMP TABLE ack_guard_retries AS SELECT base_key,corrected
            FROM byte_obs WHERE point=? AND flow=? AND is_atom AND eligible
-- name: ack_guard_delete
DELETE FROM acked USING (
            SELECT DISTINCT m.source_packet_key FROM ack_guard_missing m
            JOIN ack_guard_times ack ON ack.packet_key=m.ack_key
            JOIN ack_guard_retries retry ON retry.base_key=m.base_key
                AND retry.corrected>m.corrected AND retry.corrected<ack.corrected
            ) bad WHERE acked.packet_key=bad.source_packet_key
-- name: range_notes
SELECT point,count(*) FILTER(WHERE length>1500) AS large_frames,
        'TCP byte-range matching across segmentation/coalescing; frame timestamps are not individual wire-segment timestamps. Offload checksum artifacts are not classified as errors.' AS note
        FROM obs WHERE proto='TCP' AND flow IN (SELECT flow FROM byte_flows) GROUP BY point ORDER BY point
