-- Verbatim from the reference integrity/common.py, origins.py, payloads.py and path_checks.py (bounded path
-- checks). Tie-breaks appended after the reference ordering are listed in DETERMINISM.md.
-- name: window
CREATE OR REPLACE TEMP TABLE integrity_window AS SELECT ?::DOUBLE AS start,?::DOUBLE AS stop
-- name: view
CREATE OR REPLACE TEMP VIEW integrity_obs AS SELECT o.* FROM obs o,integrity_window w WHERE (w.start IS NULL OR coalesce(o.corrected,o.ts)>=w.start) AND (w.stop IS NULL OR coalesce(o.corrected,o.ts)<w.stop)
-- name: hints
SELECT bool_or((flags&4)>0 OR proto IN ('ICMP','ICMPv6')) AS origins,
            count(DISTINCT dscp)>1 AS dscp, max(mss)>0 AS mss,
            bool_or(json_extract_string(path_fields,'$.tcp_options')<>'') AS options,
            bool_or(length>=1200) AS large FROM obs
-- name: repeats
SELECT point,
            count(*)-count(DISTINCT frame_hash) AS identical,
            count(*)-count(DISTINCT canon || signature) AS repeated,
            count(*) FILTER(WHERE excluded_reason='span_duplicate') AS span FROM obs GROUP BY point
-- name: edges
CREATE OR REPLACE TEMP TABLE integrity_edges AS SELECT unnest(?::VARCHAR[]) AS id,unnest(?::VARCHAR[]) AS point_a,unnest(?::VARCHAR[]) AS point_b,unnest(?::VARCHAR[]) AS direction
-- name: inventories
SELECT id,inventory FROM captures
-- name: candidates
SELECT e.id,b AS packet FROM integrity_edges e JOIN integrity_obs b
                ON b.point=e.point_b AND b.direction=e.direction WHERE ({condition})
                AND NOT EXISTS(SELECT 1 FROM observation_matches m WHERE m.point_a=e.point_a AND m.point_b=b.point AND m.key_b=b.packet_key)
                QUALIFY row_number() OVER(PARTITION BY e.id ORDER BY b.corrected NULLS LAST,b.frame)<=?
                ORDER BY e.id,b.corrected NULLS LAST,b.frame
-- name: changed_pair
SELECT arg_min(a.frame,(a.corrected,a.frame,b.frame)) AS frame_a,
                arg_min(b.frame,(a.corrected,a.frame,b.frame)) AS frame_b,count(*) AS count
                FROM (SELECT * FROM integrity_obs WHERE point=? AND direction=? AND eligible) a
                JOIN (SELECT * FROM integrity_obs WHERE point=? AND direction=? AND eligible) b USING(packet_key)
                WHERE ({condition})
-- name: packet_at
SELECT * FROM integrity_obs WHERE point=? AND frame=? LIMIT 1
-- name: ORIGIN_CONDITION
(b.proto='TCP' AND (b.flags&4)>0) OR (b.proto='ICMP' AND json_extract_string(b.path_fields,'$.icmp_type')='3') OR (b.proto='ICMPv6' AND json_extract_string(b.path_fields,'$.icmp_type') IN ('1','2'))
-- name: upstream_seen
SELECT count(*) FROM obs WHERE point IN (SELECT unnest(?::VARCHAR[])) AND packet_key=?
-- name: origin_samples
SELECT * FROM obs WHERE point=? AND canon=? AND proto='TCP' AND (flags&4)=0 AND eligible
            AND corrected BETWEEN ? AND ? ORDER BY corrected,frame LIMIT 16
-- name: icmp_samples
SELECT * FROM obs WHERE point=? AND src=? AND dst=?
                AND proto='TCP' AND sport=? AND dport=? AND eligible
                AND corrected BETWEEN ? AND ? ORDER BY corrected,frame LIMIT 16
-- name: local_samples
SELECT * FROM obs WHERE point=? AND eligible
            AND packet_key IN (SELECT unnest(?::VARCHAR[])) ORDER BY corrected,frame LIMIT 16
-- name: endpoint_packets
SELECT *,count(*) OVER(PARTITION BY flow,proto) AS origin_count
                FROM integrity_obs WHERE point=? AND direction=? AND ((proto='TCP' AND (flags&4)>0)
                OR (proto='ICMP' AND json_extract_string(path_fields,'$.icmp_type')='3')
                OR (proto='ICMPv6' AND json_extract_string(path_fields,'$.icmp_type') IN ('1','2')))
                QUALIFY row_number() OVER(PARTITION BY flow,proto ORDER BY corrected,frame)=1 ORDER BY corrected,frame LIMIT ?
-- name: endpoint_refs
SELECT * FROM obs WHERE point=? AND canon=? AND (flags&4)=0 AND proto='TCP' AND eligible ORDER BY corrected,frame LIMIT 8
-- name: payload_candidates
SELECT a AS before_packet,b AS after_packet FROM modified_payload_pairs m
                JOIN integrity_obs a ON a.point=m.point_a AND a.frame=m.frame_a
                JOIN integrity_obs b ON b.point=m.point_b AND b.frame=m.frame_b
                WHERE m.point_a=? AND m.point_b=? AND m.direction=? AND a.eligible AND b.eligible
                    AND a.translation_reason IS NULL AND b.translation_reason IS NULL
                ORDER BY a.corrected,a.frame,b.frame LIMIT ?
-- name: MSS_CONDITION
(a.flags&2)>0 AND a.mss>b.mss AND b.mss>0
-- name: mtu_losses
SELECT a.flow,min(least(a.length,coalesce(e.missing_bytes,a.length))) AS min_length,count(DISTINCT a.seq) AS lost_ranges,
                first(a ORDER BY a.corrected,a.frame) AS packet,list(DISTINCT a.frame ORDER BY a.frame) AS large_frames FROM events e JOIN obs a ON a.packet_key=e.packet_key AND a.point=e.point_a
                WHERE e.point_a=? AND e.point_b=? AND e.direction=? AND e.kind IN ('impactful_loss','unrecovered_loss','recovered_loss')
                AND a.proto='TCP' AND a.length BETWEEN 1200 AND 9000
                AND coalesce(e.missing_bytes,a.length)>=greatest(1200,.9*coalesce(
                    (SELECT min(m.mss) FROM obs m WHERE m.flow=a.flow AND m.direction<>a.direction AND m.mss>0),
                    CASE WHEN contains(a.src,':') THEN 1440 ELSE 1460 END))
                GROUP BY a.flow HAVING count(DISTINCT a.seq)>=3 ORDER BY a.flow LIMIT ?
-- name: mtu_small
SELECT a.* FROM obs a WHERE a.point=? AND a.flow=? AND a.direction=?
                    AND a.length>0 AND a.length<? AND EXISTS(SELECT 1 FROM observation_matches m
                    WHERE m.point_a=a.point AND m.point_b=? AND m.key_a=a.packet_key)
                    ORDER BY a.corrected,a.frame LIMIT 3
-- name: mtu_feedback
SELECT * FROM obs o WHERE proto IN ('ICMP','ICMPv6')
                    AND ((json_extract_string(path_fields,'$.icmp_type')='3' AND json_extract_string(path_fields,'$.icmp_code')='4')
                         OR (proto='ICMPv6' AND json_extract_string(path_fields,'$.icmp_type')='2'))
                    AND EXISTS(SELECT 1 FROM obs p WHERE p.flow=?
                       AND p.src=json_extract_string(o.path_fields,'$.quoted.src')
                       AND p.dst=json_extract_string(o.path_fields,'$.quoted.dst')
                       AND p.sport=try_cast(json_extract_string(o.path_fields,'$.quoted.sport') AS INTEGER)
                       AND p.dport=try_cast(json_extract_string(o.path_fields,'$.quoted.dport') AS INTEGER)
                       AND p.raw_seq=try_cast(json_extract_string(o.path_fields,'$.quoted.seq') AS BIGINT))
                    ORDER BY corrected,point,frame LIMIT 4
-- name: mtu_large
SELECT * FROM obs WHERE point=? AND frame IN (SELECT unnest(?::BIGINT[])) ORDER BY corrected,frame
-- name: TTL_CONDITION
a.ttl-b.ttl>1 OR a.ttl<b.ttl
-- name: DSCP_CONDITION
a.dscp<>b.dscp
-- name: OPTION_CONDITION
a.caplen=a.wirelen AND b.caplen=b.wirelen AND ((json_extract_string(a.path_fields,'$.sack_permitted')='true' AND json_extract_string(b.path_fields,'$.sack_permitted')='false') OR (json_extract_string(a.path_fields,'$.window_scale') IS NOT NULL AND json_extract_string(b.path_fields,'$.window_scale') IS NULL) OR (json_extract_string(a.path_fields,'$.timestamp_value') IS NOT NULL AND json_extract_string(b.path_fields,'$.timestamp_value') IS NULL))
-- name: span_duplicate
SELECT *,count(*) OVER() AS duplicate_count FROM obs WHERE point=? AND excluded_reason='span_duplicate' ORDER BY corrected,frame LIMIT 1
-- name: duplicate_pairs
WITH sequenced AS (
        SELECT point,frame,ts,ttl,lag(frame) OVER w AS previous_frame,
        lag(ts) OVER w AS previous_ts,lag(ttl) OVER w AS previous_ttl
        FROM integrity_obs WHERE point=? AND {eligible}
        WINDOW w AS (PARTITION BY {partition} ORDER BY ts,frame)),
        candidate AS (SELECT * FROM sequenced WHERE {condition} ORDER BY ts,frame LIMIT 1)
        SELECT a AS first_packet,b AS repeat_packet FROM candidate c
        JOIN obs a ON a.point=c.point AND a.frame=c.previous_frame
        JOIN obs b ON b.point=c.point AND b.frame=c.frame
-- name: asymmetric_pairs
WITH forward_first AS (
                SELECT * FROM integrity_obs WHERE point=? AND direction='forward' AND eligible
                QUALIFY row_number() OVER(PARTITION BY flow ORDER BY corrected,frame)=1),
                return_first AS (
                SELECT * FROM integrity_obs WHERE point IN (SELECT unnest(?::VARCHAR[])) AND direction='reverse' AND eligible
                QUALIFY row_number() OVER(PARTITION BY flow ORDER BY corrected,point,frame)=1)
                SELECT f AS forward_packet,r AS return_packet FROM forward_first f JOIN return_first r USING(flow)
                WHERE NOT EXISTS(SELECT 1 FROM integrity_obs q WHERE q.flow=f.flow AND q.direction='reverse' AND q.point IN (SELECT unnest(?::VARCHAR[])))
                AND (SELECT count(DISTINCT point) FROM integrity_obs q WHERE q.flow=f.flow AND q.direction='reverse' AND q.eligible AND q.point IN (SELECT unnest(?::VARCHAR[])))=2
                ORDER BY f.corrected,r.corrected,f.frame,r.point,r.frame LIMIT 1
