-- Verbatim from the reference engine_export.py (export_tables), with appended tie-break keys (DETERMINISM.md).
-- name: cycle_points_exists
SELECT count(*) FROM duckdb_tables() WHERE table_name='cycle_points'
-- name: per_second
SELECT *,strftime(to_timestamp(start) AT TIME ZONE 'UTC','%Y-%m-%dT%H:%M:%S.%fZ') AS utc_time FROM cycle_series ORDER BY start,point,flow,port
-- name: flow_port
SELECT * FROM cycle_flow_detail ORDER BY max_ms DESC NULLS LAST,point,flow,port
-- name: periods
SELECT * FROM cycle_periods ORDER BY point,flow,period,port,reason
-- name: top_cycles
SELECT p.*,'frame.number == '||p.request_last_frame||' || frame.number == '||p.response_frame AS display_filter FROM cycle_details p
-- name: rx_tx_components
SELECT * FROM cycle_pairs ORDER BY time,hop,flow,frame_a,frame_b,cycle_id
-- name: capture_points
SELECT DISTINCT o.point,o.capture_id,c.name AS file FROM obs o JOIN captures c ON c.id=o.capture_id ORDER BY point,o.capture_id
-- name: flow_filters
SELECT * FROM flow_filters ORDER BY capture_id,flow
-- name: match_miss
SELECT * FROM adjacent_match_rates ORDER BY start,point_a,point_b,direction
-- name: captures
SELECT id,name FROM captures
