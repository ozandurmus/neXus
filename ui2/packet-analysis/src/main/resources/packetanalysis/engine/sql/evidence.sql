-- Verbatim from the reference evidence.py.
-- name: flow_filter_metadata
SELECT flow,capture_id,tuple_key,json_extract(path_fields,'$.tunnel') AS tunnel,min(ts) AS start,max(ts) AS "end"
        FROM obs GROUP BY flow,capture_id,tuple_key,tunnel
-- name: flow_filters
CREATE OR REPLACE TABLE flow_filters(flow VARCHAR,capture_id VARCHAR,display_filter VARCHAR)
-- name: flow_filters_insert
INSERT INTO flow_filters VALUES (?,?,?)
-- name: filter_join
LEFT JOIN (SELECT flow AS ff_flow,capture_id AS ff_capture,display_filter AS flow_filter FROM flow_filters) ff ON ff.ff_flow=o.flow AND ff.ff_capture=o.capture_id
-- name: evidence
SELECT ff.flow_filter,o.point,c.name AS file,o.capture_id,o.frame,
        'frame.number == ' || o.frame AS display_filter,o.ts AS observed_time,o.corrected AS corrected_time,
        o.vendor,o.path_fields,o.packet_key,o.flow,o.proto,o.src,o.dst,o.sport,o.dport,o.ipid,o.raw_seq AS seq,o.raw_ack AS ack,o.seq AS canonical_seq,o.ack AS canonical_ack,o.translation_reason,o.flags,o.length,
        o.dns_id,o.icmp_id,o.icmp_seq,o.icmp_type
        FROM obs o JOIN captures c ON c.id=o.capture_id {filter_join} WHERE {predicate}
        ORDER BY o.corrected NULLS LAST,o.point,o.frame LIMIT {limit}
-- name: has_byte_flows
SELECT 1 FROM byte_flows LIMIT 1
-- name: range_peers
SELECT point,source_frame,list(DISTINCT struct_pack(start_seq:=lo,end_seq:=hi,bytes:=range_length) ORDER BY struct_pack(start_seq:=lo,end_seq:=hi,bytes:=range_length)) AS ranges
            FROM byte_obs WHERE is_atom AND eligible AND packet_key IN (
                SELECT packet_key FROM byte_obs WHERE is_atom AND eligible AND source_packet_key IN (SELECT unnest(?::VARCHAR[])))
            GROUP BY point,source_frame ORDER BY point,source_frame
