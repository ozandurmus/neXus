-- Verbatim from the reference hold_release.py.
-- name: copy_trains
CREATE OR REPLACE TEMP TABLE copy_trains AS WITH repeated AS (
        SELECT point,flow,direction,seq,length FROM obs WHERE proto='TCP' AND length>0 AND eligible
        GROUP BY ALL HAVING count(*) BETWEEN 3 AND 8)
        SELECT point,flow,direction,seq,length,
        list(ts ORDER BY ts,frame) AS times,list(frame ORDER BY ts,frame) AS frames,list(ipid ORDER BY ts,frame) AS ids,
        min(coalesce(corrected,ts)) AS first_time,max(ts)-min(ts) AS span
        FROM obs JOIN repeated USING(point,flow,direction,seq,length) WHERE proto='TCP' AND length>0 AND eligible
        GROUP BY ALL
-- name: trains
SELECT a.flow,a.seq,a.first_time,a.times AS wire_times,b.times AS host_times,
            a.frames AS wire_frames,b.frames AS host_frames FROM copy_trains a JOIN copy_trains b USING(flow,direction,seq)
            WHERE a.point=? AND b.point=? AND a.direction=? AND len(a.times)=len(b.times) AND a.ids=b.ids
            AND a.span>=.3 AND b.span<=least(.02,a.span*.1) ORDER BY a.span DESC,a.flow,a.seq,a.length,b.length LIMIT 100
