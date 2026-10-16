-- Verbatim from the reference analysis/report.py.
-- name: preserve_order
SET preserve_insertion_order=false
-- name: coverage
SELECT point,min(corrected) AS start,max(corrected) AS "end",count(*) AS packets FROM obs WHERE point IN (SELECT unnest(?::VARCHAR[])) GROUP BY point ORDER BY point
-- name: events
CREATE OR REPLACE TABLE events (
            id VARCHAR, point_a VARCHAR,point_b VARCHAR,direction VARCHAR,kind VARCHAR,reason VARCHAR,
            packet_key VARCHAR,flow VARCHAR,ts DOUBLE,recovery_ms DOUBLE,recovery_key VARCHAR,
            support_key VARCHAR,impact_ms DOUBLE,is_data BOOLEAN,missing_bytes BIGINT,byte_ranges VARCHAR)
-- name: inventories
SELECT id,inventory FROM captures
-- name: order_scores
SELECT point,median(ttl) AS median_ttl,median(corrected) AS median_time,count(*) AS samples
        FROM obs o WHERE direction='forward' AND eligible AND EXISTS(
            SELECT 1 FROM obs b WHERE b.packet_key=o.packet_key AND b.point<>o.point AND b.eligible)
        GROUP BY point ORDER BY median_ttl DESC,median_time,point
-- name: analysis_scope
SELECT * FROM analysis_scope ORDER BY point
-- name: flow_count
SELECT count(*) FROM flow_summary
