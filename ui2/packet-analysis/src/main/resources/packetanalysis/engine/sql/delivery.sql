-- Verbatim from the reference delivery.py prepare_delivery: it filters observation_matches (rejected
-- retry substitutions), which the core segments read; the delivery report itself stays with its lane.
-- name: delivery_edges
CREATE OR REPLACE TEMP TABLE delivery_edges(point_a VARCHAR,point_b VARCHAR,direction VARCHAR)
-- name: delivery_edges_insert
INSERT INTO delivery_edges VALUES (?,?,?)
-- name: delivery_repeats
CREATE OR REPLACE TEMP TABLE delivery_repeats AS SELECT point,flow,direction,seq,count(*) AS copies
        FROM obs WHERE proto='TCP' AND length>0 AND eligible GROUP BY ALL HAVING count(*)>1
-- name: delivery_candidates
CREATE OR REPLACE TABLE delivery_candidates AS SELECT DISTINCT m.point_a,m.point_b,m.direction,
        a.flow,a.frame AS frame_a,b.frame AS frame_b,m.key_a,m.key_b,m.time_a,m.time_b,
        a.seq,a.ipid AS id_a,b.ipid AS id_b,a.src,least(a.length,b.length) AS bytes,
        a.seq=b.seq AS same_start,coalesce(r.copies,1) AS copies
        FROM observation_matches m JOIN delivery_edges USING(point_a,point_b,direction) JOIN obs a ON a.point=m.point_a AND a.packet_key=m.key_a
        JOIN obs b ON b.point=m.point_b AND b.packet_key=m.key_b
        LEFT JOIN delivery_repeats r ON r.point=a.point AND r.flow=a.flow AND r.direction=a.direction AND r.seq=a.seq
        WHERE a.proto='TCP' AND a.length>0 AND b.length>0
-- name: delivery_id_policy
CREATE OR REPLACE TABLE delivery_id_policy AS SELECT point_a,point_b,direction,flow,
        count(*)>=4 AND count(DISTINCT id_a)>1 AND avg((id_a=id_b)::INTEGER)>=.5 AS preserved_ids
        FROM delivery_candidates WHERE same_start AND NOT contains(src,':') GROUP BY ALL
-- name: delivery_rejections
CREATE OR REPLACE TABLE delivery_rejections AS SELECT c.*,
        CASE WHEN p.preserved_ids AND c.same_start AND c.id_a<>c.id_b
              AND EXISTS(SELECT 1 FROM obs u WHERE u.point=c.point_a AND u.flow=c.flow AND u.direction=c.direction
                  AND u.seq=c.seq AND u.ipid=c.id_b AND u.corrected>c.time_a AND u.corrected<=c.time_b+.005)
              AND NOT EXISTS(SELECT 1 FROM obs o WHERE o.point=c.point_b AND o.flow=c.flow AND o.direction=c.direction
                  AND o.seq=c.seq AND o.ipid=c.id_a AND o.eligible)
              THEN 'retry_substitution'
             ELSE 'unknown' END AS status
        FROM delivery_candidates c LEFT JOIN delivery_id_policy p USING(point_a,point_b,direction,flow)
        WHERE (p.preserved_ids AND c.same_start AND c.id_a<>c.id_b)
           OR (c.copies>1 AND NOT coalesce(p.preserved_ids,false) AND (time_b-time_a)*1000>=?)
-- name: delivery_rejection_peers
CREATE OR REPLACE TABLE delivery_rejections AS WITH anchors AS(SELECT * FROM delivery_rejections)
        SELECT * FROM anchors UNION ALL
        SELECT DISTINCT c.*,'unknown' AS status FROM delivery_candidates c JOIN anchors r
          ON r.point_a=c.point_a AND r.point_b=c.point_b AND r.direction=c.direction AND r.key_b=c.key_b
        WHERE NOT EXISTS(SELECT 1 FROM anchors x WHERE x.point_a=c.point_a AND x.point_b=c.point_b
          AND x.direction=c.direction AND x.key_a=c.key_a)
-- name: matches_definition
SELECT sql FROM duckdb_views() WHERE view_name='observation_matches'
-- name: filtered_matches
CREATE OR REPLACE VIEW observation_matches AS SELECT m.* FROM unfiltered_observation_matches m
        WHERE NOT EXISTS(SELECT 1 FROM delivery_rejections r WHERE r.point_a=m.point_a AND r.point_b=m.point_b
                         AND r.key_a=m.key_a AND r.direction=m.direction)
-- Verbatim from the reference delivery.py delivery_report (time and event detector lane).
-- name: delivery_positions
CREATE OR REPLACE TEMP TABLE delivery_positions(point VARCHAR,direction VARCHAR,position INTEGER)
-- name: delivery_positions_insert
INSERT INTO delivery_positions VALUES (?,?,?)
-- name: delivery_events
CREATE OR REPLACE TABLE delivery_events AS SELECT r.*,
      CASE WHEN e.reason IS NOT NULL THEN 'unknown'
           WHEN EXISTS(SELECT 1 FROM obs o JOIN delivery_positions pos ON pos.point=o.point AND pos.direction=o.direction
             JOIN delivery_positions target ON target.point=r.point_b AND target.direction=r.direction
             WHERE o.flow=r.flow AND o.direction=r.direction AND o.seq=r.seq AND o.ipid=r.id_a
             AND o.eligible AND pos.position>target.position)
             OR EXISTS(SELECT 1 FROM obs a WHERE a.point=r.point_a AND a.flow=r.flow AND a.direction<>r.direction
               AND (a.flags&16)>0 AND a.corrected>r.time_a AND a.corrected<r.time_b
               AND ((a.ack-r.seq-r.bytes+4294967296)%4294967296)<2147483648)
             THEN 'capture_miss'
           ELSE r.status END AS classification
      FROM delivery_rejections r JOIN cycle_edges e ON e.a=r.point_a AND e.b=r.point_b AND e.direction=r.direction
-- name: delivery_series
CREATE OR REPLACE TABLE delivery_series AS WITH data AS(
        SELECT c.point_a,c.point_b,c.direction,c.flow,c.time_a,c.frame_a,arg_max(c.frame_b,CASE WHEN c.time_b IS NOT NULL THEN (c.time_b,c.frame_b) END) AS frame_b,
        CASE WHEN e.reason IS NOT NULL THEN 'unknown' WHEN max(time_b-time_a)*1000>greatest(1,2*e.uncertainty_ms) THEN 'delayed' ELSE 'matched' END AS classification,
        max(time_b-time_a)*1000 AS wait_ms FROM delivery_candidates c
        JOIN cycle_edges e ON e.a=c.point_a AND e.b=c.point_b AND e.direction=c.direction
        WHERE NOT EXISTS(SELECT 1 FROM delivery_rejections r WHERE r.point_a=c.point_a AND r.point_b=c.point_b
          AND r.key_a=c.key_a AND r.direction=c.direction)
        GROUP BY c.point_a,c.point_b,c.direction,c.flow,c.time_a,c.frame_a,c.key_a,e.reason,e.uncertainty_ms
        UNION ALL SELECT point_a,point_b,direction,flow,time_a,frame_a,frame_b,classification,(time_b-time_a)*1000 FROM delivery_events
        UNION ALL SELECT point_a,point_b,direction,flow,ts,NULL,NULL,
            CASE WHEN kind IN ('impactful_loss','recovered_loss') THEN 'dropped'
                 WHEN kind='capture_miss' THEN 'capture_miss' ELSE 'unknown' END,coalesce(recovery_ms,impact_ms)
            FROM events e WHERE is_data AND NOT EXISTS(SELECT 1 FROM delivery_rejections r
              WHERE r.point_a=e.point_a AND r.point_b=e.point_b AND r.key_a=e.packet_key))
        SELECT point_a,point_b,direction,flow,floor(time_a/?) * ? AS start,count(*) AS observations,
          count(*) FILTER(WHERE classification='delayed') AS delayed,
          count(*) FILTER(WHERE classification IN ('dropped','retry_substitution')) AS dropped,
          count(*) FILTER(WHERE classification='capture_miss') AS capture_misses,
          count(*) FILTER(WHERE classification='unknown') AS unknown,
          max(wait_ms) FILTER(WHERE classification='delayed') AS max_delay_ms,
          max(wait_ms) FILTER(WHERE classification IN ('dropped','retry_substitution')) AS max_recovery_ms,
          min(frame_a) AS evidence_frame_a,min(frame_b) AS evidence_frame_b
          FROM data WHERE (? IS NULL OR time_a>=?) AND (? IS NULL OR time_a<=?) GROUP BY ALL
-- name: adjacent_match_rates
CREATE OR REPLACE TABLE adjacent_match_rates AS SELECT *,
        100.0*(observations-dropped-capture_misses-unknown)/nullif(observations,0) AS match_percent,
        CASE WHEN unknown>0 THEN NULL ELSE 100.0*dropped/nullif(observations,0) END AS missing_percent FROM delivery_series
-- name: substitutions
SELECT point_a,point_b,direction,flow,count(*) AS count,min(time_a) AS start,max(time_b) AS stop,
        max((time_b-time_a)*1000) AS max_recovery_ms,arg_min(frame_a,CASE WHEN time_a IS NOT NULL THEN (time_a,frame_a,frame_b) END) AS frame_a,arg_min(frame_b,CASE WHEN time_a IS NOT NULL THEN (time_a,frame_a,frame_b) END) AS frame_b
        FROM delivery_events WHERE classification='retry_substitution' GROUP BY ALL ORDER BY count DESC,start,point_a,point_b,direction,flow LIMIT 100
-- name: series_rows
SELECT * FROM adjacent_match_rates ORDER BY start,point_a,point_b,flow,direction LIMIT 20000
