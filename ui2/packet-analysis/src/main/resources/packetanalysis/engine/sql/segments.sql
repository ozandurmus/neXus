-- Verbatim from the reference analysis/segments.py.
-- name: unresolved_nat
SELECT count(*) FROM nat_point_tuples x WHERE x.point IN (?,?)
            AND NOT EXISTS(SELECT 1 FROM nat_point_tuples y WHERE y.point IN (?,?) AND y.point<>x.point AND y.canon=x.canon)
            AND x.canon NOT IN (SELECT canon FROM tuple_lookup WHERE tuple_key IN (SELECT unnest(?::VARCHAR[])))
-- name: endpoint_quality
SELECT point,
        CASE WHEN direction='unknown' THEN 'direction_unknown'
             WHEN NOT eligible THEN coalesce(excluded_reason,'unsupported_identity') ELSE 'eligible' END AS reason,
        count(*) AS count FROM obs WHERE point IN (?,?) AND (direction=? OR direction='unknown')
        AND (? IS NULL OR coalesce(corrected,ts)>=?) AND (? IS NULL OR coalesce(corrected,ts)<=?)
        GROUP BY point,reason ORDER BY point,reason
-- name: matched
SELECT count(*) AS matched,min(time_b-time_a)*1000 AS min_ms,
        quantile_cont((time_b-time_a)*1000,.5) AS p50_ms,
        quantile_cont((time_b-time_a)*1000,.05) AS p05_ms,
        count(*) FILTER(WHERE (time_b-time_a)*1000 < -?) AS negative_outliers,
        quantile_cont((time_b-time_a)*1000,.95) AS p95_ms,
        max((time_b-time_a)*1000) AS max_ms
        FROM observation_matches WHERE point_a=? AND point_b=? AND direction=?
        AND (? IS NULL OR time_a>=?) AND (? IS NULL OR time_a<=?)
-- name: outer_points
SELECT DISTINCT point FROM obs WHERE json_extract_string(path_fields,'$.tunnel.layer')='outer'
-- name: unselected_points
SELECT DISTINCT point FROM obs WHERE unsupported LIKE 'Tunnel encapsulation%' OR unsupported LIKE 'Capture layer decode unavailable:%'
-- name: flow_summary
CREATE OR REPLACE TABLE flow_summary AS
        WITH base AS (SELECT flow,max(coalesce(translation_reason,range_reason)) AS matching_unknown_reason,min(corrected) AS start,max(corrected) AS "end",count(DISTINCT point) AS points,
            count(*) FILTER(WHERE retrans) AS retrans_observations,
            bool_or((flags&4)>0) AS has_reset, max(rtt)*1000 AS max_rtt_ms,
            min(canon) AS tuple, count(*) FILTER(WHERE (flags&2)>0 AND (flags&16)=0) AS syns,
            count(*) FILTER(WHERE (flags&18)=18) AS synacks FROM obs GROUP BY flow),
        bytes AS (SELECT flow,sum(length) AS bytes,count(packet_key) AS unique_observations FROM
            (SELECT flow,packet_key,max(length) AS length FROM obs GROUP BY flow,packet_key) GROUP BY flow),
        losses AS (SELECT flow,count(*) FILTER(WHERE kind='impactful_loss') AS impactful_loss,
            count(*) FILTER(WHERE kind='recovered_loss') AS recovered_loss,
            count(*) FILTER(WHERE kind='capture_miss') AS capture_miss,
            count(*) FILTER(WHERE kind='unrecovered_loss') AS unrecovered_loss,
            count(*) FILTER(WHERE kind='handshake_blocked') AS handshake_blocked,
            count(*) FILTER(WHERE kind='confirmed_device_drop') AS confirmed_device_drop,
            count(*) FILTER(WHERE kind='unknown') AS unknown_events,
            max(greatest(impact_ms,recovery_ms)) AS max_stall_ms
            FROM events GROUP BY flow)
        SELECT base.*,NULL::DOUBLE AS handshake_rtt_ms,NULL::VARCHAR AS reset_origin,bytes.bytes,bytes.unique_observations,coalesce(impactful_loss,0) AS impactful_loss,
            coalesce(recovered_loss,0) AS recovered_loss,coalesce(capture_miss,0) AS capture_miss,max_stall_ms,
            coalesce(unrecovered_loss,0) AS unrecovered_loss,coalesce(handshake_blocked,0) AS handshake_blocked,
            coalesce(confirmed_device_drop,0) AS confirmed_device_drop,
            coalesce(unknown_events,0) AS unknown_events,
            (syns>0 AND synacks=0) OR coalesce(handshake_blocked,0)>0 AS handshake_incomplete
        FROM base JOIN bytes USING(flow) LEFT JOIN losses USING(flow)
-- name: flow_summary_bytes
UPDATE flow_summary SET bytes=x.bytes FROM (
            SELECT flow,sum(length) AS bytes FROM (
                SELECT o.flow,o.packet_key,max(o.length) AS length FROM obs o WHERE NOT
                    (o.proto='TCP' AND o.length>0 AND EXISTS(SELECT 1 FROM byte_flows f WHERE f.flow=o.flow AND f.direction=o.direction))
                    GROUP BY o.flow,o.packet_key
                UNION ALL SELECT flow,packet_key,max(range_length) FROM byte_obs WHERE is_atom AND eligible GROUP BY flow,packet_key)
            GROUP BY flow) x WHERE flow_summary.flow=x.flow
-- name: quality
SELECT point,count(*) AS packets,count(*) FILTER(WHERE NOT eligible) AS excluded,
        count(*) FILTER(WHERE caplen<wirelen) AS truncated,count(*) FILTER(WHERE length>1500) AS possible_offload,
        count(*) FILTER(WHERE direction='unknown') AS unknown_direction FROM obs GROUP BY point ORDER BY point
-- name: captures
SELECT id,name,inventory FROM captures
-- name: index_counts
SELECT *,input_frames-retained_frames AS excluded_frames,input_flows-retained_flows AS excluded_flows FROM index_scope
