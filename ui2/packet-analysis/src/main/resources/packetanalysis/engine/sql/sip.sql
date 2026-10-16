-- Verbatim from the reference sip.py (build_sip). Tie-breaks: DETERMINISM.md.
-- name: sip_messages
CREATE OR REPLACE TABLE sip_messages AS SELECT o.*,d."sip.Call-ID" AS call_id,
        d."sip.CSeq.seq" AS cseq,d."sip.CSeq.method" AS method,coalesce(d."sip.Status-Code",'request') AS status,
        d."sdp.connection_info.address" AS address,try_cast(d."sdp.media.port" AS INTEGER) AS port,
        d."sdp.media_attr" AS attributes,d."sdp.media.media" AS media,d."sip.Contact" AS contact,d."sip.Via" AS via,
        o.eligible AND o.caplen=o.wirelen AND c.reason IS NULL AND NOT contains(d."sip.CSeq.seq",',') AS complete
        FROM obs o JOIN udp_decoded d USING(point,frame) JOIN udp_decode_cache c USING(point)
        WHERE d."sip.Call-ID" IS NOT NULL
-- name: any_message
SELECT 1 FROM sip_messages LIMIT 1
-- name: drop_call_packets
DROP TABLE IF EXISTS rtp_call_packets
-- name: messages
WITH candidates AS(SELECT x.frame,y.frame AS frame_b,y.corrected AS time_b,
            y.address AS address_b,y.port AS port_b,y.contact AS contact_b,y.via AS via_b,
            count(*) OVER(PARTITION BY y.frame) AS owners
            FROM sip_messages x JOIN sip_messages y ON x.flow=y.flow AND x.call_id=y.call_id AND x.cseq=y.cseq
            AND x.method=y.method AND x.status=y.status AND x.direction=y.direction
            WHERE x.point=? AND y.point=? AND x.direction=? AND x.complete AND y.complete
            AND abs(y.corrected-x.corrected)<=?), pairs AS(SELECT frame,count(*) AS candidates,bool_or(owners<>1) AS ambiguous,
            min(frame_b) AS frame_b,min(address_b) AS address_b,min(port_b) AS port_b,min(contact_b) AS contact_b,min(via_b) AS via_b
            FROM candidates GROUP BY frame)
            SELECT x.call_id,x.flow,x.direction,x.corrected AS time,x.frame,x.address,x.port,x.contact,x.via,
            p.* EXCLUDE(frame) FROM sip_messages x LEFT JOIN pairs p USING(frame) WHERE x.point=? AND x.direction=? AND (? IS NULL OR x.corrected>=?) AND (? IS NULL OR x.corrected<=?) ORDER BY x.frame
-- name: sip_endpoints
CREATE OR REPLACE TEMP TABLE sip_endpoints AS SELECT call_id,flow AS signaling_flow,direction,address,port,corrected AS time,
        attributes,point,frame FROM sip_messages WHERE complete AND media='audio' AND port>0 AND address IS NOT NULL AND NOT contains(address,',')
        AND (direction='forward' AND point=? OR direction='reverse' AND point=?)
        QUALIFY row_number() OVER(PARTITION BY flow,call_id,direction ORDER BY corrected,frame)=1
-- name: rtp_call_packets
CREATE OR REPLACE TABLE rtp_call_packets AS WITH c AS(SELECT r.*,s.call_id,s.signaling_flow,
        count(*) OVER(PARTITION BY r.point,r.frame) AS owners FROM rtp_input r JOIN sip_endpoints s
        ON r.direction=s.direction AND json_extract_string(r.canon,'$[1]')=s.address AND try_cast(json_extract_string(r.canon,'$[2]') AS INTEGER)=s.port
        AND r.corrected>=s.time AND r.corrected<=s.time+300)
        SELECT * FROM c WHERE owners=1
-- name: media
SELECT DISTINCT src,sport,flow,min(frame) AS frame FROM rtp_call_packets WHERE call_id=? AND signaling_flow=? AND direction=? AND point=? GROUP BY ALL
-- name: calls
SELECT call_id,signaling_flow FROM sip_endpoints GROUP BY ALL HAVING count(DISTINCT direction)=2
        AND bool_and(NOT coalesce(attributes LIKE '%sendonly%' OR attributes LIKE '%recvonly%' OR attributes LIKE '%inactive%',false)) ORDER BY call_id,signaling_flow
-- name: observed
SELECT point,direction,flow,min(corrected) AS time,min(frame) AS frame FROM rtp_call_packets
            WHERE call_id=? AND signaling_flow=? GROUP BY ALL
