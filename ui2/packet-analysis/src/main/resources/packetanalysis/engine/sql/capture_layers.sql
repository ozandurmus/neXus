-- Verbatim from the reference capture_layers.py (apply_layers, overlay_layers, tunnel_report).
-- name: layer_candidates
SELECT count(*) FROM obs WHERE point=? AND (unsupported LIKE 'Tunnel encapsulation%' OR unsupported LIKE 'Fragmented packet%')
-- name: layer_cache
CREATE TABLE IF NOT EXISTS layer_cache(point VARCHAR PRIMARY KEY,identity VARCHAR,reason VARCHAR)
-- name: layer_packets
CREATE TABLE IF NOT EXISTS layer_packets(point VARCHAR,layer VARCHAR,frame BIGINT,packet VARCHAR)
-- name: capture_row
SELECT path,identity,inventory FROM captures WHERE id=?
-- name: cache_get
SELECT identity,reason FROM layer_cache WHERE point=?
-- name: delete_packets
DELETE FROM layer_packets WHERE point=?
-- name: layer_frames
CREATE OR REPLACE TEMP TABLE layer_frames AS SELECT row_number() OVER(ORDER BY frame) AS local_frame,frame,ts FROM obs WHERE point=?
-- name: frames_count
SELECT count(*) FROM layer_frames
-- name: packets_insert
INSERT INTO layer_packets SELECT ?,r.layer,f.frame,to_json(r.packet)
                    FROM (SELECT unnest(from_json(?,?)) r) JOIN layer_frames f ON r.local_frame=f.local_frame
                    WHERE abs(r.packet.ts-f.ts)<0.000002
-- name: cache_insert
INSERT OR REPLACE INTO layer_cache VALUES (?,?,?)
-- name: layer_unavailable
UPDATE obs SET unsupported=? WHERE point=? AND (unsupported LIKE 'Tunnel encapsulation%' OR unsupported LIKE 'Fragmented packet%')
-- name: layer_batches
CREATE OR REPLACE TEMP TABLE layer_batches AS SELECT frame,
        (row_number() OVER(ORDER BY frame)-1)//? AS batch FROM
        (SELECT DISTINCT frame FROM layer_packets WHERE point=?)
-- name: max_batch
SELECT max(batch) FROM layer_batches
-- name: batch_frames
CREATE OR REPLACE TEMP TABLE layer_batch_frames AS SELECT frame FROM layer_batches WHERE batch=?
-- name: chosen_layer
CREATE OR REPLACE TEMP TABLE chosen_layer AS SELECT frame,from_json(packet,?) AS p
            FROM layer_packets WHERE point=? AND frame IN (SELECT frame FROM layer_batch_frames)
            AND (layer='plain' OR layer=? OR (? IS NULL AND layer='inner'
            AND json_extract_string(json_extract_string(packet,'$.path_fields'),'$.tunnel.kind')='erspan'))
-- name: layer_overlay
CREATE OR REPLACE TEMP TABLE layer_overlay AS SELECT o.* REPLACE ({replacements}) FROM obs o JOIN layer_batch_frames f USING(frame) LEFT JOIN chosen_layer c USING(frame) WHERE o.point=?
-- name: delete_batch_obs
DELETE FROM obs WHERE point=? AND frame IN (SELECT frame FROM layer_batch_frames)
-- name: insert_overlay
INSERT INTO obs SELECT * FROM layer_overlay
-- name: drop_overlay
DROP TABLE layer_overlay
-- name: drop_chosen
DROP TABLE chosen_layer
-- name: tunnel_unselected
UPDATE obs SET unsupported='Tunnel encapsulation: choose inner or outer; incomplete/nested layers stay unknown' WHERE point=? AND unsupported LIKE 'Tunnel encapsulation%'
-- name: tunnel_report
SELECT point,count(*) FILTER(WHERE json_exists(path_fields,'$.tunnel')) AS selected,
        count(*) FILTER(WHERE unsupported LIKE 'Tunnel encapsulation%') AS unselected,
        min(json_extract_string(path_fields,'$.tunnel.layer')) AS layer,
        count(*) FILTER(WHERE json_extract_string(path_fields,'$.tunnel.layer')='inner'
        AND try_cast(json_extract_string(path_fields,'$.tunnel.truncated') AS BOOLEAN)) AS truncated_inner
        FROM obs GROUP BY point HAVING selected+unselected>0 ORDER BY point
