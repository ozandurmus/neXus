-- Verbatim from the reference proxy_analysis.py (prepare_requests, correlate), proxy_matching.py (pair_requests),
-- proxy_waterfall.py (add_dwell), proxy_resets.py (reset_checks) and application_evidence.py. Tie-breaks appended
-- after the reference ordering are listed in DETERMINISM.md.
-- name: has_requests
SELECT count(*) FROM app_protocol WHERE kind IN ('http_request','tls_setup')
-- name: empty_requests
CREATE OR REPLACE TABLE proxy_requests(id VARCHAR,point VARCHAR,last_frame BIGINT,
            first_frame BIGINT,response_frame BIGINT,flow VARCHAR,canon VARCHAR,start_seq BIGINT,kind VARCHAR,
            origin_ip VARCHAR,line VARCHAR,host VARCHAR,xff VARCHAR[],sni VARCHAR,ready BOOLEAN,reason VARCHAR,
            start DOUBLE,complete DOUBLE,response DOUBLE,protocol_stream BIGINT,socket_signature VARCHAR)
-- name: requests
CREATE OR REPLACE TABLE proxy_requests AS
    WITH app AS (SELECT *,md5(point||':'||frame::VARCHAR||':'||kind) AS id FROM app_protocol WHERE kind IN ('http_request','tls_setup')),
    anchors AS (SELECT point,flow,canon,arg_min(seq,frame) AS base FROM obs WHERE (flags&2)=2 GROUP BY point,flow,canon),
    components AS (SELECT a.id,arg_min(o.frame,(CASE WHEN h.base IS NOT NULL THEN (o.seq-h.base+4294967296)%4294967296
        ELSE -((e.seq-o.seq+4294967296)%4294967296) END,o.frame)) AS first_frame,
        min(o.corrected) AS earliest, bool_and(o.eligible AND (h.base IS NOT NULL OR (e.seq-o.seq+4294967296)%4294967296<2147483648)) AS usable
        FROM app a JOIN obs e ON e.point=a.point AND e.frame=a.frame
        LEFT JOIN anchors h ON h.point=e.point AND h.flow=e.flow AND h.canon=e.canon
        JOIN obs o ON o.point=a.point AND o.frame IN (SELECT unnest(from_json(a.components,'["BIGINT"]')))
        GROUP BY a.id),
    responses AS (SELECT a.id,arg_min(r.frame,(ro.corrected,r.frame)) FILTER(WHERE ro.corrected IS NOT NULL) AS completion_frame
        FROM app a JOIN app_protocol r ON r.point=a.point AND r.protocol_stream=a.protocol_stream
        AND ((a.kind='http_request' AND r.kind='http_response' AND try_cast(json_extract_string(r.metadata,'$.request_in') AS BIGINT)=a.frame)
        OR (a.kind='tls_setup' AND r.kind='tls_server' AND r.frame>a.frame
            AND NOT EXISTS(SELECT 1 FROM app n WHERE n.point=a.point AND n.protocol_stream=a.protocol_stream AND n.kind='tls_setup' AND n.frame>a.frame AND n.frame<r.frame)))
        JOIN obs ro ON ro.point=r.point AND ro.frame=r.frame GROUP BY a.id),
    response_start AS (SELECT r.id,arg_min(o.frame,(CASE WHEN h.base IS NOT NULL THEN (o.seq-h.base+4294967296)%4294967296
        ELSE -((e.seq-o.seq+4294967296)%4294967296) END,o.frame)) AS response_frame
        FROM responses r JOIN app a ON a.id=r.id JOIN app_protocol p ON p.point=a.point AND p.frame=r.completion_frame AND p.kind IN ('http_response','tls_server')
        JOIN obs e ON e.point=p.point AND e.frame=p.frame LEFT JOIN anchors h ON h.point=e.point AND h.flow=e.flow AND h.canon=e.canon
        JOIN obs o ON o.point=p.point AND o.frame IN (SELECT unnest(from_json(p.components,'["BIGINT"]'))) GROUP BY r.id)
    SELECT a.id,a.point,a.frame AS last_frame,c.first_frame,r.response_frame,e.flow,e.canon,s.seq AS start_seq,
        CASE WHEN a.kind='http_request' THEN 'http' ELSE 'tls_setup' END AS kind,
        coalesce(json_extract_string(a.metadata,'$.xff[0]'),json_extract_string(s.canon,'$[1]'),s.src) AS origin_ip,
        json_extract_string(a.metadata,'$.line') AS line,json_extract_string(a.metadata,'$.host') AS host,
        coalesce(from_json(json_extract(a.metadata,'$.xff'),'["VARCHAR"]'),[]::VARCHAR[]) AS xff,
        json_extract_string(a.metadata,'$.sni') AS sni,
        coalesce(try_cast(json_extract_string(a.metadata,'$.ready') AS BOOLEAN),false) AND c.usable AS ready,
        CASE WHEN NOT c.usable THEN 'Unmatchable components or out-of-order bytes without a SYN sequence anchor' ELSE json_extract_string(a.metadata,'$.reason') END AS reason,
        s.corrected AS start,e.corrected AS complete,ro.corrected AS response,
        a.protocol_stream,md5(s.tuple_key||':'||coalesce((SELECT raw_seq::VARCHAR FROM obs h WHERE h.point=s.point AND h.flow=s.flow AND h.canon=s.canon AND (h.flags&18)=2 ORDER BY h.frame LIMIT 1),s.raw_seq::VARCHAR)) AS socket_signature
    FROM app a JOIN obs e ON e.point=a.point AND e.frame=a.frame
    JOIN components c ON c.id=a.id JOIN obs s ON s.point=a.point AND s.frame=c.first_frame
    LEFT JOIN response_start r ON r.id=a.id LEFT JOIN obs ro ON ro.point=a.point AND ro.frame=r.response_frame
-- name: http_pdus
CREATE OR REPLACE TEMP TABLE http_pdus AS
        WITH anchors AS (SELECT point,flow,canon,arg_min(seq,frame) AS base FROM obs WHERE (flags&2)=2 GROUP BY point,flow,canon),
        data AS (SELECT a.*,e.canon,arg_max((o.seq+o.length)%4294967296,
            (CASE WHEN h.base IS NOT NULL THEN (o.seq-h.base+4294967296)%4294967296 ELSE -((e.seq-o.seq+4294967296)%4294967296) END,(o.seq+o.length)%4294967296)) AS end_seq,e.corrected AS completion,
            arg_min(o.frame,(CASE WHEN h.base IS NOT NULL THEN (o.seq-h.base+4294967296)%4294967296 ELSE -((e.seq-o.seq+4294967296)%4294967296) END,o.frame)) AS first_frame,
            bool_and(o.eligible AND coalesce(try_cast(json_extract_string(a.metadata,'$.ready') AS BOOLEAN),false)) AS ready
            FROM app_protocol a JOIN obs e ON e.point=a.point AND e.frame=a.frame
            LEFT JOIN anchors h ON h.point=e.point AND h.flow=e.flow AND h.canon=e.canon
            JOIN obs o ON o.point=a.point AND o.frame IN (SELECT unnest(from_json(a.components,'["BIGINT"]')))
            WHERE a.kind='http_request' OR (a.kind='http_response' AND try_cast(json_extract_string(a.metadata,'$.code') AS INTEGER)>=200)
            GROUP BY ALL), ordered AS (SELECT d.*,s.seq AS start_seq,s.corrected AS first_time,
            row_number() OVER w AS ordinal,lag(end_seq) OVER w AS previous_end
            FROM data d JOIN obs s ON s.point=d.point AND s.frame=d.first_frame
            WINDOW w AS (PARTITION BY d.point,d.protocol_stream,d.kind ORDER BY d.frame))
        SELECT *,ready AND CASE WHEN ordinal=1 THEN EXISTS(SELECT 1 FROM obs h WHERE h.point=ordered.point
            AND h.canon=ordered.canon AND (h.flags&2)=2 AND (h.seq+1)%4294967296=start_seq)
            ELSE previous_end%4294967296=start_seq END AS contiguous FROM ordered
-- name: clear_http_responses
UPDATE proxy_requests SET response=NULL,response_frame=NULL WHERE kind='http'
-- name: http_responses
UPDATE proxy_requests SET response=p.first_time,response_frame=p.first_frame
        FROM http_pdus q JOIN http_pdus p ON q.point=p.point AND q.protocol_stream=p.protocol_stream AND q.ordinal=p.ordinal
        WHERE proxy_requests.point=q.point AND proxy_requests.last_frame=q.frame AND proxy_requests.kind='http'
        AND q.kind='http_request' AND p.kind='http_response' AND p.first_time>=q.completion
        AND NOT EXISTS(SELECT 1 FROM http_pdus x WHERE x.point=q.point AND x.protocol_stream=q.protocol_stream AND NOT x.contiguous)
        AND (SELECT count(*) FROM http_pdus x WHERE x.point=q.point AND x.protocol_stream=q.protocol_stream AND x.kind='http_response')
            <=(SELECT count(*) FROM http_pdus x WHERE x.point=q.point AND x.protocol_stream=q.protocol_stream AND x.kind='http_request')
-- name: ordered_requests
SELECT * FROM proxy_requests ORDER BY start,point,last_frame,id
-- name: all_requests
SELECT * FROM proxy_requests
-- name: pairs_table
CREATE OR REPLACE TABLE proxy_pairs(device VARCHAR,client_id VARCHAR,server_id VARCHAR,status VARCHAR,metadata VARCHAR)
-- name: pairs_insert
INSERT INTO proxy_pairs VALUES (?,?,?,?,?)
-- name: pair_metadata
SELECT metadata FROM proxy_pairs
-- name: matched_metadata
SELECT metadata FROM proxy_pairs WHERE status='matched'
-- name: update_pair
UPDATE proxy_pairs SET metadata=? WHERE device=? AND client_id IS NOT DISTINCT FROM ? AND server_id IS NOT DISTINCT FROM ?
-- name: request_schema
[{"id":"VARCHAR","flow":"VARCHAR","socket_signature":"VARCHAR","origin_ip":"VARCHAR","line":"VARCHAR","host":"VARCHAR","xff":"VARCHAR[]","sni":"VARCHAR","start":"DOUBLE","complete":"DOUBLE","ready":"BOOLEAN","kind":"VARCHAR"}]
-- name: pair_edges
WITH clients AS (SELECT unnest(from_json(?,?)) r),servers AS (SELECT unnest(from_json(?,?)) r),
                possible AS (SELECT c.r.id AS c,s.r.id AS s,
                    coalesce(c.r.line=s.r.line AND c.r.host=s.r.host AND c.r.line IS NOT NULL AND c.r.host IS NOT NULL,false) AS identity,
                    coalesce(list_contains(s.r.xff,c.r.origin_ip),false) AS xff,
                    coalesce(c.r.sni=s.r.sni AND c.r.sni IS NOT NULL,false) AS sni,
                    abs(s.r.start-c.r.start) AS distance
                FROM clients c JOIN servers s ON c.r.kind=s.r.kind AND c.r.flow<>s.r.flow
                AND s.r.start BETWEEN c.r.start-? AND c.r.start+?+?
                WHERE c.r.ready AND s.r.ready
                    AND (c.r.socket_signature IS NULL OR s.r.socket_signature IS NULL OR c.r.socket_signature<>s.r.socket_signature)
                    AND NOT(coalesce(c.r.line<>s.r.line OR c.r.host<>s.r.host,false)
                        AND c.r.line IS NOT NULL AND s.r.line IS NOT NULL AND c.r.host IS NOT NULL AND s.r.host IS NOT NULL)
                    AND (coalesce(len(s.r.xff),0)=0 OR c.r.origin_ip IS NULL OR list_contains(s.r.xff,c.r.origin_ip)))
                SELECT * FROM possible WHERE identity OR xff OR sni LIMIT 50001
-- name: frame_lookup
o.point||':'||o.frame::VARCHAR IN (SELECT unnest(?::VARCHAR[]))
-- name: opaque_edges
CREATE OR REPLACE TEMP TABLE proxy_opaque_edges(a VARCHAR,b VARCHAR)
-- name: opaque_edges_insert
INSERT INTO proxy_opaque_edges VALUES (?,?)
-- name: resets
SELECT * FROM obs WHERE proto='TCP' AND (flags&4)=4 ORDER BY corrected,point,frame LIMIT 501
-- name: degraded
SELECT count(*) FROM events e WHERE kind IN ('capture_miss','unknown') AND point_b IN (?,?) AND flow IN (?,?) AND ts BETWEEN ? AND ? AND NOT EXISTS(SELECT 1 FROM proxy_opaque_edges p WHERE (p.a=e.point_a AND p.b=e.point_b) OR (p.b=e.point_a AND p.a=e.point_b))
-- name: legs
SELECT point,flow,count(*) AS packets,count(*) FILTER(WHERE retrans OR fast_retrans) AS retransmissions,
        count(*) FILTER(WHERE (flags&4)=4) AS resets FROM obs WHERE proto='TCP' GROUP BY point,flow ORDER BY point,flow
-- name: loss_by_leg
SELECT flow,point_a,kind,count(*) FROM events WHERE kind IN ('recovered_loss','impactful_loss','unrecovered_loss') GROUP BY flow,point_a,kind
-- name: waterfall_requests
SELECT * FROM proxy_requests WHERE kind='http' ORDER BY start,point,last_frame,id LIMIT 20001
-- name: handshake_packets
SELECT * FROM obs WHERE flow=? AND ((flags&2)=2 OR (flags=16 AND length=0)) ORDER BY corrected,frame LIMIT 501
