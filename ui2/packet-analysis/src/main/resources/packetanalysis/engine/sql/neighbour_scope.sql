-- NOT from the frozen reference: neighbour capture scope (NeighbourScope.java; README "Intentional divergences from
-- the reference", DETERMINISM.md "Intentional divergences"). Every statement below runs only on the engine paths
-- that NeighbourScope enables; a case without an out-of-scope flow runs the verbatim reference statements.
--
-- hop_unobserved: flows with packets at point A in direction D (?1 A, ?2 D) that have no observation at the neighbour
-- B (?3) in either direction, restricted on a NAT hop to flows carrying a confirmed mapping tuple at A (?4 restricted,
-- ?5 tuple keys), and proven to have crossed B: a packet of direction D at a point after B on the D path (?6 D,
-- ?7 points), or reply traffic of the other direction (?8) at a point after B on that path (?9 points) that is not a
-- reset and carries payload or an ACK without SYN (a device answering a blocked handshake sends neither).
-- name: hop_unobserved
SELECT a.flow,count(*) AS packets FROM obs a
        WHERE a.point=? AND a.direction=? AND a.flow IS NOT NULL
        AND NOT EXISTS(SELECT 1 FROM obs b WHERE b.point=? AND b.flow=a.flow)
        AND (NOT ? OR EXISTS(SELECT 1 FROM obs m WHERE m.point=a.point AND m.flow=a.flow
            AND m.tuple_key IN (SELECT unnest(?::VARCHAR[]))))
        AND EXISTS(SELECT 1 FROM obs t WHERE t.flow=a.flow AND (
            (t.direction=? AND t.point IN (SELECT unnest(?::VARCHAR[])))
            OR (t.direction=? AND t.point IN (SELECT unnest(?::VARCHAR[]))
                AND (coalesce(t.flags,0)&4)=0
                AND (coalesce(t.length,0)>0
                    OR (t.proto='TCP' AND (coalesce(t.flags,0)&2)=0 AND (coalesce(t.flags,0)&16)>0)))))
        GROUP BY a.flow ORDER BY a.flow
-- Index scope (restrict_index_scope): flows the reference keeps only because they are client tuples, seen at one
-- capture point (?1 evidence points are kept by the reference regardless and are not candidates).
-- name: single_point
CREATE OR REPLACE TEMP TABLE scope_single_point AS SELECT flow,min(point) AS point FROM obs
        WHERE flow IN (SELECT flow FROM scoped_flows) GROUP BY flow
        HAVING count(DISTINCT point)=1 AND min(point) NOT IN (SELECT unnest(?::VARCHAR[]))
-- name: single_point_count
SELECT count(*) FROM scope_single_point
-- name: single_point_assessed
SELECT DISTINCT s.flow FROM scope_single_point s JOIN obs o ON o.flow=s.flow AND o.point=s.point
        WHERE s.point=? AND o.direction=? ORDER BY s.flow
-- name: scope_excluded
CREATE OR REPLACE TEMP TABLE scope_excluded(flow VARCHAR)
-- name: scope_excluded_insert
INSERT INTO scope_excluded VALUES (?)
-- name: scope_excluded_delete
DELETE FROM scoped_flows WHERE flow IN (SELECT flow FROM scope_excluded)
-- selection.sql analysis_scope_update, plus excluded_single_point_flows from the same index scope as the frames:
-- the reference updates only the frames, so flows dropped by restrict_index_scope were never counted as flows.
-- name: analysis_scope_update
UPDATE analysis_scope SET retained_frames=i.retained_frames,
        excluded_single_point_frames=analysis_scope.input_frames-i.retained_frames,
        excluded_single_point_flows=i.input_flows-i.retained_flows FROM index_scope i WHERE analysis_scope.point=i.point
-- Per hop (segments): the flows of one hop that are out of its scope.
-- name: hop_scope
CREATE OR REPLACE TABLE hop_scope(point_a VARCHAR,point_b VARCHAR,direction VARCHAR,flow VARCHAR,packets BIGINT)
-- name: hop_scope_insert
INSERT INTO hop_scope VALUES (?,?,?,?,?)
-- Appended to classification:total, classification:byte_count and headlines:denominator (?: A, B, direction).
-- name: hop_filter
AND flow NOT IN (SELECT flow FROM hop_scope WHERE point_a=? AND point_b=? AND direction=?)
-- Appended to the classification:missing condition ({a}, {b}, {direction} are SQL literals).
-- name: missing_scope
a.flow NOT IN (SELECT flow FROM hop_scope WHERE point_a={a} AND point_b={b} AND direction={direction})
-- timeseries:aggregate with the hop filter (?: A, direction, A, B, direction).
-- name: aggregate_scoped
SELECT bucket,count(*) AS packets,count(*) FILTER(WHERE eligible) AS matchable,
            sum(wirelen)*8 AS bits,count(*) FILTER(WHERE proto='TCP') AS tcp,
            count(*) FILTER(WHERE retrans) AS retrans,
            count(*) FILTER(WHERE eligible AND (length>0 OR (proto='TCP' AND (flags&7)>0) OR proto IN ('UDP','ICMP'))) AS eligible,
            median(rtt)*1000 AS rtt_ms,count(DISTINCT flow) FILTER(WHERE proto='TCP' AND (flags&18)=2 AND NOT retrans) AS new_connections,
            count(*) FILTER(WHERE proto='TCP' AND (flags&4)>0) AS resets,
            count(*) FILTER(WHERE zero_window) AS zero_windows
            FROM bucket_obs WHERE point=? AND direction=?
            AND flow NOT IN (SELECT flow FROM hop_scope WHERE point_a=? AND point_b=? AND direction=?) GROUP BY bucket
