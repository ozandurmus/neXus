-- Verbatim from the reference pilot.py flow_columns.
-- name: handshake_rtt
UPDATE flow_summary SET handshake_rtt_ms=h.ms FROM (
            SELECT s.flow,arg_min((sa.ts-s.ts)*1000,(s.ts,s.frame,sa.ts,sa.frame)) AS ms FROM obs s JOIN obs sa
            ON sa.point=s.point AND sa.stream=s.stream AND sa.ack=(s.seq+1)%4294967296
            AND (sa.flags&18)=18 AND sa.ts>s.ts AND sa.ts<s.ts+60
            WHERE s.point=? AND s.proto='TCP' AND (s.flags&18)=2 GROUP BY s.flow) h
            WHERE flow_summary.flow=h.flow
-- name: reset_flows
SELECT DISTINCT o.flow FROM obs o JOIN
            (VALUES {placeholders}) r(capture_id,frame) USING(capture_id,frame)
            WHERE (o.flags&4)>0
-- name: reset_origin
UPDATE flow_summary SET reset_origin=? WHERE flow=?
