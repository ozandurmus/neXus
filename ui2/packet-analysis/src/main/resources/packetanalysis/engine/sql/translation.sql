-- Verbatim from the reference translation.py ({MOD} is the module constant 4294967296).
-- name: sequence_models
CREATE OR REPLACE TABLE sequence_models (
        ingress VARCHAR,egress VARCHAR,point VARCHAR,old_flow VARCHAR,ingress_stream BIGINT,stream BIGINT,
        forward_offset BIGINT,reverse_offset BIGINT,boundary_forward_offset BIGINT,boundary_reverse_offset BIGINT,
        samples BIGINT,status VARCHAR,reason VARCHAR,frame_a BIGINT,frame_b BIGINT)
-- name: translation_links
CREATE OR REPLACE TEMP TABLE translation_links (
        point_a VARCHAR,stream_a BIGINT,point_b VARCHAR,stream_b BIGINT,old_flow VARCHAR)
-- name: sequence_anchors
CREATE OR REPLACE TEMP TABLE sequence_anchors AS
            WITH widths AS (SELECT flow,direction,length,min(length(coalesce(prefix,''))) AS width
                FROM obs WHERE proto='TCP' GROUP BY flow,direction,length),
            shapes AS (SELECT o.*,md5(direction||':'||flags||':'||o.length||':'||left(coalesce(prefix,''),width)) AS shape
                FROM obs o JOIN widths USING(flow,direction,length) WHERE proto='TCP' AND stream>=0 AND coalesce(o.unsupported,'')=''),
            keys AS (SELECT *, 'payload:'||shape AS key FROM shapes WHERE length(prefix)>=16
                UNION ALL SELECT *, 'id:'||ipid||':'||shape AS key FROM shapes)
            SELECT * FROM keys QUALIFY count(*) OVER(PARTITION BY point,flow,key)=1
-- name: sequence_pairs
CREATE OR REPLACE TEMP TABLE sequence_pairs AS
                SELECT DISTINCT x.flow,x.stream AS sa,y.stream AS sb,x.frame AS fa,y.frame AS fb,x.direction,
                    (y.raw_seq-x.seq+{MOD})%{MOD} AS seq_delta,
                    CASE WHEN (x.flags&16)>0 THEN (y.raw_ack-x.ack+{MOD})%{MOD} END AS ack_delta,
                    (y.raw_seq-x.raw_seq+{MOD})%{MOD} AS raw_seq_delta,
                    CASE WHEN (x.flags&16)>0 THEN (y.raw_ack-x.raw_ack+{MOD})%{MOD} END AS raw_ack_delta,
                    y.ts-x.ts AS dt
                FROM sequence_anchors x JOIN sequence_anchors y ON x.flow=y.flow AND x.key=y.key
                WHERE x.point=? AND y.point=?
-- name: sequence_found
WITH deltas AS (
                SELECT *,CASE WHEN direction='forward' THEN seq_delta ELSE ack_delta END AS f,
                    CASE WHEN direction='reverse' THEN seq_delta ELSE ack_delta END AS r,
                    CASE WHEN direction='forward' THEN raw_seq_delta ELSE raw_ack_delta END AS raw_f,
                    CASE WHEN direction='reverse' THEN raw_seq_delta ELSE raw_ack_delta END AS raw_r
                FROM sequence_pairs)
                SELECT flow,sa,sb,count(*) AS n,min(f) AS f,max(f) AS f_max,min(r) AS r,max(r) AS r_max,
                    count(f) AS nf,count(r) AS nr,min(raw_f) AS raw_f,min(raw_r) AS raw_r,
                    max(dt)-min(dt) AS spread,min(fa) AS fa,arg_min(fb,(fa,fb)) AS fb
                FROM deltas GROUP BY flow,sa,sb
-- name: translation_links_insert
INSERT INTO translation_links VALUES (?,?,?,?,?)
-- name: offset_candidates
CREATE OR REPLACE TEMP TABLE offset_candidates(flow VARCHAR,sa BIGINT,sb BIGINT,f BIGINT,r BIGINT)
-- name: offset_candidates_insert
INSERT INTO offset_candidates VALUES (?,?,?,?,?)
-- name: inconsistent
WITH xx AS (
                SELECT o.*,m.sa,m.sb,count(*) OVER(PARTITION BY o.flow,m.sa,m.sb,direction,flags,length,seq) AS ns,
                    count(*) OVER(PARTITION BY o.flow,m.sa,m.sb,direction,flags,length,ack) AS na
                FROM obs o JOIN offset_candidates m ON o.flow=m.flow AND o.stream=m.sa
                WHERE o.point=? AND coalesce(o.unsupported,'')=''),
            yy0 AS (SELECT o.*,m.sa,m.sb,
                (raw_seq-CASE WHEN direction='forward' THEN m.f ELSE m.r END+{MOD})%{MOD} AS qs,
                CASE WHEN (flags&16)>0 THEN (raw_ack-CASE WHEN direction='forward' THEN m.r ELSE m.f END+{MOD})%{MOD} ELSE raw_ack END AS qa
                FROM obs o JOIN offset_candidates m ON o.flow=m.flow AND o.stream=m.sb
                WHERE o.point=? AND coalesce(o.unsupported,'')=''),
            yy AS (SELECT *,count(*) OVER(PARTITION BY flow,sa,sb,direction,flags,length,qs) AS ns,
                count(*) OVER(PARTITION BY flow,sa,sb,direction,flags,length,qa) AS na FROM yy0),
            pairs AS (
                SELECT x.flow,x.sa,x.sb,x.seq,x.ack,x.flags,y.qs,y.qa FROM xx x JOIN yy y
                ON x.flow=y.flow AND x.sa=y.sa AND x.sb=y.sb AND x.direction=y.direction AND x.flags=y.flags
                AND x.length=y.length AND x.seq=y.qs AND x.ns=1 AND y.ns=1
                WHERE left(coalesce(x.prefix,''),least(length(coalesce(x.prefix,'')),length(coalesce(y.prefix,''))))=
                      left(coalesce(y.prefix,''),least(length(coalesce(x.prefix,'')),length(coalesce(y.prefix,''))))
                UNION ALL
                SELECT x.flow,x.sa,x.sb,x.seq,x.ack,x.flags,y.qs,y.qa FROM xx x JOIN yy y
                ON x.flow=y.flow AND x.sa=y.sa AND x.sb=y.sb AND x.direction=y.direction AND x.flags=y.flags
                AND x.length=y.length AND x.ack=y.qa AND x.na=1 AND y.na=1 AND (x.flags&16)>0
                WHERE left(coalesce(x.prefix,''),least(length(coalesce(x.prefix,'')),length(coalesce(y.prefix,''))))=
                      left(coalesce(y.prefix,''),least(length(coalesce(x.prefix,'')),length(coalesce(y.prefix,'')))))
            SELECT DISTINCT flow,sa,sb FROM pairs WHERE seq<>qs OR ((flags&16)>0 AND ack<>qa)
-- name: models_insert
INSERT INTO sequence_models VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
-- name: point_sessions
SELECT flow,stream,min(frame) FROM obs WHERE point=? AND proto='TCP' GROUP BY flow,stream
-- name: model_exists
SELECT count(*) FROM sequence_models WHERE ingress=? AND point=? AND old_flow=? AND {column}=?
-- name: bad_sequence_sessions
CREATE OR REPLACE TEMP TABLE bad_sequence_sessions AS
            SELECT old_flow,ingress_stream,min(reason) AS reason FROM sequence_models
            WHERE ingress=? AND point=? AND status='unknown' GROUP BY old_flow,ingress_stream
-- name: propagate_bad
UPDATE sequence_models SET status='unknown',reason=bad.reason
            FROM bad_sequence_sessions bad WHERE ingress=? AND sequence_models.old_flow=bad.old_flow
            AND sequence_models.ingress_stream=bad.ingress_stream
-- name: ingress_models
SELECT * FROM sequence_models WHERE ingress=?
-- name: sequence_map
CREATE OR REPLACE TEMP TABLE sequence_map(point VARCHAR,old_flow VARCHAR,stream BIGINT,f BIGINT,r BIGINT,apply_offset BOOLEAN,reason VARCHAR)
-- name: sequence_map_insert
INSERT INTO sequence_map VALUES (?,?,?,?,?,?,?)
-- name: sequence_map_grouped
CREATE OR REPLACE TEMP TABLE sequence_map AS SELECT point,old_flow,stream,
            min(f) AS f,min(r) AS r,bool_or(apply_offset) AS apply_offset,max(reason) AS reason FROM sequence_map GROUP BY point,old_flow,stream
-- name: apply_offsets
CREATE OR REPLACE TABLE obs AS SELECT o.* REPLACE(
            CASE WHEN m.apply_offset AND m.reason IS NULL THEN (o.raw_seq-CASE WHEN direction='forward' THEN m.f ELSE m.r END+{MOD})%{MOD} ELSE o.seq END AS seq,
            CASE WHEN m.apply_offset AND m.reason IS NULL AND (flags&16)>0 THEN (o.raw_ack-CASE WHEN direction='forward' THEN m.r ELSE m.f END+{MOD})%{MOD} ELSE o.ack END AS ack,
            coalesce(nullif(o.unsupported,''),m.reason) AS unsupported,coalesce(o.translation_reason,m.reason) AS translation_reason)
            FROM obs o LEFT JOIN sequence_map m ON o.point=m.point AND o.flow=m.old_flow AND o.stream=m.stream
-- name: translated_signatures
CREATE OR REPLACE TABLE obs AS SELECT o.* REPLACE(
            CASE WHEN o.proto='TCP' AND o.flow IN (SELECT flow FROM obs WHERE raw_seq<>seq OR raw_ack<>ack)
            THEN md5(proto||':'||ipid||':'||seq||':'||ack||':'||flags||':'||length) ELSE signature END AS signature)
            FROM obs o
-- name: translated_keys
UPDATE obs SET packet_key=md5(canon||signature)
-- name: sequence_report
SELECT m.*,arg_min(o.flow,(o.frame,o.flow)) AS flow FROM sequence_models m
        LEFT JOIN obs o ON o.point=m.ingress AND o.stream=m.ingress_stream
        WHERE m.point=m.egress GROUP BY ALL ORDER BY m.ingress,m.ingress_stream,m.point,m.old_flow,m.stream,m.frame_a,m.frame_b LIMIT 200
