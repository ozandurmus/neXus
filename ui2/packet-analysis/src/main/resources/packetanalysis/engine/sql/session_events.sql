-- Verbatim from the reference session_events.py. arg_min/arg_max get struct tie-breaks (DETERMINISM.md).
-- name: sess_obs
CREATE OR REPLACE TEMP TABLE sess_obs AS SELECT frame,flow,direction,src,dst,sport,dport,
        coalesce(corrected,ts) AS t,flags,length,ipid,ttl,seq,ack,"window" AS win,retrans,zero_window,rtt
        FROM obs WHERE point=? AND proto='TCP' AND flow IS NOT NULL AND direction IN ('forward','reverse')
-- name: sess_flows
CREATE OR REPLACE TEMP TABLE sess_flows AS SELECT flow,min(t) AS first_t,max(t) AS last_t,
        min(t) FILTER(WHERE (flags&18)=2 AND direction='forward') AS syn_t,
        any_value(src) FILTER(WHERE direction='forward') AS client_ip,
        any_value(sport) FILTER(WHERE direction='forward') AS client_port,
        any_value(dst) FILTER(WHERE direction='forward') AS server_ip,
        any_value(dport) FILTER(WHERE direction='forward') AS server_port,
        coalesce(sum(length) FILTER(WHERE direction='forward'),0) AS c2s_bytes,
        coalesce(sum(length) FILTER(WHERE direction='reverse'),0) AS s2c_bytes,
        min(t) FILTER(WHERE (flags&5)>0) AS close_t
        FROM sess_obs GROUP BY flow
-- name: lifecycle
WITH firsts AS (SELECT o.flow,arg_min(o.direction,(o.t,o.frame)) AS closer,arg_min(o.flags,(o.t,o.frame)) AS close_flags,
            arg_min(o.frame,(o.t,o.frame)) AS close_frame FROM sess_obs o JOIN sess_flows f USING(flow)
            WHERE (o.flags&5)>0 AND o.t=f.close_t GROUP BY o.flow),
        final_record AS (SELECT o.flow,max(o.length) AS final_len FROM sess_obs o JOIN firsts c USING(flow)
            JOIN sess_flows f USING(flow) WHERE o.direction=c.closer AND o.length BETWEEN 1 AND 100
            AND o.t BETWEEN f.close_t-1 AND f.close_t AND (c.close_flags&1)>0 GROUP BY o.flow),
        followed_rst AS (SELECT o.flow,count(*) AS rsts FROM sess_obs o JOIN firsts c USING(flow)
            JOIN sess_flows f USING(flow) WHERE o.direction=c.closer AND (o.flags&4)>0
            AND o.t>f.close_t AND o.t<=f.close_t+5 AND (c.close_flags&1)>0 GROUP BY o.flow),
        reconnect AS (SELECT f.flow,min(n.syn_t) AS next_syn,arg_min(n.flow,(n.syn_t,n.flow)) AS next_flow
            FROM sess_flows f JOIN sess_flows n ON n.client_ip=f.client_ip AND n.server_ip=f.server_ip
            AND n.server_port=f.server_port AND n.flow<>f.flow AND n.syn_t>f.close_t
            AND n.syn_t<=f.close_t+? WHERE f.close_t IS NOT NULL GROUP BY f.flow)
        SELECT f.*,c.closer,c.close_flags,c.close_frame,coalesce(r.final_len,0) AS final_len,
            coalesce(fr.rsts,0) AS rsts_after_fin,n.next_syn,n.next_flow
        FROM sess_flows f LEFT JOIN firsts c USING(flow) LEFT JOIN final_record r USING(flow)
        LEFT JOIN followed_rst fr USING(flow) LEFT JOIN reconnect n USING(flow) ORDER BY f.first_t,f.flow
-- name: resets
SELECT r.frame,r.flow,r.direction,r.t,r.ttl,r.ipid,r.seq,
            (SELECT max(f.t) FROM sess_obs f WHERE f.flow=r.flow AND f.direction=r.direction
                AND (f.flags&1)>0 AND f.t<r.t) AS own_fin
        FROM sess_obs r WHERE (r.flags&4)>0 AND (r.flags&16)=0
        AND (SELECT arg_max(p.ack,(p.t,-p.frame)) FROM sess_obs p WHERE p.flow=r.flow AND p.direction<>r.direction
            AND p.t<r.t AND p.t>={trigger}) = r.seq
        ORDER BY r.direction,r.t,r.frame
-- name: storm_details
SELECT f.flow,(SELECT max(o.t) FROM sess_obs o WHERE o.flow=f.flow AND o.direction=?
            AND (o.flags&4)=0 AND o.t<?) AS side_last,
        (SELECT arg_max(o.frame,(o.t,-o.frame)) FROM sess_obs o WHERE o.flow=f.flow AND o.direction=? AND o.t<?) AS trigger_frame,
        (SELECT arg_max(o.flags,(o.t,-o.frame)) FROM sess_obs o WHERE o.flow=f.flow AND o.direction=? AND o.t<?) AS trigger_flags,
        (SELECT mode(o.ttl) FROM sess_obs o WHERE o.flow=f.flow AND o.direction=? AND (o.flags&4)=0) AS normal_ttl
        FROM (SELECT unnest(?::VARCHAR[]) AS flow) f
-- name: acks
WITH a AS (SELECT t,win,rtt,zero_window,
            (ack-lag(ack) OVER (ORDER BY t,frame)+4294967296)%4294967296 AS delta
            FROM sess_obs WHERE flow=? AND direction=? AND (flags&16)>0 AND (flags&4)=0)
        SELECT floor((t-?)/{bucket})::BIGINT AS bucket,
            sum(delta) FILTER(WHERE delta<1073741824) AS delivered,
            min(win) AS min_window,median(win) AS median_window,bool_or(zero_window) AS zero_window,
            quantile_cont(rtt,0.95) AS rtt_p95
        FROM a WHERE t>=? AND t<? GROUP BY bucket ORDER BY bucket
-- name: bucket_retrans
SELECT floor((t-?)/{bucket})::BIGINT,count(*) FILTER(WHERE retrans)
            FROM sess_obs WHERE flow=? AND direction=? AND length>0 AND t>=? AND t<? GROUP BY 1
-- name: flows_by_volume
SELECT * FROM sess_flows ORDER BY greatest(c2s_bytes,s2c_bytes) DESC,flow
-- name: drop_losses
SELECT count(*) FROM events WHERE flow=? AND ts>=? AND ts<?
        AND kind IN ('impactful_loss','recovered_loss','unrecovered_loss','confirmed_device_drop')
