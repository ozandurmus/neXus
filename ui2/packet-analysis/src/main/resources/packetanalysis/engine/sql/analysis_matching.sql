-- Verbatim from the reference analysis/matching.py (NAT suggestions, tuple canonicalization, prepare).
-- name: nat_candidates
CREATE OR REPLACE TEMP TABLE nat_candidates AS
        SELECT *,md5(signature || left(prefix,16)) AS nat_key FROM obs
        WHERE coalesce(unsupported,'')='' AND length(prefix)>=16 AND point IN (SELECT unnest(?::VARCHAR[]))
        QUALIFY count(*) OVER(PARTITION BY point,nat_key)=1
-- name: nat_pairs
SELECT a.tuple_key AS tuple_a,b.tuple_key AS tuple_b,count(*) AS samples,
            min(b.ts-a.ts) AS min_delta,max(b.ts-a.ts) AS max_delta,min(a.frame) AS frame_a,arg_min(b.frame,(a.frame,b.frame)) AS frame_b
            FROM nat_candidates a JOIN nat_candidates b ON a.nat_key=b.nat_key
            AND left(a.prefix,least(length(a.prefix),length(b.prefix)))=left(b.prefix,least(length(a.prefix),length(b.prefix)))
            WHERE a.point=? AND b.point=? AND a.tuple_key<>b.tuple_key
            GROUP BY a.tuple_key,b.tuple_key HAVING count(*)>=3 AND max(b.ts-a.ts)-min(b.ts-a.ts)<1
            ORDER BY samples DESC,tuple_a,tuple_b LIMIT 1000
-- name: tuple_lookup
CREATE OR REPLACE TEMP TABLE tuple_lookup(tuple_key VARCHAR,canon VARCHAR,canon_reverse VARCHAR,direction VARCHAR)
-- name: distinct_tuples
SELECT DISTINCT tuple_key FROM obs
-- name: tuple_lookup_insert
INSERT INTO tuple_lookup VALUES (?,?,?,?)
-- name: canonical_obs
CREATE OR REPLACE TABLE obs AS SELECT o.* REPLACE(
        t.canon AS canon,t.canon_reverse AS canon_reverse,t.direction AS direction,
        md5(t.canon || o.signature) AS packet_key,md5(least(t.canon,t.canon_reverse)) AS flow)
        FROM obs o JOIN tuple_lookup t ON o.tuple_key=t.tuple_key
-- name: f5_direction
UPDATE obs SET direction=CASE
            WHEN (c.role='client') = (lower(json_extract_string(obs.vendor,'$."f5ethtrailer.ingress"')) IN ('true','1')) THEN 'forward'
            ELSE 'reverse' END FROM f5_connections c WHERE obs.capture_id=c.capture_id AND obs.stream=c.stream
            AND c.role IN ('client','server') AND json_extract_string(obs.vendor,'$."f5ethtrailer.ingress"') IS NOT NULL
-- name: prefix_lengths
CREATE OR REPLACE TEMP TABLE prefix_lengths AS
        SELECT packet_key,min(coalesce(length(prefix),0)) AS n FROM obs GROUP BY packet_key
-- name: prefix_keys
CREATE OR REPLACE TABLE obs AS SELECT o.* REPLACE(
        md5(o.packet_key || left(coalesce(o.prefix,''),p.n)) AS packet_key)
        FROM obs o JOIN prefix_lengths p ON o.packet_key=p.packet_key
-- name: nat_point_tuples
CREATE OR REPLACE TEMP TABLE nat_point_tuples AS
        SELECT DISTINCT point,canon FROM obs WHERE point IN (SELECT unnest(?::VARCHAR[]))
-- name: sip_keys
CREATE OR REPLACE TABLE obs AS SELECT o.* REPLACE(
        CASE WHEN o.proto='UDP' AND d."sip.Call-ID" IS NOT NULL AND d."sip.CSeq.seq" IS NOT NULL
          AND NOT contains(d."sip.CSeq.seq",',') AND o.caplen=o.wirelen
          THEN md5(o.canon||'sip:'||d."sip.Call-ID"||':'||d."sip.CSeq.seq"||':'||coalesce(d."sip.CSeq.method",'')||':'||coalesce(d."sip.Status-Code",'request'))
          ELSE o.packet_key END AS packet_key) FROM obs o LEFT JOIN udp_decoded d USING(point,frame)
-- name: large_tcp
SELECT count(*) FROM obs WHERE proto='TCP' AND length>1500 AND eligible
