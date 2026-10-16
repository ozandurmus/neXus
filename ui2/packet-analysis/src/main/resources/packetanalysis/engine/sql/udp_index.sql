-- Verbatim from the reference udp_index.py: table shapes and the "decoding needed" guard. The tshark
-- decoding pass is the UDP/SIP lane; here udp_decoded stays empty.
-- name: udp_decode_cache
CREATE TABLE IF NOT EXISTS udp_decode_cache(point VARCHAR PRIMARY KEY,identity VARCHAR,reason VARCHAR)
-- name: udp_decoded
CREATE TABLE IF NOT EXISTS udp_decoded(point VARCHAR,frame BIGINT,{schema})
-- name: describe_udp_decoded
DESCRIBE udp_decoded
-- name: add_field
ALTER TABLE udp_decoded ADD COLUMN "{field}" VARCHAR
-- name: needed
SELECT 1 FROM obs WHERE proto='UDP' AND dns_id IS NULL OR proto='TCP' AND (sport=5060 OR dport=5060 OR json_extract_string(path_fields,'$.sip_probe')='1') LIMIT 1
-- name: clear_decoded
DELETE FROM udp_decoded
-- name: clear_cache
DELETE FROM udp_decode_cache
-- The tshark decode pass (UDP/RTP/SIP lane), verbatim from the reference index_udp / protocol_evidence.
-- name: point_needed
SELECT 1 FROM obs WHERE point=? AND (proto='UDP' AND dns_id IS NULL OR proto='TCP' AND (sport=5060 OR dport=5060 OR json_extract_string(path_fields,'$.sip_probe')='1')) LIMIT 1
-- name: delete_point
DELETE FROM udp_decoded WHERE point=?
-- name: capture
SELECT path,identity,inventory FROM captures WHERE id=?
-- name: cached
SELECT identity,reason FROM udp_decode_cache WHERE point=?
-- name: tunnel_or_fragment
SELECT 1 FROM obs WHERE point=? AND (json_exists(path_fields,'$.tunnel') OR json_exists(path_fields,'$.fragment')) LIMIT 1
-- name: point_count
SELECT count(*) FROM obs WHERE point=?
-- name: capture_tcp_sip
SELECT 1 FROM obs WHERE capture_id=? AND proto='TCP' AND (sport=5060 OR dport=5060 OR json_extract_string(path_fields,'$.sip_probe')='1') LIMIT 1
-- name: frame_map
CREATE OR REPLACE TEMP TABLE udp_frame_map AS SELECT *,row_number() OVER(ORDER BY frame) AS ordinal FROM obs WHERE point=?
-- name: udp_raw
CREATE OR REPLACE TEMP TABLE udp_raw({schema})
-- name: raw_insert
INSERT INTO udp_raw SELECT r.* FROM(SELECT unnest(from_json(?,?)) r)
-- name: raw_clear
DELETE FROM udp_raw
-- name: learned_ports
SELECT DISTINCT try_cast(port AS INTEGER) FROM udp_raw,unnest(string_split("sdp.media.port",',')) p(port) WHERE try_cast(port AS INTEGER) BETWEEN 1 AND 65535 LIMIT 257
-- name: header_table
CREATE OR REPLACE TEMP TABLE {header_table} AS SELECT * FROM udp_raw
-- name: header_select
CREATE OR REPLACE TEMP TABLE udp_raw AS SELECT r.* FROM {header_table} r
                        JOIN udp_frame_map o ON try_cast(r."frame.number" AS BIGINT)=o.frame
-- name: join_direct
r."frame.number"::BIGINT=o.frame
-- name: join_ordinal
r."frame.number"::BIGINT=o.ordinal
-- name: valid
coalesce(r."ip.src",r."ipv6.src")=o.src AND coalesce(r."ip.dst",r."ipv6.dst")=o.dst
                    AND try_cast(coalesce(r."udp.srcport",r."tcp.srcport") AS INTEGER)=o.sport AND try_cast(coalesce(r."udp.dstport",r."tcp.dstport") AS INTEGER)=o.dport
                    AND abs(try_cast(r."frame.time_epoch" AS DOUBLE)-o.ts)<0.000002
-- name: wrong
SELECT count(*) FROM udp_raw r LEFT JOIN udp_frame_map o ON {join} WHERE o.frame IS NULL OR NOT coalesce({valid},false)
-- name: insert_decoded
INSERT INTO udp_decoded(point,frame,{field_columns}) SELECT o.point,o.frame,r.* FROM udp_raw r JOIN udp_frame_map o ON {join}
-- name: cache_insert
INSERT OR REPLACE INTO udp_decode_cache VALUES (?,?,?)
-- name: decoded_lookup
SELECT d.* FROM udp_decoded d JOIN(SELECT unnest(from_json(?, '[{"point":"VARCHAR","frame":"BIGINT"}]')) r) s
        ON d.point=s.r.point AND d.frame=s.r.frame
-- name: frame_predicate
EXISTS(SELECT 1 FROM(SELECT unnest(from_json(?, '[{"point":"VARCHAR","frame":"BIGINT"}]')) r) s WHERE s.r.point=o.point AND s.r.frame=o.frame)
