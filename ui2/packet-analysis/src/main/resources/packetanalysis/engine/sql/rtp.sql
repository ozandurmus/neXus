-- Verbatim from the reference rtp.py (build_rtp). Tie-breaks: DETERMINISM.md.
-- name: rtp_sdp_rates
CREATE OR REPLACE TEMP TABLE rtp_sdp_rates AS SELECT point,
        try_cast("sdp.media.port" AS INTEGER) AS port,try_cast(regexp_extract("sdp.media_attr",'rtpmap:[0-9]+ [^/ ]+/([0-9]+)',1) AS INTEGER) AS rate,
        try_cast(regexp_extract("sdp.media_attr",'rtpmap:([0-9]+)',1) AS INTEGER) AS pt
        FROM udp_decoded WHERE "sdp.media.port" IS NOT NULL AND NOT contains("sdp.media.port",',')
-- name: rtp_rates
CREATE OR REPLACE TEMP TABLE rtp_rates AS SELECT point,port,pt,min(rate) AS rate FROM rtp_sdp_rates GROUP BY point,port,pt HAVING count(DISTINCT rate)=1
-- name: rtp_input
CREATE OR REPLACE TABLE rtp_input AS SELECT o.point,o.capture_id,o.frame,o.flow,o.canon,o.direction,o.ts,o.corrected,o.packet_key,o.src,o.dst,o.sport,o.dport,o.eligible,
        try_cast(d."rtp.ssrc" AS BIGINT) AS ssrc,try_cast(d."rtp.seq" AS BIGINT) AS seq,try_cast(d."rtp.timestamp" AS BIGINT) AS stamp,
        try_cast(d."rtp.p_type" AS INTEGER) AS pt,
        coalesce(?,r.rate,CASE WHEN d."rtp.p_type" IN ('0','8','13') THEN 8000 END) AS rate
        FROM obs o JOIN udp_decoded d USING(point,frame) LEFT JOIN LATERAL(SELECT min(rate) AS rate FROM rtp_rates r WHERE r.point=o.point AND r.pt=try_cast(d."rtp.p_type" AS INTEGER)
        AND r.port IN(o.sport,o.dport) HAVING count(DISTINCT rate)=1) r ON true WHERE d."rtp.version"='2' AND d."rtp.ssrc" IS NOT NULL AND (? IS NULL OR o.corrected>=?) AND (? IS NULL OR o.corrected<=?)
-- name: any_input
SELECT 1 FROM rtp_input LIMIT 1
-- name: drop_packets
DROP TABLE IF EXISTS rtp_packets
-- name: drop_streams
DROP TABLE IF EXISTS rtp_streams
-- name: rtp_order
CREATE OR REPLACE TEMP TABLE rtp_order AS SELECT *,row_number() OVER w AS rn,
        lag(seq) OVER w AS previous_seq,lag(stamp) OVER w AS previous_stamp,lag(ts) OVER w AS previous_ts
        FROM rtp_input WHERE eligible WINDOW w AS(PARTITION BY point,flow,direction,ssrc ORDER BY ts,frame)
-- name: rtp_spans
CREATE OR REPLACE TEMP TABLE rtp_spans AS WITH d AS(
        SELECT *,((seq-previous_seq+98304)%65536)-32768 AS ds,
        ((stamp-previous_stamp+6442450944)%4294967296)-2147483648 AS dt
        FROM rtp_order)
        SELECT *,first_value(seq) OVER w+coalesce(sum(ds) OVER w,0) AS extended_seq,
        abs((ts-previous_ts)*rate-dt)/16 AS innovation,
        floor((rn-1)/512)::BIGINT AS block,((rn-1)%512)+1 AS pos FROM d
        WINDOW w AS(PARTITION BY point,flow,direction,ssrc ORDER BY rn)
-- name: rtp_local
CREATE OR REPLACE TEMP TABLE rtp_local AS SELECT *,
        pow(0.9375,pos)*sum(coalesce(innovation,0)/pow(0.9375,pos)) OVER(PARTITION BY point,flow,direction,ssrc,block ORDER BY rn) AS local_j,
        row_number() OVER(PARTITION BY point,flow,direction,ssrc,extended_seq ORDER BY rn) AS occurrence,
        max(extended_seq) OVER(PARTITION BY point,flow,direction,ssrc ORDER BY rn ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prior_high
        FROM rtp_spans
-- name: rtp_blocks
CREATE OR REPLACE TEMP TABLE rtp_blocks AS SELECT point,flow,direction,ssrc,block,max(pos) AS n,
        arg_max(local_j,rn) AS tail FROM rtp_local GROUP BY ALL
-- name: rtp_block_state
CREATE OR REPLACE TEMP TABLE rtp_block_state AS WITH RECURSIVE state AS(
        SELECT *,0.0::DOUBLE AS previous_j,tail::DOUBLE AS final_j FROM rtp_blocks WHERE block=0
        UNION ALL SELECT b.*,s.final_j,b.tail+pow(0.9375,b.n)*s.final_j FROM rtp_blocks b JOIN state s
        ON b.point=s.point AND b.flow=s.flow AND b.direction=s.direction AND b.ssrc=s.ssrc AND b.block=s.block+1)
        SELECT * FROM state
-- name: rtp_packets
CREATE OR REPLACE TABLE rtp_packets AS SELECT l.*,
        CASE WHEN rate IS NOT NULL THEN (local_j+pow(0.9375,pos)*s.previous_j)/rate*1000 END AS jitter_ms
        FROM rtp_local l JOIN rtp_block_state s USING(point,flow,direction,ssrc,block)
-- name: rtp_streams
CREATE OR REPLACE TABLE rtp_streams AS SELECT point,flow,direction,ssrc,min(ts) AS start,max(ts) AS finish,
        count(*) AS packets,count(*)-count(DISTINCT extended_seq) AS duplicates,
        count(*) FILTER(WHERE occurrence=1 AND extended_seq<prior_high) AS reordered,
        max(extended_seq)-min(extended_seq)+1-count(DISTINCT extended_seq) AS gaps,
        count(*) FILTER(WHERE abs(ds)>3000) AS discontinuities,count(DISTINCT rate) AS clock_variants,min(rate) AS rate,
        CASE WHEN count(DISTINCT rate)=1 AND count(*) FILTER(WHERE abs(ds)>3000)=0 THEN quantile_cont(jitter_ms,.95) END AS jitter_p95_ms,
        CASE WHEN count(DISTINCT rate)=1 AND count(*) FILTER(WHERE abs(ds)>3000)=0 THEN max(jitter_ms) END AS jitter_max_ms,
        arg_min(frame,CASE WHEN ts IS NOT NULL THEN (ts,frame) END) AS first_frame,arg_max(frame,CASE WHEN ts IS NOT NULL THEN (ts,frame) END) AS last_frame FROM rtp_packets GROUP BY ALL
-- name: streams
SELECT * FROM rtp_streams ORDER BY point,flow,direction,ssrc LIMIT 200
-- name: losses
SELECT r.flow,r.ssrc,count(*) AS missing,min(e.ts) AS time,arg_min(e.packet_key,CASE WHEN e.ts IS NOT NULL THEN (e.ts,e.packet_key) END) AS packet_key
            FROM events e JOIN rtp_input r ON r.point=e.point_a AND r.packet_key=e.packet_key
            WHERE e.point_a=? AND e.point_b=? AND e.direction=? AND e.kind IN('unrecovered_loss','impactful_loss','recovered_loss','confirmed_device_drop')
            GROUP BY r.flow,r.ssrc ORDER BY r.flow,r.ssrc
-- name: rtp_transit
CREATE OR REPLACE TEMP TABLE rtp_transit AS SELECT x.flow,x.ssrc,x.seq,x.stamp,x.frame AS frame_a,y.frame AS frame_b,
            m.time_a AS time,(m.time_b-m.time_a)*1000 AS transit_ms,
            lag((m.time_b-m.time_a)*1000) OVER(PARTITION BY x.flow,x.ssrc ORDER BY m.time_a,x.frame,y.frame) AS previous_transit
            FROM observation_matches m JOIN rtp_input x ON x.point=m.point_a AND x.packet_key=m.key_a
            JOIN rtp_input y ON y.point=m.point_b AND y.packet_key=m.key_b AND x.ssrc=y.ssrc AND x.seq=y.seq AND x.stamp=y.stamp
            WHERE m.point_a=? AND m.point_b=? AND m.direction=?
-- name: signals
SELECT flow,ssrc,count(*) FILTER(WHERE abs(transit_ms-previous_transit)>?) AS crossings,
            quantile_cont(abs(transit_ms-previous_transit),.95) AS added_variation_p95_ms,
            min(time) FILTER(WHERE abs(transit_ms-previous_transit)>?) AS time,
            arg_max(frame_a,CASE WHEN abs(transit_ms-previous_transit) IS NOT NULL THEN (abs(transit_ms-previous_transit),frame_a,frame_b) END) AS frame_a,arg_max(frame_b,CASE WHEN abs(transit_ms-previous_transit) IS NOT NULL THEN (abs(transit_ms-previous_transit),frame_a,frame_b) END) AS frame_b
            FROM rtp_transit GROUP BY flow,ssrc HAVING count(*) FILTER(WHERE abs(transit_ms-previous_transit)>?)>=3 ORDER BY flow,ssrc
-- name: one_way
SELECT point,flow,count(DISTINCT direction) AS directions,min(direction) AS seen_direction,
        sum(packets) AS packets FROM rtp_streams GROUP BY point,flow HAVING count(DISTINCT direction)=1 ORDER BY point,flow
-- name: total
SELECT count(*) FROM rtp_streams
