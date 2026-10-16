-- Verbatim from the reference timeseries.py (build_timeseries, event_buckets).
-- name: segment_buckets
CREATE OR REPLACE TABLE segment_buckets (
        segment VARCHAR,direction VARCHAR,bucket BIGINT,start DOUBLE,"end" DOUBLE,
        coverage VARCHAR,reason VARCHAR,eligible_packets BIGINT,matched_packets BIGINT,
        capture_misses BIGINT,unknown_events BIGINT,loss_count BIGINT,matchable_packets BIGINT,tcp_packets BIGINT,retransmissions BIGINT,failed_handshake_count BIGINT,{metric_sql})
-- name: bucket_obs
CREATE OR REPLACE TEMP VIEW bucket_obs AS SELECT *,
        floor((corrected-{origin})/{width})::BIGINT AS bucket FROM obs
        WHERE corrected>={origin} AND corrected<{stop}
-- name: aggregate
SELECT bucket,count(*) AS packets,count(*) FILTER(WHERE eligible) AS matchable,
            sum(wirelen)*8 AS bits,count(*) FILTER(WHERE proto='TCP') AS tcp,
            count(*) FILTER(WHERE retrans) AS retrans,
            count(*) FILTER(WHERE eligible AND (length>0 OR (proto='TCP' AND (flags&7)>0) OR proto IN ('UDP','ICMP'))) AS eligible,
            median(rtt)*1000 AS rtt_ms,count(DISTINCT flow) FILTER(WHERE proto='TCP' AND (flags&18)=2 AND NOT retrans) AS new_connections,
            count(*) FILTER(WHERE proto='TCP' AND (flags&4)>0) AS resets,
            count(*) FILTER(WHERE zero_window) AS zero_windows
            FROM bucket_obs WHERE point=? AND direction=? GROUP BY bucket
-- name: downstream_quality
SELECT bucket,
            count(*) FILTER(WHERE eligible)::DOUBLE/count(*) FROM bucket_obs
            WHERE point=? AND direction=? GROUP BY bucket
-- name: latency
SELECT floor((time_a-?)/?)::BIGINT AS bucket,count(*) AS matched,
            quantile_cont((time_b-time_a)*1000,.5) AS latency_p50_ms,
            quantile_cont((time_b-time_a)*1000,.95) AS latency_p95_ms,
            max((time_b-time_a)*1000) AS latency_max_ms
            FROM observation_matches WHERE point_a=? AND point_b=? AND direction=? GROUP BY bucket
-- name: event_buckets
WITH tagged AS (
        SELECT *,min(ts) FILTER(WHERE kind='handshake_blocked') OVER(PARTITION BY flow) AS first_failure
        FROM events WHERE point_a=? AND point_b=? AND direction=?)
        SELECT floor((ts-?)/?)::BIGINT AS bucket,kind,count(*) AS n,
        count(DISTINCT flow) FILTER(WHERE kind<>'handshake_blocked' OR ts=first_failure) AS flows
        FROM tagged GROUP BY bucket,kind
-- name: describe
DESCRIBE segment_buckets
-- name: insert
INSERT INTO segment_buckets SELECT record.* FROM (SELECT unnest(from_json(?,?)) AS record)
