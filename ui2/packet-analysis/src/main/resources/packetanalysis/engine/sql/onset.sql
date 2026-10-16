-- Verbatim from the reference onset.py (add_onsets) and symptoms.py (label_retransmissions).
-- name: segment_buckets
SELECT * FROM segment_buckets WHERE segment=? ORDER BY bucket
-- name: first_event
SELECT packet_key,recovery_key,support_key FROM events WHERE point_a=? AND point_b=? AND direction=?
                    AND ts>=? AND ts<? AND kind IN {kinds} ORDER BY ts,id,packet_key LIMIT 1
-- name: max_pair
SELECT key_a,key_b FROM observation_matches WHERE point_a=? AND point_b=? AND direction=?
                    AND time_a>=? AND time_a<? ORDER BY time_b-time_a DESC,time_a,key_a,key_b LIMIT 1
-- name: retrans_losses
SELECT DISTINCT e.point_a,e.point_b FROM events e WHERE e.direction=?
            AND e.kind IN ('recovered_loss','impactful_loss','unrecovered_loss','handshake_blocked','confirmed_device_drop')
            AND e.ts<? AND e.ts+coalesce(greatest(e.recovery_ms,e.impact_ms)/1000,60)>=?
            AND e.flow IN (SELECT DISTINCT flow FROM obs WHERE point=? AND direction=? AND retrans
                AND corrected>=? AND corrected<?)
