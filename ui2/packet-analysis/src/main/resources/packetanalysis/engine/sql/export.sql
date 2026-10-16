-- Verbatim from the reference export.py (export_data).
-- name: timeseries_items
SELECT * FROM segment_buckets ORDER BY direction,segment,bucket
-- name: flow_filters
SELECT f.flow,f.capture_id,c.name AS file,f.display_filter
                FROM flow_filters f JOIN captures c ON c.id=f.capture_id ORDER BY f.flow,c.name
