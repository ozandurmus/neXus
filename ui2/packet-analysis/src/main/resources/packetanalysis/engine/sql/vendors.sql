-- Verbatim from the reference vendors.py (point_selector, prepare_f5_connections table). F5 / Check Point
-- connection decoding beyond the selector is a vendor lane.
-- name: checkpoint_stage
 AND json_extract_string(vendor,'$.stage')=?
-- name: checkpoint_interface
 AND json_extract_string(vendor,'$."fw1.interface"')=?
-- name: f5_role
 AND EXISTS(SELECT 1 FROM f5_connections c WHERE c.capture_id=packets.capture_id AND c.stream=packets.stream AND c.role=?)
-- name: f5_connections
CREATE OR REPLACE TABLE f5_connections(capture_id VARCHAR,stream BIGINT,
        flowid VARCHAR,peerid VARCHAR,tmm VARCHAR,role VARCHAR,reason VARCHAR)
-- F5 connection decoding and report (vendor lane), verbatim from the reference vendors.py. Tie-breaks:
-- DETERMINISM.md.
-- name: f5_inventory
SELECT id,name,inventory FROM captures WHERE id IN (SELECT unnest(?::VARCHAR[]))
-- name: f5_tuples
SELECT DISTINCT capture_id,stream,src,dst,
        json_extract_string(vendor,'$."f5ethtrailer.flowid"') AS flowid,
        json_extract_string(vendor,'$."f5ethtrailer.peerid"') AS peerid,
        concat(json_extract_string(vendor,'$."f5ethtrailer.slot"'),':',json_extract_string(vendor,'$."f5ethtrailer.tmm"')) AS tmm
        FROM packets WHERE capture_id IN (SELECT unnest(?::VARCHAR[])) AND proto='TCP'
        AND json_extract_string(vendor,'$.adapter')='f5'
-- name: f5_insert
INSERT INTO f5_connections VALUES (?,?,?,?,?,?,?)
-- name: f5_report_connections
WITH clients AS (
        SELECT * FROM f5_connections WHERE role='client' ORDER BY capture_id,stream,peerid,flowid,tmm LIMIT 100)
        SELECT * FROM clients UNION SELECT p.* FROM f5_connections p JOIN clients c
        ON p.capture_id=c.capture_id AND p.tmm=c.tmm AND p.flowid=c.peerid AND p.peerid=c.flowid
        UNION (SELECT * FROM f5_connections WHERE role='unknown' ORDER BY capture_id,stream,flowid,peerid,tmm LIMIT 20)
        ORDER BY capture_id,stream,flowid,peerid,tmm,role,reason
-- name: f5_connection_count
SELECT count(*) FROM f5_connections
-- name: f5_request_packets
SELECT * FROM obs WHERE capture_id=? AND stream IN (?,?)
            AND json_extract_string(vendor,'$."http.request.method"') IS NOT NULL
            AND ((json_extract_string(vendor,'$."f5ethtrailer.flowid"')=? AND json_extract_string(vendor,'$."f5ethtrailer.peerid"')=?)
              OR (json_extract_string(vendor,'$."f5ethtrailer.flowid"')=? AND json_extract_string(vendor,'$."f5ethtrailer.peerid"')=?))
            ORDER BY ts,frame,point LIMIT 2001
-- name: f5_min_flow
SELECT min(flow) FROM obs WHERE capture_id=? AND stream=?
-- name: f5_resets
SELECT * FROM obs WHERE json_extract_string(vendor,'$."f5ethtrailer.rstcausetxt"') IS NOT NULL ORDER BY ts,point,frame LIMIT 200
