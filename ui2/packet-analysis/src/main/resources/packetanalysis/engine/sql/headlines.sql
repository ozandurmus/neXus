-- Verbatim from the reference headlines.py ({LOSS_TYPES} is the module constant).
-- name: LOSS_TYPES
('recovered_loss','impactful_loss','unrecovered_loss','confirmed_device_drop')
-- name: first_loss
SELECT min(ts) FROM events WHERE point_a=? AND point_b=? AND direction=?
                AND is_data AND kind IN {LOSS_TYPES}
-- name: loss_counts
SELECT count(*),count(*) FILTER(WHERE kind='recovered_loss'),
                    count(*) FILTER(WHERE greatest(impact_ms,recovery_ms)>=?),
                    max(greatest(impact_ms,recovery_ms)) FILTER(WHERE greatest(impact_ms,recovery_ms)>=?)
                    FROM events WHERE point_a=? AND point_b=? AND direction=? AND is_data AND kind IN {LOSS_TYPES}
                    AND ts>=? AND (? IS NULL OR ts<=?)
-- name: denominator
SELECT count(*) FROM obs WHERE point=? AND direction=? AND eligible
                    AND length>0 AND corrected>=? AND (? IS NULL OR corrected<=?)
-- name: previous
SELECT count(*) FROM events WHERE direction=? AND point_a IN (SELECT unnest(?::VARCHAR[]))
                            AND is_data AND kind IN {LOSS_TYPES} AND ts>=? AND (? IS NULL OR ts<=?)
