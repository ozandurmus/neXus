-- Verbatim from the reference transport.py, retransmissions.py and waterfall.py (service_times).
-- Tie-breaks appended after the reference ordering are listed in DETERMINISM.md.
-- name: settings_get
SELECT value FROM settings WHERE key=?
-- name: settings_set
INSERT OR REPLACE INTO settings VALUES (?,?)
-- name: transport_points
CREATE OR REPLACE TEMP TABLE transport_points(point VARCHAR,kind VARCHAR,label VARCHAR)
-- name: transport_points_insert
INSERT INTO transport_points VALUES (?,?,?)
-- name: transport_tcp
CREATE OR REPLACE TABLE transport_tcp AS
        SELECT o.*,p.kind,p.label,
        try_cast(json_extract_string(path_fields,'$.window_scale') AS INTEGER) AS shift,
        coalesce(try_cast(json_extract_string(path_fields,'$.transport_index') AS INTEGER)=1,false) AS indexed,
        coalesce(try_cast(json_extract_string(path_fields,'$.window_full') AS BOOLEAN),false) AS window_full,
        coalesce(try_cast(json_extract_string(path_fields,'$.duplicate_ack') AS BOOLEAN),false) AS duplicate_ack,
        coalesce(try_cast(json_extract_string(path_fields,'$.zero_probe') AS BOOLEAN),false) AS zero_probe,
        try_cast(json_extract_string(path_fields,'$.rto_seconds') AS DOUBLE) AS rto_seconds,
        try_cast(json_extract_string(path_fields,'$.rto_frame') AS BIGINT) AS rto_frame,
        try_cast(json_extract_string(path_fields,'$.bytes_in_flight') AS BIGINT) AS dissected_flight,
        try_cast(json_extract_string(path_fields,'$.sack_left') AS BIGINT) AS sack_left,
        try_cast(json_extract_string(path_fields,'$.sack_right') AS BIGINT) AS sack_right,
        (SELECT count(DISTINCT point) FROM obs x WHERE x.capture_id=o.capture_id)=1
            OR EXISTS(SELECT 1 FROM app_protocol_cache a WHERE a.point=o.point AND a.reason IS NULL) AS trusted
        FROM obs o JOIN transport_points p USING(point) WHERE proto='TCP' AND flow IS NOT NULL
-- name: transport_syn
CREATE OR REPLACE TEMP TABLE transport_syn AS
        SELECT flow,point,direction,arg_min(seq,(ts,frame)) AS isn,arg_min(raw_seq,(ts,frame)) AS raw_isn,arg_min(shift,(ts,frame)) AS shift,min(ts) AS ts
        FROM transport_tcp WHERE (flags&2)>0 AND eligible AND caplen>=wirelen GROUP BY ALL
-- name: transport_handshake
CREATE OR REPLACE TABLE transport_handshake AS
        SELECT h.*,peer.isn AS peer_isn,
        peer.isn IS NOT NULL AND h.shift BETWEEN 0 AND 14 AND peer.shift BETWEEN 0 AND 14 AS scaled,
        peer.isn IS NOT NULL AND (h.shift IS NULL OR h.shift BETWEEN 0 AND 14) AND (peer.shift IS NULL OR peer.shift BETWEEN 0 AND 14) AND EXISTS(SELECT 1 FROM transport_tcp a
           WHERE a.flow=h.flow AND a.point=h.point AND (a.flags&18)=16
           AND a.ts>=greatest(h.ts,peer.ts) AND a.seq=(CASE WHEN a.direction=h.direction THEN h.isn ELSE peer.isn END+1)%4294967296
           AND a.ack=(CASE WHEN a.direction=h.direction THEN peer.isn ELSE h.isn END+1)%4294967296) AS handshake
        FROM transport_syn h LEFT JOIN transport_syn peer ON h.flow=peer.flow AND h.point=peer.point AND h.direction<>peer.direction
-- name: transport_acks
CREATE OR REPLACE TABLE transport_acks AS
        SELECT o.flow,o.point,CASE WHEN o.direction='forward' THEN 'reverse' ELSE 'forward' END AS direction,
        o.frame,o.ts,o.corrected,(o.ack-h.isn+4294967296)%4294967296 AS ack_end,o.rtt,
        CASE WHEN h.handshake THEN o.window*CASE WHEN (o.flags&2)>0 OR NOT h.scaled THEN 1 ELSE pow(2,peer.shift) END END AS receive_window,
        o.zero_window,o.duplicate_ack,o.sack_left,o.sack_right,
        (o.sack_left-h.raw_isn+4294967296)%4294967296 AS sack_start,
        (o.sack_right-h.raw_isn+4294967296)%4294967296 AS sack_end
        FROM transport_tcp o JOIN transport_handshake h ON o.flow=h.flow AND o.point=h.point AND o.direction<>h.direction
        LEFT JOIN transport_handshake peer ON peer.flow=h.flow AND peer.point=h.point AND peer.direction<>h.direction
        WHERE (o.flags&16)>0
-- name: transport_ack_first
CREATE OR REPLACE TEMP TABLE transport_ack_first AS
        SELECT flow,point,direction,ack_end,min(ts) AS ts,arg_min(frame,(ts,frame)) AS frame
        FROM transport_acks GROUP BY ALL
-- name: transport_data
CREATE OR REPLACE TEMP TABLE transport_data AS
        WITH d AS(SELECT o.*,h.handshake,h.isn,(o.seq-h.isn+4294967296)%4294967296 AS relative_seq
            FROM transport_tcp o LEFT JOIN transport_handshake h USING(flow,point,direction)
            WHERE o.length>0 AND (o.flags&2)=0), s AS(
            SELECT *,relative_seq+length AS seq_end,
            max(relative_seq+length) OVER(PARTITION BY flow,point,direction ORDER BY ts,frame) AS sent_end,
            (ts-lag(ts) OVER(PARTITION BY flow,point,direction ORDER BY ts,frame))*1000 AS gap_ms FROM d)
        SELECT s.*,w.receive_window,w.ack_end,w.frame AS window_frame,
        CASE WHEN s.trusted THEN s.dissected_flight END AS bytes_in_flight,
        greatest(0,s.sent_end-w.ack_end) AS unacknowledged_span,
        CASE WHEN NOT s.retrans AND NOT s.spurious AND a.ts>=s.ts AND a.ts<s.ts+60 THEN (a.ts-s.ts)*1000 END AS ack_delay_ms,
        a.frame AS ack_frame
        FROM s ASOF LEFT JOIN transport_acks w ON s.flow=w.flow AND s.point=w.point AND s.direction=w.direction AND s.ts>=w.ts
        ASOF LEFT JOIN transport_ack_first a ON s.flow=a.flow AND s.point=a.point AND s.direction=a.direction AND s.seq_end<=a.ack_end
-- name: transport_samples
CREATE OR REPLACE TABLE transport_samples AS
        SELECT d.* EXCLUDE(ack_delay_ms,ack_frame),
        CASE WHEN NOT d.retrans AND NOT d.spurious THEN least(d.ack_delay_ms,(s.ts-d.ts)*1000) END AS ack_delay_ms,
        CASE WHEN s.ts IS NOT NULL AND (d.ack_delay_ms IS NULL OR (s.ts-d.ts)*1000<d.ack_delay_ms) THEN s.frame ELSE d.ack_frame END AS ack_frame
        FROM transport_data d LEFT JOIN LATERAL(SELECT min(w.ts) AS ts,arg_min(w.frame,(w.ts,w.frame)) AS frame FROM transport_acks w
            WHERE w.flow=d.flow AND w.point=d.point AND w.direction=d.direction AND w.sack_start<=d.relative_seq AND w.sack_end>=d.seq_end
            AND w.ts>=d.ts AND w.ts<d.ts+60) s ON true
-- name: transport_window_trace
CREATE OR REPLACE TEMP TABLE transport_window_trace AS
        WITH w AS(SELECT *,lag(receive_window) OVER(PARTITION BY flow,point,direction ORDER BY ts,frame) AS prior_window FROM transport_acks)
        SELECT w.*,greatest(0,d.sent_end-w.ack_end) AS flight
        FROM w ASOF LEFT JOIN transport_data d ON w.flow=d.flow AND w.point=d.point AND w.direction=d.direction AND w.ts>=d.ts
-- name: transport_point_facts
CREATE OR REPLACE TABLE transport_point_facts AS
        WITH a AS(SELECT flow,point,direction,count(*) FILTER(WHERE receive_window=0) AS zero_windows,
            coalesce((max(ts) FILTER(WHERE receive_window=0)-min(ts) FILTER(WHERE receive_window=0))*1000,0) AS zero_span_ms,
            count(*) FILTER(WHERE receive_window<prior_window) AS window_shrinks,
            count(*) FILTER(WHERE receive_window=0 AND flight>0) AS blocked_windows FROM transport_window_trace GROUP BY ALL),
        d AS(SELECT flow,point,direction,min(kind) AS kind,min(label) AS label,bool_and(indexed) AS indexed,bool_and(trusted) AS trusted,
            bool_and(coalesce(handshake,false)) AND max(relative_seq)<2147483648 AS handshake,count(*) AS samples,
            count(*) FILTER(WHERE ack_delay_ms IS NOT NULL) AS ack_samples,quantile_cont(ack_delay_ms,.95) AS ack_delay_p95_ms,
            count(*) FILTER(WHERE window_full) AS window_full,count(*) FILTER(WHERE zero_probe) AS probes,
            count(*) FILTER(WHERE receive_window>greatest(length*2,bytes_in_flight*2) AND receive_window>0) AS open_samples,
            count(*) FILTER(WHERE receive_window>0 AND bytes_in_flight>=receive_window*.9) AS blocked_samples,
            count(*) FILTER(WHERE gap_ms>=?) AS idle_gaps,max(bytes_in_flight) AS max_bytes_in_flight,
            max(receive_window) AS max_receive_window FROM transport_samples GROUP BY ALL)
        SELECT d.*,coalesce(a.zero_windows,0) AS zero_windows,coalesce(a.zero_span_ms,0) AS zero_span_ms,
        coalesce(a.window_shrinks,0) AS window_shrinks,coalesce(a.blocked_windows,0) AS blocked_windows
        FROM d LEFT JOIN a USING(flow,point,direction)
-- name: transport_edges
CREATE OR REPLACE TABLE transport_edges(direction VARCHAR,a VARCHAR,b VARCHAR,hop VARCHAR)
-- name: transport_edges_insert
INSERT INTO transport_edges VALUES (?,?,?,?)
-- name: transport_transit
CREATE OR REPLACE TABLE transport_transit AS
        SELECT a.flow,e.direction,e.hop,a.ts,a.corrected,(b.corrected-a.corrected)*1000 AS ms,a.frame,a.point,b.frame AS peer_frame,b.point AS peer_point,
        min(a.ts) OVER(PARTITION BY a.flow,e.hop) AS first_ts,max(a.ts) OVER(PARTITION BY a.flow,e.hop) AS last_ts
        FROM transport_edges e JOIN obs a ON a.point=e.a AND a.direction=e.direction AND a.eligible AND a.length>0 AND NOT a.retrans
        JOIN obs b ON b.point=e.b AND b.direction=e.direction AND b.flow=a.flow AND b.packet_key=a.packet_key AND b.eligible
        WHERE a.corrected IS NOT NULL AND b.corrected>=a.corrected
-- name: transport_path_facts
CREATE OR REPLACE TABLE transport_path_facts AS WITH p AS(
        SELECT flow,direction,hop,count(*) AS samples,quantile_cont(ms,.95) AS p95_ms,
        median(ms) FILTER(WHERE ts<=first_ts+(last_ts-first_ts)*.25) AS baseline,
        median(ms) FILTER(WHERE ts>first_ts+(last_ts-first_ts)*.25) AS later,
        count(*) FILTER(WHERE ts<=first_ts+(last_ts-first_ts)*.25) AS early_samples FROM transport_transit GROUP BY ALL)
        SELECT flow,direction,sum(samples) AS path_samples,max(p95_ms) AS path_p95_ms,
        bool_or(early_samples>=3 AND later>greatest(baseline*2,baseline+20)) AS path_degraded FROM p GROUP BY ALL
-- name: transport_rtt_facts
CREATE OR REPLACE TABLE transport_rtt_facts AS WITH r AS(
        SELECT *,min(ts) OVER(PARTITION BY flow,point,direction) AS lo,max(ts) OVER(PARTITION BY flow,point,direction) AS hi
        FROM transport_acks WHERE rtt IS NOT NULL)
        SELECT flow,point,direction,count(*) AS rtt_samples,median(rtt)*1000 AS rtt_ms,
        count(*) FILTER(WHERE ts<=lo+(hi-lo)*.25)>=3 AND count(*) FILTER(WHERE ts>lo+(hi-lo)*.25)>=3
        AND median(rtt) FILTER(WHERE ts>lo+(hi-lo)*.25)>greatest(.1,2*median(rtt) FILTER(WHERE ts<=lo+(hi-lo)*.25)) AS rtt_degraded
        FROM r GROUP BY ALL
-- name: RETRY_KIND
CASE WHEN NOT trusted OR NOT indexed THEN 'unknown'
    WHEN spurious AND delivered THEN 'spurious'
    WHEN sack_hole AND fast_retrans AND duplicate_acks>=2 THEN 'sack'
    WHEN fast_retrans THEN 'fast'
    WHEN original_range AND rto_seconds>=rto_bound AND prior_rtts>=3 AND duplicate_acks=0 AND NOT sack_hole THEN 'rto'
    ELSE 'unknown' END
-- name: transport_retry_signals
CREATE OR REPLACE TABLE transport_retry_signals AS
        SELECT r.*,s.relative_seq,s.seq_end,h.prior_rtts,
        EXISTS(SELECT 1 FROM transport_tcp original WHERE original.point=r.point AND original.flow=r.flow AND original.frame=r.rto_frame
            AND (r.raw_seq-original.raw_seq+4294967296)%4294967296+r.length<=original.length) AS original_range,greatest(1,coalesce(h.prior_median+4*h.variation,1)) AS rto_bound,
        coalesce(a.delivered,false) AS delivered,coalesce(a.sack_hole,false) AS sack_hole,coalesce(a.duplicate_acks,0) AS duplicate_acks,a.ack_trigger_frame,a.delivered_frame
        FROM transport_tcp r LEFT JOIN transport_samples s ON r.point=s.point AND r.flow=s.flow AND r.frame=s.frame
        LEFT JOIN LATERAL(SELECT count(*) AS prior_rtts,median(rtt) AS prior_median,stddev_pop(rtt) AS variation
            FROM transport_acks t WHERE t.flow=r.flow AND t.point=r.point AND t.direction=r.direction AND t.ts<(SELECT min(x.ts) FROM transport_tcp x WHERE x.point=r.point AND x.flow=r.flow AND x.direction=r.direction AND (x.retrans OR x.fast_retrans OR x.spurious)) AND t.rtt IS NOT NULL) h ON true
        LEFT JOIN LATERAL(SELECT bool_or(t.ack_end>=s.seq_end) AS delivered,
            bool_or(t.ts>=r.ts-.05 AND t.sack_left IS NOT NULL AND (t.sack_left-r.raw_seq+4294967296)%4294967296 BETWEEN 1 AND 2147483647
                AND t.ack_end<=s.relative_seq) AS sack_hole,
            count(*) FILTER(WHERE t.duplicate_ack AND t.ts>=r.ts-.05) AS duplicate_acks,
            arg_max(t.frame,(t.ts,t.frame)) FILTER(WHERE t.duplicate_ack OR t.sack_left IS NOT NULL) AS ack_trigger_frame,
            arg_min(t.frame,(t.ts,t.frame)) FILTER(WHERE t.ack_end>=s.seq_end) AS delivered_frame
            FROM transport_acks t WHERE t.flow=r.flow AND t.point=r.point AND t.direction=r.direction AND t.ts<r.ts
) a ON true
        WHERE r.retrans OR r.fast_retrans OR r.spurious
-- name: transport_retries
CREATE OR REPLACE TABLE transport_retries AS SELECT *,{RETRY_KIND} AS retry_kind FROM transport_retry_signals
-- name: transport_order_neighbors
CREATE OR REPLACE TEMP TABLE transport_order_neighbors AS SELECT *,
        lead(packet_key) OVER(PARTITION BY flow,point,direction ORDER BY relative_seq,ts,frame) AS next_key,
        lead(ts) OVER(PARTITION BY flow,point,direction ORDER BY relative_seq,ts,frame) AS next_ts
        FROM transport_samples WHERE eligible AND trusted AND indexed AND handshake AND NOT retrans AND NOT spurious
-- name: transport_reordering
CREATE OR REPLACE TABLE transport_reordering AS
        SELECT a.flow,e.direction,e.hop,a.point,a.frame,b.point AS downstream,b.frame AS downstream_frame,
        c.frame AS neighbor_frame,d.frame AS downstream_neighbor_frame,b.corrected AS time,
        'Original matched byte ranges changed arrival order across this edge; in order upstream and reversed downstream; a generic first-appearance retransmission hint is resolved by the original pair' AS reason
        FROM transport_edges e JOIN transport_order_neighbors a ON a.point=e.a AND a.direction=e.direction AND NOT a.out_of_order
        JOIN transport_tcp b ON b.point=e.b AND b.flow=a.flow AND b.packet_key=a.packet_key AND (b.out_of_order OR (b.retrans AND NOT EXISTS(SELECT 1 FROM transport_tcp old WHERE old.point=b.point AND old.flow=b.flow AND old.frame=b.rto_frame AND (b.raw_seq-old.raw_seq+4294967296)%4294967296+b.length<=old.length))) AND NOT b.fast_retrans AND NOT b.spurious AND b.trusted AND b.indexed AND b.eligible
        JOIN transport_tcp c ON c.point=e.a AND c.flow=a.flow AND c.packet_key=a.next_key AND c.direction=a.direction
        JOIN transport_tcp d ON d.point=e.b AND d.flow=a.flow AND d.packet_key=a.next_key AND d.direction=a.direction AND d.trusted AND d.indexed AND d.eligible
        WHERE a.ts<c.ts AND b.ts>d.ts AND b.ts-d.ts<.1 AND b.corrected-a.corrected BETWEEN 0 AND .1
        QUALIFY row_number() OVER(PARTITION BY a.flow,e.direction,a.packet_key ORDER BY b.corrected,e.hop,a.frame,b.frame)=1
-- name: profile_cache
CREATE TABLE IF NOT EXISTS transport_profile_cache(flow VARCHAR PRIMARY KEY,generation VARCHAR,value VARCHAR)
-- name: profile_cache_get
SELECT value FROM transport_profile_cache WHERE flow=? AND generation=?
-- name: profile_cache_prune
DELETE FROM transport_profile_cache WHERE generation<>?
-- name: profile_cache_put
INSERT OR REPLACE INTO transport_profile_cache VALUES (?,?,?)
-- name: point_data
SELECT f.*,p.path_samples,p.path_p95_ms,p.path_degraded,r.rtt_ms,r.rtt_degraded
            FROM transport_point_facts f LEFT JOIN transport_path_facts p USING(flow,direction)
            LEFT JOIN transport_rtt_facts r USING(flow,point,direction) WHERE (? IS NULL OR f.flow=?) ORDER BY f.flow,f.point,f.direction
-- name: losses
SELECT flow,direction,count(*) AS loss_count,max(coalesce(recovery_ms,impact_ms)) AS loss_stall_ms,
            arg_max(direction||':'||point_a||':'||point_b,CASE WHEN coalesce(recovery_ms,impact_ms) IS NOT NULL THEN (coalesce(recovery_ms,impact_ms),direction||':'||point_a||':'||point_b) END) AS loss_hop
            FROM events WHERE direction||':'||point_a||':'||point_b IN(SELECT hop FROM transport_edges) AND kind IN('recovered_loss','impactful_loss','unrecovered_loss','handshake_blocked','confirmed_device_drop') GROUP BY ALL
-- name: observed_edges
SELECT flow,direction,hop FROM transport_transit GROUP BY ALL HAVING count(*)>=3
-- name: signal_samples
SELECT point,frame,ack_frame,window_frame FROM transport_samples WHERE flow=? AND direction=?
                ORDER BY zero_probe DESC,window_full DESC,gap_ms DESC NULLS LAST,ack_delay_ms DESC NULLS LAST,point,frame LIMIT 6
-- name: retry_points
SELECT t.flow,t.point,t.direction,bool_and(t.trusted AND t.indexed) AS usable,
        count(*) FILTER(WHERE t.duplicate_ack) AS duplicate_acks,
        count(*) FILTER(WHERE r.retry_kind='rto') AS rto,
        count(*) FILTER(WHERE r.retry_kind='fast') AS fast,
        count(*) FILTER(WHERE r.retry_kind='spurious') AS spurious,
        count(*) FILTER(WHERE r.retry_kind='sack') AS sack,
        count(*) FILTER(WHERE r.retry_kind='unknown') AS unknown
        FROM transport_tcp t LEFT JOIN transport_retries r ON t.point=r.point AND t.flow=r.flow AND t.frame=r.frame
        WHERE (? IS NULL OR t.flow=?) GROUP BY t.flow,t.point,t.direction ORDER BY t.flow,t.point,t.direction
-- name: duplicate_ack_counts
SELECT flow,point,CASE WHEN direction='forward' THEN 'reverse' ELSE 'forward' END AS direction,count(*) AS n
        FROM transport_tcp WHERE duplicate_ack GROUP BY ALL
-- name: retry_edges
SELECT a,direction,hop FROM transport_edges
-- name: retry_events
SELECT flow,point,frame,rto_frame,retry_kind,rto_seconds,rto_bound,prior_rtts,duplicate_acks,sack_hole,ack_trigger_frame,delivered_frame FROM transport_retries
        WHERE trusted AND indexed AND (? IS NULL OR flow=?) ORDER BY ts,point,frame LIMIT 200
-- name: retry_reordering
SELECT * FROM transport_reordering WHERE (? IS NULL OR flow=?) ORDER BY time,hop,point,frame LIMIT 200
-- name: http_flows
SELECT DISTINCT flow FROM obs WHERE proto='TCP' AND
            json_extract_string(path_fields,'$.http_method') IS NOT NULL ORDER BY flow LIMIT ?
-- name: proxy_service_pairs
SELECT last_frame,response_frame,complete,response,point FROM proxy_requests
                WHERE flow=? AND point=? AND ready AND response IS NOT NULL ORDER BY complete,last_frame LIMIT 100
-- name: obs_stamps
SELECT frame,ts FROM obs WHERE point=? AND frame IN (?,?)
-- name: server_packets
SELECT * FROM obs WHERE point=? AND flow=? AND proto='TCP' ORDER BY ts,frame LIMIT ?
