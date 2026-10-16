-- Verbatim from the reference integrity/payloads.py link_modified_payloads (it rewrites packet keys before
-- matching, so the core engine owns it; the payload findings stay with the integrity lane).
-- name: modified_payload_pairs
CREATE OR REPLACE TEMP TABLE modified_payload_pairs(point_a VARCHAR,point_b VARCHAR,direction VARCHAR,frame_a BIGINT,frame_b BIGINT)
-- name: modified_headers
CREATE OR REPLACE TEMP TABLE modified_headers AS SELECT point,canon,seq,ack,flags,length,direction,frame,corrected,
        json_extract_string(path_fields,'$.payload_complete') AS complete,json_extract_string(path_fields,'$.payload_sha256') AS digest
        FROM obs WHERE proto='TCP' AND length>0 AND eligible AND translation_reason IS NULL
-- name: modified_pairs_insert
INSERT INTO modified_payload_pairs WITH pairs AS (
            SELECT a.frame AS frame_a,b.frame AS frame_b,a.complete AS complete_a,b.complete AS complete_b,
                a.digest AS digest_a,b.digest AS digest_b
            FROM (SELECT * FROM modified_headers WHERE point=? AND direction=?) a
            JOIN (SELECT * FROM modified_headers WHERE point=? AND direction=?) b
            ON a.canon=b.canon AND a.seq=b.seq AND a.ack=b.ack AND a.flags=b.flags AND a.length=b.length
            WHERE abs(a.corrected-b.corrected)<=?
            QUALIFY count(*) OVER(PARTITION BY a.frame)=1 AND count(*) OVER(PARTITION BY b.frame)=1)
            SELECT ?,?,?,frame_a,frame_b FROM pairs WHERE complete_a='true' AND complete_b='true' AND digest_a<>digest_b
-- name: drop_modified_headers
DROP TABLE modified_headers
-- name: changed_edges
SELECT DISTINCT point_a,point_b FROM modified_payload_pairs
-- name: relink
UPDATE obs SET packet_key=paired.before_key FROM (
            SELECT a.packet_key AS before_key,b.packet_key AS after_key FROM modified_payload_pairs m
            JOIN obs a ON a.point=m.point_a AND a.frame=m.frame_a
            JOIN obs b ON b.point=m.point_b AND b.frame=m.frame_b WHERE m.point_a=? AND m.point_b=?) paired
            WHERE obs.packet_key=paired.after_key
