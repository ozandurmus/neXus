-- Verbatim from the reference tls_analysis.py (tls_checks). The facts query has no ORDER BY in the reference
-- (row order decides the note order); the appended ORDER BY is listed in DETERMINISM.md.
-- name: has_setup
SELECT count(*) FROM proxy_requests WHERE kind='tls_setup'
-- name: facts
SELECT r.*,try_cast(json_extract_string(s.metadata,'$.version') AS INTEGER) AS version,
        json_extract(c.metadata,'$.chain') AS chain,c.frame AS cert_frame
        FROM proxy_requests r LEFT JOIN LATERAL (SELECT p.* FROM app_protocol p JOIN obs e ON e.point=p.point AND e.frame=p.frame WHERE p.point=r.point AND e.canon<>r.canon
            AND p.protocol_stream=r.protocol_stream AND p.kind='tls_server' AND p.frame>=r.last_frame
            AND NOT EXISTS(SELECT 1 FROM app_protocol n WHERE n.point=r.point AND n.protocol_stream=r.protocol_stream AND n.kind='tls_setup' AND n.frame>r.last_frame AND n.frame<p.frame)
            ORDER BY p.frame LIMIT 1) s ON true
        LEFT JOIN LATERAL (SELECT p.* FROM app_protocol p JOIN obs e ON e.point=p.point AND e.frame=p.frame WHERE p.point=r.point AND e.canon<>r.canon AND p.protocol_stream=r.protocol_stream
            AND p.kind='tls_certificate' AND p.frame>=s.frame AND e.eligible
            AND NOT EXISTS(SELECT 1 FROM app_protocol n WHERE n.point=r.point AND n.protocol_stream=r.protocol_stream AND n.kind='tls_setup' AND n.frame>r.last_frame AND n.frame<p.frame)
            ORDER BY p.frame LIMIT 1) c ON true WHERE r.kind='tls_setup' ORDER BY r.start NULLS LAST,r.point,r.last_frame,r.id
-- name: matched_pairs
SELECT device,client_id,server_id FROM proxy_pairs WHERE status='matched'
