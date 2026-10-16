-- Verbatim from the reference fragments.py (normalize_fragments, guard_missing). FRAGMENT_STATUS is the
-- reference module constant concatenated into fragment_groups.
-- name: FRAGMENT_STATUS
CASE WHEN invalid>0 OR starts<>1 OR tails>1 THEN 'unknown'
    WHEN tails=0 OR gaps>0 OR decoded=0 THEN 'incomplete' ELSE 'complete' END
-- name: wire_observations
CREATE OR REPLACE TABLE wire_observations AS SELECT point,capture_id,frame,ts,
        wirelen,caplen,frame_hash,length AS transport_length FROM obs
-- name: fragment_members
CREATE OR REPLACE TABLE fragment_members AS SELECT point,frame,src,dst,proto,
        raw_seq,ipid,ts,caplen,wirelen,
        json_extract_string(path_fields,'$.fragment.id') AS fragment_id,
        json_extract_string(path_fields,'$.fragment.protocol') AS fragment_protocol,
        json_extract_string(path_fields,'$.fragment.family') AS family,
        try_cast(json_extract_string(path_fields,'$.fragment.offset') AS BIGINT) AS off,
        try_cast(json_extract_string(path_fields,'$.fragment.size') AS BIGINT) AS size,
        try_cast(json_extract_string(path_fields,'$.fragment.more') AS BOOLEAN) AS more,
        try_cast(json_extract_string(path_fields,'$.fragment.complete') AS BOOLEAN) AS decoded,
        try_cast(json_extract_string(path_fields,'$.fragment.invalid') AS BOOLEAN) AS invalid,
        json_extract_string(path_fields,'$.fragment.target') AS target,
        json_extract_string(path_fields,'$.tunnel.vni') AS vni
        FROM obs WHERE json_exists(path_fields,'$.fragment')
-- name: fragment_episodes
CREATE OR REPLACE TEMP TABLE fragment_episodes AS
        SELECT *,sum(CASE WHEN off=0 THEN 1 ELSE 0 END) OVER(
            PARTITION BY point,src,dst,fragment_id,fragment_protocol,vni ORDER BY ts,frame) AS episode
        FROM fragment_members
-- name: fragment_spans
CREATE OR REPLACE TEMP TABLE fragment_spans AS SELECT *,
        coalesce('t:'||target,'e:'||episode::VARCHAR) AS assembly,
        lag(off+size) OVER(PARTITION BY point,src,dst,fragment_id,fragment_protocol,vni,
            coalesce('t:'||target,'e:'||episode::VARCHAR) ORDER BY off,frame) AS prior_end
        FROM fragment_episodes
-- name: fragment_groups
CREATE OR REPLACE TABLE fragment_groups AS WITH g AS (
        SELECT point,src,dst,fragment_id,fragment_protocol,vni,assembly,min(family) AS family,
        count(*) AS fragments,list(frame ORDER BY frame) AS members,
        min(ts) AS first_ts,max(ts) AS last_ts,min(frame) AS first_frame,
        coalesce(min(frame) FILTER(WHERE decoded),min(frame)) AS frame,
        count(*) FILTER(WHERE invalid OR caplen<wirelen OR size<=0 OR prior_end>off) AS invalid,
        count(*) FILTER(WHERE off=0) AS starts,count(*) FILTER(WHERE NOT more) AS tails,
        count(*) FILTER(WHERE prior_end<off) AS gaps,count(*) FILTER(WHERE decoded) AS decoded,
        arg_min(raw_seq,frame) AS first_seq,min(ipid) AS ipid
        FROM fragment_spans GROUP BY point,src,dst,fragment_id,fragment_protocol,vni,assembly)
        SELECT *,{FRAGMENT_STATUS} AS status FROM g
-- name: any_group
SELECT 1 FROM fragment_groups LIMIT 1
-- name: ambiguous_groups
UPDATE fragment_groups SET status='unknown' WHERE status<>'complete' AND
        (point,src,dst,fragment_id,fragment_protocol) IN (
        SELECT point,src,dst,fragment_id,fragment_protocol FROM fragment_groups WHERE status<>'complete'
        GROUP BY ALL HAVING count(*)>1)
-- name: normalized_fragments
CREATE OR REPLACE TEMP TABLE normalized_fragments AS SELECT o.* REPLACE (
        CASE WHEN g.frame IS NULL THEN o.unsupported WHEN g.status='complete' THEN ''
            WHEN g.status='incomplete' THEN 'Incomplete IP datagram: one or more fragments missing'
            ELSE 'Overlapping, truncated or inconsistent fragments; datagram identity unknown' END AS unsupported,
        CASE WHEN g.frame IS NULL THEN o.path_fields ELSE json_merge_patch(o.path_fields,json_object('fragment',json_object(
            'status',g.status,'members',g.members,'count',g.fragments,'id',g.fragment_id,
            'family',g.family,'protocol',g.fragment_protocol,'first_time',g.first_ts,'last_time',g.last_ts))) END AS path_fields)
        FROM obs o LEFT JOIN fragment_groups g ON o.point=g.point AND o.frame=g.frame
        WHERE o.point IN (SELECT DISTINCT point FROM fragment_groups) AND
            (g.frame IS NOT NULL OR NOT EXISTS(SELECT 1 FROM fragment_members m WHERE m.point=o.point AND m.frame=o.frame))
-- name: delete_fragment_points
DELETE FROM obs WHERE point IN (SELECT DISTINCT point FROM fragment_groups)
-- name: insert_normalized
INSERT INTO obs SELECT * FROM normalized_fragments
-- name: drop_normalized
DROP TABLE normalized_fragments
-- name: guard_missing
UPDATE missing SET range_unknown='Overlapping or inconsistent fragments at the next point; disappearance unknown'
        WHERE EXISTS(SELECT 1 FROM fragment_groups g JOIN obs b ON b.point=g.point AND b.frame=g.frame
            WHERE g.point=? AND g.status='unknown' AND missing.canon=b.canon
            AND missing.raw_seq=g.first_seq AND missing.ipid=g.ipid)
-- name: fragment_member_rows
SELECT frame,ts,off FROM fragment_members WHERE point=? AND frame IN (SELECT unnest(?::BIGINT[]))
-- Verbatim from the reference fragments.py fragment_findings (b.frame appended to the LIMIT ordering).
-- name: fragment_findings
SELECT a.frame AS before,b.frame AS after,b.flow,b.corrected,g.fragments,g.members,g.fragment_id,g.status
            FROM obs a JOIN obs b ON a.flow=b.flow AND (a.packet_key=b.packet_key OR (json_extract_string(b.path_fields,'$.fragment.status')='incomplete' AND a.canon=b.canon AND a.ipid=b.ipid AND a.seq=b.seq AND a.ack=b.ack AND a.flags=b.flags AND a.length>0 AND b.corrected>=a.corrected AND b.corrected-a.corrected<=?))
            JOIN fragment_groups g ON g.point=b.point AND g.frame=b.frame
            WHERE a.point=? AND b.point=? AND a.direction=? AND a.eligible AND (b.eligible OR g.status='incomplete')
            AND g.status IN ('complete','incomplete') AND NOT json_exists(a.path_fields,'$.fragment')
            QUALIFY count(*) OVER(PARTITION BY b.point,b.frame)=1 ORDER BY b.corrected,b.frame LIMIT 100
-- name: fragment_quality
SELECT point,status,count(*) AS datagrams,sum(fragments) AS physical_fragments
        FROM fragment_groups GROUP BY ALL ORDER BY point,status
