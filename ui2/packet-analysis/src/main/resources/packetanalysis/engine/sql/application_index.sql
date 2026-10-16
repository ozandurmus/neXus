-- Verbatim from the reference application_index.py: table shapes and the "indexing needed" guard. The
-- isolated tshark application pass (index_applications, apply_tcp_analysis).
-- name: app_protocol
CREATE TABLE IF NOT EXISTS app_protocol(point VARCHAR,capture_id VARCHAR,frame BIGINT,kind VARCHAR,components VARCHAR,protocol_stream BIGINT,metadata VARCHAR)
-- name: app_protocol_cache
CREATE TABLE IF NOT EXISTS app_protocol_cache(point VARCHAR PRIMARY KEY,identity VARCHAR,reason VARCHAR)
-- name: tcp_point_analysis
CREATE TABLE IF NOT EXISTS tcp_point_analysis(point VARCHAR,frame BIGINT,metadata VARCHAR)
-- name: tcp_state_raw
CREATE OR REPLACE TEMP TABLE tcp_state_raw(point VARCHAR,local_frame BIGINT,src VARCHAR,raw_seq BIGINT,tcp_len BIGINT,observed DOUBLE,metadata VARCHAR)
-- name: needed
SELECT count(*) FROM obs WHERE proto='TCP' AND (json_extract_string(path_fields,'$.tls_record_type') IN ('20','21','22','23','24') OR (NOT coalesce(json_exists(path_fields,'$.tls_record_type'),false) AND NOT coalesce(try_cast(json_extract_string(path_fields,'$.transport_index') AS INTEGER)=1,false)))
-- name: clear
DELETE FROM {table}
-- name: preferences
SELECT value FROM settings WHERE key=?
-- name: capture_row
SELECT path,identity,inventory FROM captures WHERE id=?
-- name: point_count
SELECT count(*) FROM obs WHERE point=?
-- The reference reads bit_xor (UBIGINT) as an int; the outer cast keeps its exact digits for the identity text.
-- name: identity_hash
SELECT n,x::VARCHAR FROM (SELECT count(*) AS n,bit_xor(hash(frame,tuple_key)) AS x FROM obs WHERE point=?)
-- name: cache_get
SELECT identity,reason FROM app_protocol_cache WHERE point=?
-- name: state_insert
INSERT INTO tcp_state_raw SELECT r.* FROM (SELECT unnest(from_json(?,?)) r)
-- name: state_schema
[{"point":"VARCHAR","local_frame":"BIGINT","src":"VARCHAR","raw_seq":"BIGINT","tcp_len":"BIGINT","observed":"DOUBLE","metadata":"VARCHAR"}]
-- name: delete_state
DELETE FROM tcp_state_raw WHERE point=?
-- name: delete_analysis
DELETE FROM tcp_point_analysis WHERE point=?
-- name: delete_app
DELETE FROM app_protocol WHERE point=?
-- name: tuples
SELECT DISTINCT proto,src,dst,sport,dport FROM obs WHERE point=? LIMIT 129
-- name: state_mapping
CREATE OR REPLACE TEMP TABLE tcp_state_mapping AS SELECT point,frame,src,ts,raw_seq,length,
                row_number() OVER(ORDER BY frame) AS local_frame FROM obs WHERE point=?
-- name: state_invalid
SELECT count(*) FROM tcp_state_raw s LEFT JOIN tcp_state_mapping m USING(point,local_frame)
                WHERE s.point=? AND (m.frame IS NULL OR s.src<>m.src OR s.raw_seq<>m.raw_seq OR s.tcp_len<>m.length OR abs(s.observed-m.ts)>1e-6)
-- name: analysis_insert
INSERT INTO tcp_point_analysis SELECT s.point,m.frame,
                json_merge_patch(s.metadata,json_object('rto_frame',reference.frame))
                FROM tcp_state_raw s JOIN tcp_state_mapping m USING(point,local_frame)
                LEFT JOIN tcp_state_mapping reference ON reference.point=s.point AND reference.local_frame=try_cast(json_extract_string(s.metadata,'$.rto_frame') AS BIGINT)
                WHERE s.point=?
-- name: mapped
SELECT * FROM (SELECT frame,src,ts,raw_seq,length,row_number() OVER(ORDER BY frame) AS local_frame
                FROM obs WHERE point=?) WHERE local_frame IN (SELECT unnest(?::BIGINT[]))
-- name: app_insert
INSERT INTO app_protocol SELECT r.* FROM (SELECT unnest(from_json(?,?)) r)
-- name: app_schema
[{"point":"VARCHAR","capture_id":"VARCHAR","frame":"BIGINT","kind":"VARCHAR","components":"VARCHAR","protocol_stream":"BIGINT","metadata":"VARCHAR"}]
-- name: cache_insert
INSERT OR REPLACE INTO app_protocol_cache VALUES (?,?,?)
-- name: point_tcp_state
CREATE OR REPLACE TEMP TABLE point_tcp_state AS SELECT * FROM tcp_point_analysis WHERE point=?
-- name: apply_obs
CREATE OR REPLACE TABLE obs AS SELECT * FROM obs WHERE point<>? UNION ALL SELECT o.* REPLACE({fields}) FROM (SELECT * FROM obs WHERE point=?) o LEFT JOIN point_tcp_state a ON o.point=a.point AND o.frame=a.frame
-- name: apply_flag
CASE WHEN a.frame IS NULL THEN false ELSE try_cast(json_extract_string(a.metadata,'$.{name}') AS BOOLEAN) END AS {name}
-- name: apply_rtt
try_cast(json_extract_string(a.metadata,'$.rtt') AS DOUBLE) AS rtt
-- name: apply_path_fields
CAST(json_merge_patch(json_merge_patch(o.path_fields,
        '{"duplicate_ack":false,"window_full":false,"zero_probe":false,"rto_seconds":null,"rto_frame":null,"bytes_in_flight":null}'),
        coalesce(a.metadata,'{}')) AS VARCHAR) AS path_fields
-- name: drop_point_tcp_state
DROP TABLE point_tcp_state
