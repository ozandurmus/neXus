-- Verbatim from the reference device_drops.py: the proof table shape. Proof extraction is the device-drop lane.
-- name: device_proofs
CREATE OR REPLACE TABLE device_proofs (
        proof_id VARCHAR,point VARCHAR,device VARCHAR,stage VARCHAR,adapter VARCHAR,
        capture_id VARCHAR,frame BIGINT,flow VARCHAR,packet_key VARCHAR,path_key VARCHAR,
        time DOUBLE,time_source VARCHAR,status VARCHAR,reason VARCHAR,event_id VARCHAR)
-- Verbatim from the reference device_drops.py prepare_proofs / upgrade_events / finding_context /
-- add_unplaced / device_report (time and event detector lane).
-- name: vendor_points
CREATE OR REPLACE TEMP TABLE vendor_points(point VARCHAR,device VARCHAR,stage VARCHAR,in_path BOOLEAN)
-- name: vendor_points_insert
INSERT INTO vendor_points VALUES (?,?,?,?)
-- name: inventory
SELECT inventory FROM captures WHERE id=?
-- name: inspection_candidates
CREATE OR REPLACE TEMP TABLE inspection_candidates AS
                SELECT o.*,EXISTS(SELECT 1 FROM obs b WHERE b.base_key=o.base_key
                    AND b.point IN (SELECT unnest(?::VARCHAR[])) AND NOT EXISTS(
                        SELECT 1 FROM obs i WHERE i.point=o.point AND i.packet_key=b.packet_key AND i.eligible)) AS unpaired_later,
                    (NOT ? OR o.tuple_key IN (SELECT unnest(?::VARCHAR[]))) AS translation_known
                FROM obs o WHERE point=? AND eligible AND NOT EXISTS (
                    SELECT 1 FROM obs b WHERE b.packet_key=o.packet_key AND b.point IN (SELECT unnest(?::VARCHAR[])))
-- name: inspection_proofs
INSERT INTO device_proofs
                SELECT point||':'||frame,point,?,'i → no I/o','checkpoint',capture_id,frame,flow,packet_key,packet_key,
                coalesce(corrected,ts),CASE WHEN corrected IS NULL THEN 'observed; inspection clock unaligned' ELSE 'corrected inspection clock' END,
                CASE WHEN supported THEN 'confirmed_device_drop' ELSE 'unknown' END,
                CASE WHEN unpaired_later THEN 'Unpaired later-stage appearance with the same identity; matching window insufficient'
                  WHEN NOT translation_known THEN 'NAT mapping not confirmed for this inspection tuple'
                  WHEN supported THEN 'Complete mapped inspection capture: i appearance has no later inspection appearance'
                  ELSE 'Inspection coverage incomplete, not attested or boundary unsupported; cannot confirm a device drop' END,NULL
                FROM (SELECT *,? AND ts+?<=? AND NOT unpaired_later AND translation_known AS supported FROM inspection_candidates)
-- name: paloalto_proofs
INSERT INTO device_proofs SELECT point||':'||frame,point,?,'drop','paloalto',capture_id,frame,
                flow,packet_key,NULL,coalesce(corrected,ts),CASE WHEN corrected IS NULL THEN 'observed; clock unaligned' ELSE 'corrected' END,
                'confirmed_device_drop','Positive Palo Alto drop-stage capture (user file tag)',NULL FROM obs WHERE point=?
-- name: associate_drop_files
UPDATE device_proofs p SET path_key=m.packet_key,flow=m.flow,
        time=CASE WHEN p.time_source='observed; clock unaligned' THEN coalesce(m.ts,p.time) ELSE p.time END,
        time_source=CASE WHEN p.time_source='observed; clock unaligned' AND m.ts IS NOT NULL THEN 'matched upstream timestamp; stage clock unaligned' ELSE p.time_source END
        FROM (SELECT p.proof_id,min(o.packet_key) AS packet_key,min(o.flow) AS flow,min(o.corrected) AS ts
          FROM device_proofs p JOIN obs d ON d.point=p.point AND d.frame=p.frame
          JOIN obs o ON o.base_key=d.base_key AND o.eligible
          JOIN vendor_points vp ON vp.point=o.point AND vp.device=p.device AND vp.in_path AND coalesce(vp.stage,'')<>'drop'
          WHERE p.adapter='paloalto' GROUP BY p.proof_id
          HAVING count(DISTINCT o.packet_key)=1 AND count(DISTINCT o.flow)=1) m WHERE m.proof_id=p.proof_id
-- name: window_trim
DELETE FROM device_proofs WHERE (? IS NOT NULL AND time<?) OR (? IS NOT NULL AND time>?)
-- name: proof_count
SELECT count(*) FROM device_proofs
-- name: device_assignments
CREATE OR REPLACE TEMP TABLE device_assignments AS
        SELECT e.id AS event_id,p.proof_id FROM events e JOIN device_proofs p ON p.path_key=e.packet_key
        WHERE e.point_a=? AND e.point_b=? AND e.direction=? AND p.device=?
          AND p.status='confirmed_device_drop' AND p.event_id IS NULL AND e.kind<>'capture_miss'
        QUALIFY row_number() OVER(PARTITION BY e.id ORDER BY p.frame,p.proof_id)=1
          AND row_number() OVER(PARTITION BY p.proof_id ORDER BY e.ts,e.id)=1
-- name: upgrade_events
UPDATE events e SET kind='confirmed_device_drop',reason=p.reason,support_key=p.packet_key
        FROM device_assignments x JOIN device_proofs p USING(proof_id) WHERE e.id=x.event_id
-- name: mark_proofs
UPDATE device_proofs SET event_id=x.event_id FROM device_assignments x WHERE device_proofs.proof_id=x.proof_id
-- name: finding_context
SELECT DISTINCT p.device,p.stage,p.time_source FROM device_proofs p JOIN events e ON p.event_id=e.id
                       WHERE e.point_a=? AND e.point_b=? AND e.direction=?
-- name: unplaced_events
INSERT INTO events SELECT 'device:'||p.proof_id,p.point,p.point,o.direction,
        'confirmed_device_drop',p.reason,coalesce(p.path_key,p.packet_key),p.flow,p.time,NULL,NULL,p.packet_key,NULL,
        o.length>0,o.length,NULL FROM device_proofs p JOIN obs o ON o.point=p.point AND o.frame=p.frame
        WHERE p.status='confirmed_device_drop' AND p.event_id IS NULL
-- name: unplaced_mark
UPDATE device_proofs SET event_id='device:'||proof_id WHERE status='confirmed_device_drop' AND event_id IS NULL
-- name: unplaced_groups
SELECT point_a,direction,count(*) AS count,sum(missing_bytes) AS missing_bytes,
        min(ts) AS first_seen,max(ts) AS last_seen FROM events WHERE point_a=point_b AND kind='confirmed_device_drop'
        GROUP BY point_a,direction ORDER BY point_a,direction
-- name: unplaced_frame
SELECT p.point,p.frame FROM device_proofs p JOIN events e ON p.event_id=e.id
                              WHERE e.point_a=? AND e.point_b=? AND e.direction=? ORDER BY e.ts,p.frame,p.proof_id LIMIT 1
-- name: sample
SELECT * FROM device_proofs ORDER BY time,proof_id LIMIT 200
-- name: grouped
SELECT device,stage,count(*) AS count FROM device_proofs
                         WHERE status='confirmed_device_drop' GROUP BY device,stage ORDER BY device,stage
