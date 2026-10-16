-- Verbatim from the reference analysis/selection.py (frozen PacketBreaker 5b608e6).
-- name: path_tuples
CREATE OR REPLACE TEMP TABLE path_tuples AS SELECT DISTINCT least(tuple_key,reverse_tuple) AS tuple_key FROM obs
-- name: path_tuples_insert
INSERT INTO path_tuples VALUES (?)
-- name: capture_addresses
SELECT DISTINCT src FROM packets WHERE capture_id=? UNION SELECT DISTINCT dst FROM packets WHERE capture_id=?
-- name: selected_clients
CREATE OR REPLACE TEMP TABLE selected_clients(ip VARCHAR)
-- name: selected_clients_insert
INSERT INTO selected_clients VALUES (?)
-- name: host_scope_clause
 AND (least(tuple_key,reverse_tuple) IN (SELECT tuple_key FROM path_tuples) OR tuple_key IN (SELECT tuple_key FROM path_tuples) OR src IN(SELECT ip FROM selected_clients) OR dst IN(SELECT ip FROM selected_clients))
-- name: point_source_addresses
SELECT DISTINCT src FROM obs WHERE point=?
-- name: point_sources
CREATE OR REPLACE TEMP TABLE point_sources(src VARCHAR)
-- name: point_sources_insert
INSERT INTO point_sources VALUES (?)
-- name: point_sources_delete
DELETE FROM obs WHERE point=? AND (src IS NULL OR src NOT IN (SELECT src FROM point_sources))
-- name: paloalto_vendor
UPDATE obs SET vendor=? WHERE point=?
-- name: drop_matches_view
DROP VIEW IF EXISTS observation_matches
-- name: byte_flows
CREATE OR REPLACE TABLE byte_flows(flow VARCHAR,direction VARCHAR)
-- name: drop_obs
DROP TABLE IF EXISTS obs
-- name: create_obs
CREATE TABLE obs AS SELECT *, seq AS raw_seq, ack AS raw_ack, NULL::VARCHAR AS translation_reason, NULL::VARCHAR AS range_reason, NULL::VARCHAR AS point, NULL::VARCHAR AS canon FROM packets WHERE false
-- name: ready_captures
SELECT id FROM captures WHERE state='ready'
-- name: analysis_scope
CREATE OR REPLACE TABLE analysis_scope(point VARCHAR,input_frames BIGINT,retained_frames BIGINT,excluded_single_point_frames BIGINT,excluded_single_point_flows BIGINT)
-- name: capture_inventory
SELECT inventory FROM captures WHERE id=?
-- name: input_frames
SELECT count(*) FROM packets WHERE {clause}
-- name: input_flows
SELECT count(DISTINCT least(tuple_key,reverse_tuple)) FROM packets WHERE {clause}
-- name: analysis_scope_insert
INSERT INTO analysis_scope VALUES (?,?,?,?,?)
-- name: obs_insert
INSERT INTO obs SELECT *,seq,ack,NULL,NULL,?,tuple_key FROM packets WHERE {clause}
-- name: add_corrected
ALTER TABLE obs ADD COLUMN corrected DOUBLE
-- name: add_direction
ALTER TABLE obs ADD COLUMN direction VARCHAR
-- name: add_canon_reverse
ALTER TABLE obs ADD COLUMN canon_reverse VARCHAR
-- name: add_packet_key
ALTER TABLE obs ADD COLUMN packet_key VARCHAR
-- name: add_flow
ALTER TABLE obs ADD COLUMN flow VARCHAR
-- name: add_eligible
ALTER TABLE obs ADD COLUMN eligible BOOLEAN DEFAULT false
-- name: overlapping_selections
SELECT count(*) FROM (SELECT capture_id,frame FROM obs
        GROUP BY capture_id,frame HAVING count(*)>1)
-- name: distinct_canon
SELECT DISTINCT canon FROM obs
-- name: client_tuples
CREATE OR REPLACE TEMP TABLE client_tuples(canon VARCHAR)
-- name: client_tuples_insert
INSERT INTO client_tuples VALUES (?)
-- name: scoped_flows
CREATE OR REPLACE TEMP TABLE scoped_flows AS SELECT flow FROM obs GROUP BY flow
        HAVING count(DISTINCT point)>=2 OR bool_or(canon IN(SELECT canon FROM client_tuples))
-- name: index_scope
CREATE OR REPLACE TABLE index_scope AS SELECT point,count(*) AS input_frames,count(DISTINCT flow) AS input_flows,
        count(*) FILTER(WHERE flow IN(SELECT flow FROM scoped_flows) OR point IN(SELECT unnest(?::VARCHAR[]))) AS retained_frames,
        count(DISTINCT flow) FILTER(WHERE flow IN(SELECT flow FROM scoped_flows) OR point IN(SELECT unnest(?::VARCHAR[]))) AS retained_flows
        FROM obs GROUP BY point
-- name: index_scope_update
UPDATE index_scope SET input_frames=s.input_frames,input_flows=index_scope.input_flows+s.excluded_single_point_flows FROM analysis_scope s WHERE index_scope.point=s.point
-- name: restrict_obs
CREATE OR REPLACE TABLE obs AS SELECT * FROM obs WHERE flow IN(SELECT flow FROM scoped_flows) OR point IN(SELECT unnest(?::VARCHAR[]))
-- name: analysis_scope_update
UPDATE analysis_scope SET retained_frames=i.retained_frames,
        excluded_single_point_frames=analysis_scope.input_frames-i.retained_frames FROM index_scope i WHERE analysis_scope.point=i.point
