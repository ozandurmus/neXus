# Packet Analysis core engine — determinism

Status: implementation record for the PO decision of 2026-10-15 ("same input must give the same output").
Scope: the core engine (`com.securityexpert.nexus.ui2.packetanalysis.engine`), SQL resources under
`src/main/resources/packetanalysis/engine/sql/`.

## Rule applied

The frozen reference is not deterministic with several DuckDB threads: ties in `ORDER BY ... LIMIT`, in
`arg_min`/`arg_max`, in window orderings and the output order of `GROUP BY` without `ORDER BY` resolve
differently from run to run. Every tie-sensitive construct the core engine ports got a tie-break that only
decides among rows the reference itself considers equal: stable keys are appended **after** the reference's own
ordering key; no comparison, criterion, threshold or formula was changed.

For `arg_min(x, k)` / `arg_max(x, k)` the ordering key becomes the struct `(k, x)`: the reference's minimum of
`k` is kept and, among rows with that same `k`, the smallest (largest) `x` is taken. In every ported use `k` is
never NULL in the aggregated rows (it is constrained by the join or `HAVING`), so the struct form ignores the
same rows the scalar form ignores.

Parity runs both engines with one DuckDB thread (`-Dpacketanalysis.duckdb.threads=1`; the reference through the
harness). `EngineParityTest.sameInputSameOutputAcrossThreadCounts` runs the same frozen inputs with threads=1
and threads=4 and asserts identical report text and identical `obs`, `events`, `flow_summary`, `flow_filters`,
`index_scope`, `analysis_scope` tables.

## Tie-breaks added

| File | Statement | Construct | Reference ordering | Appended keys |
|---|---|---|---|---|
| `analysis_matching.sql` | `nat_pairs` | `arg_min(b.frame, a.frame)` | `a.frame` | `b.frame` (struct key) |
| `matching.sql` | `nearest` | `row_number() OVER(PARTITION BY point,frame ORDER BY ...)` | `distance, anchor_order` | `anchor_id` |
| `matching.sql` | `assigned` | `row_number() OVER(PARTITION BY point,anchor_id ORDER BY ...)` | `distance, occurrence` | `frame` |
| `translation.sql` | `sequence_found` | `arg_min(fb, fa)` | `fa` | `fb` (struct key) |
| `translation.sql` | `sequence_report` | `arg_min(o.flow, o.frame)` | `o.frame` | `o.flow` (struct key) |
| `translation.sql` | `sequence_report` | `ORDER BY ... LIMIT 200` | `m.ingress, m.ingress_stream` | `m.point, m.old_flow, m.stream, m.frame_a, m.frame_b` |
| `byte_ranges.sql` | `flow_byte_obs` | `row_number() OVER(PARTITION BY o.point,o.frame ORDER BY ...)` | `a.lo` | `a.hi, r.kind` |
| `byte_ranges.sql` | `flow_clock_atoms` (chosen) | `row_number() OVER(PARTITION BY point,frame ORDER BY ...)` | byte-calibration preference `CASE` | `c.packet_key` |
| `byte_ranges.sql` | `flow_clock_atoms` | `row_number() OVER(PARTITION BY o.point,o.direction ORDER BY ...) <= 256` | `hash(c.packet_key)` | `c.packet_key, o.frame` |
| `byte_ranges.sql` | `byte_recovered_atoms` | `arg_min(r.source_packet_key, r.corrected)` | `r.corrected` | `r.source_packet_key` (struct key) |
| `byte_ranges.sql` | `byte_recoveries` | `arg_max(r.recovery_key, r.recovery_time)` | `r.recovery_time` | `r.recovery_key` (struct key) |
| `byte_ranges.sql` | `byte_missing` | `list(... ORDER BY lo)` | `lo` | `hi, kind` |
| `byte_ranges.sql` | `range_notes` | `GROUP BY point` output order | none | `ORDER BY point` |
| `classification.sql` | `recoveries` | `arg_min(r.packet_key, r.corrected)` | `r.corrected` | `r.frame` (struct key; see below) |
| `classification.sql` | `acked` | `arg_min(ack.packet_key, ack.corrected)` | `ack.corrected` | `ack.frame` (struct key; see below) |
| `classification.sql` | `acked_replies` | `arg_min(reply.packet_key, reply.corrected)` | `reply.corrected` | `reply.frame` (struct key; see below) |
| `classification.sql` | `acked_resets` | `arg_min(rst.packet_key, rst.corrected)` | `rst.corrected` | `rst.frame` (struct key; see below) |
| `classification.sql` | `failures` | `arg_min(f.packet_key, f.corrected)` | `f.corrected` | `f.frame` (struct key; see below) |
| `classification.sql` | `first_event` | `ORDER BY ts LIMIT 1` | `ts` | `id, packet_key` (see below) |
| `classification.sql` | `classes` | `GROUP BY kind` output order (segment `classes` key order, finding order before the stable severity/time sort) | none | `ORDER BY kind` |
| `segments.sql` | `endpoint_quality` | `GROUP BY point,reason` output order (`excluded` key order) | none | `ORDER BY point, reason` |
| `segments.sql` | `quality` | `GROUP BY point` output order (report `quality`) | none | `ORDER BY point` |
| `report.sql` | `coverage` | `GROUP BY point` output order (report `coverage`) | none | `ORDER BY point` |
| `report.sql` | `analysis_scope` | `SELECT *` output order (report `analysis_scope`) | none | `ORDER BY point` |
| `capture_layers.sql` | `tunnel_report` | `GROUP BY point` output order (report `tunnel_points`) | none | `ORDER BY point` |
| `evidence.sql` | `range_peers` | `list(DISTINCT struct_pack(...))` element order (evidence `byte_ranges`) | none | `ORDER BY` the struct (`start_seq, end_seq, bytes`) |
| `udp.sql` | `udp_stops` | `arg_min(direction\|\|':'\|\|point_a\|\|':'\|\|point_b, ts)` | `ts` | `direction\|\|':'\|\|point_a\|\|':'\|\|point_b` (struct key) |
| `udp.sql` | `summary` | `GROUP BY ALL ORDER BY point,protocol` | `point, protocol` | `endpoint` |
| `dns.sql` | `dns_losses` | `arg_min(direction\|\|':'\|\|point_a\|\|':'\|\|point_b, ts)` | `ts` | `direction\|\|':'\|\|point_a\|\|':'\|\|point_b` (struct key) |
| `rtp.sql` | `rtp_streams` | `arg_min(frame, ts)`, `arg_max(frame, ts)` | `ts` | `frame` (struct key) |
| `rtp.sql` | `losses` | `arg_min(e.packet_key, e.ts)` | `e.ts` | `e.packet_key` (struct key) |
| `rtp.sql` | `rtp_transit` | `lag(...) OVER(PARTITION BY x.flow,x.ssrc ORDER BY ...)` | `m.time_a, x.frame` | `y.frame` |
| `rtp.sql` | `signals` | `arg_max(frame_a, abs(transit_ms-previous_transit))`, `arg_max(frame_b, ...)` | `abs(transit_ms-previous_transit)` | `frame_a, frame_b` (struct key) |
| `rtp.sql` | `one_way` | `GROUP BY point,flow HAVING count(DISTINCT direction)=1` output order | none | `ORDER BY point, flow` |
| `sip.sql` | `calls` | `GROUP BY ALL HAVING ...` output order | none | `ORDER BY call_id, signaling_flow` |
| `vendors.sql` | `f5_report_connections` | `clients` `ORDER BY ... LIMIT 100`, `unknown` `ORDER BY ... LIMIT 20`, outer `UNION` | `capture_id, stream, peerid` / `capture_id, stream, flowid` / none | `flowid, tmm` / `peerid, tmm` / outer `ORDER BY capture_id, stream, flowid, peerid, tmm, role, reason` |
| `vendors.sql` | `f5_request_packets` | `ORDER BY ... LIMIT 2001` | `ts, frame` | `point` |
| `vendors.sql` | `f5_resets` | `ORDER BY ... LIMIT 200` | `ts` | `point, frame` |
| `integrity.sql` | `candidates` | outer row order after `QUALIFY row_number() ... <=?` (candidate order decides finding order and the id counter) | none | `ORDER BY e.id, b.corrected NULLS LAST, b.frame` |
| `integrity.sql` | `origin_samples`, `icmp_samples`, `local_samples`, `endpoint_refs`, `mtu_small`, `mtu_large`, `span_duplicate` | `ORDER BY corrected LIMIT n` | `corrected` | `frame` (one capture point per query) |
| `integrity.sql` | `mtu_feedback` | `ORDER BY corrected LIMIT 4` | `corrected` | `point, frame` |
| `integrity.sql` | `changed_pair` | `arg_min(a.frame, ...)` / `arg_min(b.frame, ...)` | `(a.corrected, a.frame)` | `b.frame` (struct key) |
| `integrity.sql` | `endpoint_packets` | outer row order after `QUALIFY row_number() OVER(PARTITION BY flow,proto ORDER BY corrected,frame)=1` | none before `LIMIT ?` | `ORDER BY corrected, frame` |
| `integrity.sql` | `payload_candidates` | `ORDER BY ... LIMIT ?` | `a.corrected, a.frame` | `b.frame` |
| `integrity.sql` | `mtu_losses` | `first(a)` / `list(DISTINCT a.frame)` / `GROUP BY a.flow LIMIT ?` | none | `first(a ORDER BY a.corrected,a.frame)`, `list(DISTINCT a.frame ORDER BY a.frame)`, `ORDER BY a.flow` |
| `integrity.sql` | `asymmetric_pairs` | `return_first` `row_number()` and outer `ORDER BY ... LIMIT 1` | `corrected, frame` / `f.corrected, r.corrected` | `point, frame` / `f.frame, r.point, r.frame` |
| `fragments.sql` | `fragment_findings` | `ORDER BY ... LIMIT 100` | `b.corrected` | `b.frame` |
| `fragments.sql` | `fragment_quality` | `GROUP BY ALL` output order | `ORDER BY point, status` | — |
| `proxy.sql` | `components`, `response_start` | `arg_min(o.frame, relative_seq)` | `relative_seq` | `o.frame` (struct key) |
| `proxy.sql` | `responses` | `arg_min(r.frame, ro.corrected)` | `ro.corrected` | `r.frame` (struct key) |
| `proxy.sql` | `http_pdus` | `arg_max(end_seq, relative_seq)` / `arg_min(o.frame, relative_seq)` | `relative_seq` | `end_seq` / `o.frame` (struct key) |
| `proxy.sql` | `ordered_requests`, `waterfall_requests` | `ORDER BY ...` | `start, point, last_frame` | `id` |
| `proxy.sql` | `resets` | `ORDER BY ... LIMIT 501` | `corrected, frame` | `point` (between `corrected` and `frame`) |
| `tls.sql` | `facts` | `SELECT ... WHERE r.kind='tls_setup'` output order | none | `ORDER BY r.start NULLS LAST, r.point, r.last_frame, r.id` |
| `transport.sql` | `transport_syn` | `arg_min(seq/raw_seq/shift, ts)` | `ts` | `frame` (struct key) |
| `transport.sql` | `transport_ack_first`, `transport_samples` | `arg_min(frame, ts)` | `ts` | `frame` (struct key) |
| `transport.sql` | `transport_retry_signals` | `arg_max(t.frame, t.ts)` / `arg_min(t.frame, t.ts)` | `t.ts` | `t.frame` (struct key) |
| `transport.sql` | `transport_reordering` | `QUALIFY row_number() OVER(PARTITION BY a.flow,e.direction,a.packet_key ORDER BY ...)=1` | `b.corrected, e.hop` | `a.frame, b.frame` |
| `transport.sql` | `losses` | `arg_max(hop, coalesce(recovery_ms,impact_ms))` | `coalesce(recovery_ms, impact_ms)` | `hop` (guarded by `CASE WHEN coalesce(...) IS NOT NULL`) |
| `transport.sql` | `signal_samples` | `ORDER BY ... LIMIT 6` | `zero_probe DESC, window_full DESC, gap_ms DESC NULLS LAST, ack_delay_ms DESC NULLS LAST` | `point, frame` |
| `transport.sql` | `retry_events` | `ORDER BY ... LIMIT 200` | `ts, frame` | `point` (between `ts` and `frame`) |
| `transport.sql` | `retry_reordering` | `ORDER BY ... LIMIT 200` | `time, hop, frame` | `point` (between `hop` and `frame`) |
| `transport.sql` | `proxy_service_pairs` | `ORDER BY ... LIMIT 100` | `complete` | `last_frame` |
| `Transport.java` | `profiles` signal evidence | `for p in {s["point"] for s in signal}` (unordered set) | hash-seed order | `TreeSet<String>` (lexicographical `point` order) |

Classification tie-break choice (contract §4a, 2026-10-16). Each candidate set of `recoveries`, `acked`,
`acked_replies`, `acked_resets` and `failures` is one capture point, so `frame` is unique there. Ordering ties by
`frame` reproduces the reference's one-thread choice on every corpus input of the four parity scripts (core,
det_a, det_b, det_c); the earlier `packet_key` key did not (`events.support_key` on `pilot`, `pilot_ipv6`,
`real_case_2`, `incident_replica`, `scenario_pilot`; e.g. `pilot` `a_rx` frames 151/152 at identical
`corrected=1700000018.000645`: reference and now Java take frame 151, `packet_key` order took 152). `first_event`
keeps `id, packet_key`: the reference takes the first tied row in `events` storage order. No value of the row
reproduces that order on all inputs (smallest `frame` at `point_a` matches 4 of the 13 tied groups in the
corpus and fails the 23-event groups of the proxy fixtures), and storage order itself depends on the thread
count, which §4a excludes. Its cases are listed as deliberate differences below.

Constructs already totally ordered by the reference (unique keys) and left unchanged: `span_duplicates`,
`occurrences`, `anchor_points`, `fragment_episodes`, `fragment_spans`, `fragment_groups` (`arg_min(raw_seq,
frame)`), `range_atoms`, `clock_range_candidates` / `calibration_pairs` (`hash(...)`, then both frames),
`jitter_noise`, `order_scores`, `evidence` (`corrected NULLS LAST, point, frame`), `nat_pairs` `ORDER BY samples
DESC, tuple_a, tuple_b`. `GROUP BY`/`DISTINCT` reads whose row order only decides the insertion order of an
intermediate table (`flow_filter_metadata`, `sequence_found` rows, `point_sessions`, `tcp_sessions`) need none.

## Not made deterministic: needs a method change (separate PO decision)

| File | Statement | Why |
|---|---|---|
| `matching.sql` | `before_candidates`, `after_candidates` | `ASOF LEFT JOIN anchors ... r.match_time>=a.match_time` (and `<=`): when two anchors of one base key share the same `match_time`, DuckDB picks either. An ASOF join has no tie-break clause; making it deterministic means replacing the ASOF join with a different join formulation. The later `nearest` / `assigned` orderings are deterministic for whatever candidates the ASOF join returns. Not observed on the synthetic corpus. |
| `transport.sql` | `transport_data`, `transport_window_trace` | `ASOF LEFT JOIN transport_acks ... s.ts>=w.ts`, `ASOF LEFT JOIN transport_ack_first ... s.seq_end<=a.ack_end`, and `ASOF LEFT JOIN transport_data ... w.ts>=d.ts`: if two rows in the right-hand table share the exact same `ts` (or `ack_end`) for a `(flow, point, direction)` partition, DuckDB's ASOF join has no tie-break clause. Replacing the ASOF joins with lateral/window joins would be a method change. Not observed on the synthetic corpus. |

## Other ordering facts

- `captures` is read without `ORDER BY` (report `prefilter_excluded`, inventory order): the order is the table's
  storage order, deterministic for a given project database; the frozen test fixtures keep the reference
  insertion order (`export_fixture.py` exports by `rowid`).
- Declared clock domains: the reference iterates a hash-ordered set of capture ids when it copies a domain model
  (`analysis/clocks._inherit_domains`), so the insertion order of `clocks` keys for domain members depends on the
  interpreter's string hash seed. The engine iterates the members sorted. Values are identical either way.
- All reference `sorted()` / `.sort()` calls are stable and are ported as stable sorts with the reference keys.

## Deliberate differences (threads=1, recorded in `tools/packet_analysis/parity/core_compare.py`, `det_b_compare.py` and `det_c_compare.py`)

| Scenario | Section / stage / table | Reference | Java | Cause |
|---|---|---|---|---|
| `offload` | `quality[0].evidence[2].byte_ranges`, `transport_export.items[0].evidence[7].byte_ranges` | ranges in DuckDB `list(DISTINCT)` order (e.g. element 12 = 81981..83441) | ranges ordered by `start_seq` (element 12 = 80521..81981) | `range_peers` tie-break |
| `realistic_capture_miss` | `findings[3].evidence` of the final report (first event of a hop's class; `findings[2]` of the core-only report) | frames 1225 / 1219 (IPv4 DNS query) | frames 1226 / 1220 (IPv6 DNS query at the same timestamp) | `first_event` tie-break |
| `udp_FW1_*`, `udp_FW2_*`, `udp_link_*`, `udp_transactions` | `rtp.one_way` | DuckDB hash-aggregate order (`lb_in` or `fw1_out` before `client`) | ordered by `point, flow` (`client` first) | `one_way` `ORDER BY point, flow` tie-break |
| `f5_peer_reuse` | `f5.pairs` | DuckDB `UNION` hash order (`stream=2, flowid="0x000000000000012c"` at `pairs[1]`) | ordered by `capture_id, stream, flowid, peerid, tmm, role, reason` (`stream=1, flowid="0x00000000000000c8"` at `pairs[1]`) | `f5_report_connections` outer `ORDER BY` tie-break |
| `proxy_zero_ipv4`, `proxy_constant_ipv4`, `proxy_cached_ipv4`; det-a `security`, `security_fw1`, `security_link`, `security_reply`, `onset_signals`, `session_case_v4`, `session_case_v6`, det-c `scenario_security` (`findings[*].id` counters only, e.g. `security` server frames 510/426/382: reference `:0`/`:1`/`:2`, Java `:2`/`:1`/`:0`; `scenario_security` shows a non-monotonic reference order, so no frame or time order reproduces it) | `integrity`, `group_downstream_findings`, `findings` | `endpoint_packets` emits frame 1214 before 1159 (no outer `ORDER BY` after `QUALIFY row_number()=1`), so the two `reset_origin` findings at `server` swap places and id counters | frame 1159 first (`ORDER BY corrected, frame` before `LIMIT`); same findings as a multiset (checked by the harness, "order only") | `integrity.sql` `endpoint_packets` outer `ORDER BY` |
| `proxy_zero_ipv4`, `proxy_constant_ipv4`, `proxy_zero_ipv6`, `proxy_cached_ipv4` | `group_downstream_findings[6].evidence` | `first_event` tie: 24 `unknown` events on `b_in`->`b_out` at `ts=1700000000.013` | `ORDER BY ts, id, packet_key LIMIT 1` | `classification.sql` `first_event` tie-break |
| `proxy_zero_ipv4`, `proxy_constant_ipv4`, `proxy_zero_ipv6`, `proxy_cached_ipv4` | `tls_checks`, `tls` (order only) | `facts` has no `ORDER BY` (`a_in`/`tls-9.example` frame 718 before `client`/`tls-10.example` frame 733) | `ORDER BY r.start NULLS LAST, r.point, r.last_frame, r.id` | `tls.sql` `facts` `ORDER BY` |
| `diagnostics`, `diagnostics_ipv6` | `transport_export.items[*].evidence` | signal evidence iterated in set hash-seed order (`{s['point'] for s in signal}`) | signal evidence iterated in lexicographical `point` order (`TreeSet<String>`) | `Transport.java` `profiles` signal point order |

All are choices between rows or set elements the reference treats as equal; no verdict, count, class or metric differs.

## Intentional divergences (behaviour, not ties)

These are not tie-breaks: they change a criterion because the reference is wrong on inputs the corpus does not
contain (`docs/design/PACKET_ANALYSIS_PORT_CONTRACT.md` §4, README "Intentional divergences from the reference").
Each one runs only on such inputs; every corpus case runs the reference statements unchanged, so `corpus_run.py`
needs no acceptance rule and still reports any difference as `DIFF`. The hop markers are a PO method decision rather
than a reference bug; they live in the expert report, which the reference does not have and the corpus gate does not
compare.

| Where | Reference | Java | Corpus |
|---|---|---|---|
| `NeighbourScope`, `sql/neighbour_scope.sql` (called from `Preparation.restrictIndexScope`, `Segments`, `Headlines`, `Timeseries`, `CoreReport`) | a client-tuple flow seen at one capture point stays in the index scope, and every hop compares all packets of A with B, also for flows B's capture never saw (they become capture misses and impactful/unrecovered loss) | a flow B never observed, with packets proving it crossed B (same direction after B, or non-reset payload/ACK replies after B on the reply path), is outside B's capture scope: dropped from the index scope when single-point and out of scope on every hop that assesses it, otherwise left out of that hop's events and denominators; deterministic (flows ordered, `hop_unobserved ... ORDER BY a.flow`) | no case changed status (run 2026-10-17, 206 cases, threads 1 and 4) |
| `neighbour_scope.sql` `analysis_scope_update` | `selection.analysis_scope_update` updates `retained_frames` / `excluded_single_point_frames` only; `excluded_single_point_flows` keeps the host-scope count | `excluded_single_point_flows = index_scope.input_flows - retained_flows`, consistent with the frames | identical on every case (restrict excludes no flow there) |
| `export/HopVerdict`, `ExpertReportBuilder.readHopMarks` (expert report only; PO decision 2026-10-18) | no expert report and no hop verdict at all | hop markers `gecikme burada` / `temiz` by the approved rule; the before-window median and the late-sample counts are integer queries (twice the median = the sum of the two middle integer samples, compared with `2 x sample`), every mean, ratio and share comparison is exact integer arithmetic, so the workbook is byte-identical across DuckDB threads | report and FindingsExport identical on every case (run 2026-10-18, 206 cases, threads 1 and 4); the markers are outside the compared sections and are evaluated against ground truth instead (README "Intentional divergences") |

## Time and event detectors (lane A)

Scope: `timeseries.sql`, `security.sql`, `onset.sql`, `microbursts.sql`, `host_path.sql`, `cycles.sql`,
`delivery.sql` (delivery report part), `hold_release.sql`, `aggregates.sql`, `incident.sql`, `session_events.sql`,
`pilot.sql`, `device_drops.sql` (the transport and retransmission SQL is lane B's `transport.sql`). Same rule as above: keys only appended after the
reference ordering, among rows the reference treats as equal. Struct keys are guarded where the reference key
can be NULL (`CASE WHEN k IS NOT NULL THEN (k, ...) END`), because a struct with a NULL field is itself not NULL
and `arg_min`/`arg_max` would otherwise stop ignoring the row.

| File | Statement | Construct | Reference ordering | Appended keys |
|---|---|---|---|---|
| `security.sql` | `session_syns`, `session_replies`, `session_acks`, `session_lifecycle` | `arg_min(frame/seq, corrected)` | `corrected` | `frame` (struct key) |
| `security.sql` | `lifecycle_points` | `arg_min(syn_frame,start)` | `start` | `syn_frame` |
| `security.sql` | `handshake_stops`, `segment_drops` | `arg_min(packet_key,ts)` | `ts` | `packet_key` |
| `security.sql` | `failures` | `row_number() OVER(... ORDER BY time)`, `ORDER BY sample_order,time LIMIT 200` | `time`; `sample_order,time` | `flow`; `point_a,point_b,direction,flow` |
| `security.sql` | `scan_candidates` | `ORDER BY start,point LIMIT 200` | `start,point` | `source` |
| `security.sql` | `positive_proofs` | `ORDER BY e.ts LIMIT 16` | `e.ts` | `p.proof_id` |
| `onset.sql` | `first_event` | `ORDER BY ts LIMIT 1` | `ts` | `id,packet_key` |
| `onset.sql` | `max_pair` | `ORDER BY time_b-time_a DESC LIMIT 1` | transit | `time_a,key_a,key_b` |
| `microbursts.sql` | `microburst_windows`, `microbursts`, `points` | `arg_min(frame,ts)`, `arg_max(frame,ts)`, `arg_max(frame,bps)` | `ts` / `bps` | smallest `frame` among ties (struct key `(k,frame)` / `(k,-frame)`) |
| `microbursts.sql` | `items` | `ORDER BY peak_mbps DESC,point,start LIMIT 200` | as given | `episode` |
| `host_path.sql` | `host_path_series`, `streams`, `direction_series` | `arg_max(key_a/key_b/hop, delay_ms)` | `delay_ms` | one shared struct `(delay_ms,key_a,key_b[,hop])` so the keys come from one row |
| `host_path.sql` | `service`, `turnaround`, `streams` | `GROUP BY` output order (report lists, `next()` picks) | none | `ORDER BY` the group keys |
| `host_path.sql` | `host_bursts`, `service`, `turnaround` | `arg_min`/`arg_max` over `ts` / `last_frame` | as given | `frame` / the chosen frame |
| `cycles.sql` | `cycle_points` bursts | `arg_min`/`arg_max` over `local_time` | `local_time` | `frame` (smallest frame among ties) |
| `cycles.sql` | `cycle_top_points` | `ORDER BY t.max_ms DESC,t.cycle_id,point` | as given | `p.request_frame` |
| `cycles.sql` | `series_rows`, `period_rows`, `pair_rows`, `host_local` | report `ORDER BY ... LIMIT` | as given | `port`; `port,reason`; `cycle_id`; `point,flow,port` |
| `cycles.sql` | `cycle_degradations` | `arg_max(point_a/point_b/frame_a/frame_b, added_ms)` | `added_ms` | one shared struct `(added_ms,cycle_id,frame_a,frame_b)` |
| `cycles.sql` | `degradation_rows` | `ORDER BY max_ms DESC,hop,flow LIMIT 100` | as given | `component` |
| `delivery.sql` | `delivery_series` | `arg_max(c.frame_b,c.time_b)` | `time_b` | `frame_b`, largest (NULL-guarded) |
| `delivery.sql` | `substitutions` | `arg_min(frame_a/frame_b,time_a)` | `time_a` | shared `(time_a,frame_a,frame_b)` (NULL-guarded) |
| `delivery.sql` | `series_rows` | `ORDER BY start,point_a,point_b LIMIT 20000` | as given | `flow,direction` |
| `hold_release.sql` | `trains` | `ORDER BY a.span DESC LIMIT 100` | `a.span` | `a.flow,a.seq,a.length,b.length` |
| `aggregates.sql` | `aggregate_added` | `list(struct_pack(...))` element order (`max_added` evidence order) | none | `ORDER BY component,hop,point_a,point_b,frame_a,frame_b` |
| `aggregates.sql` | `best_added`, `silence` | `ORDER BY ... LIMIT 1` | as given | `host`; `frame` |
| `incident.sql` | `timeline` retrans | `arg_min(frame,coalesce(corrected,ts))` | time | `frame,point` |
| `incident.sql` | `tx_keys`, `quality`, `delivery_rows`, `top_pairs` | `GROUP BY` / `ORDER BY` ties | as given | the group keys (`direction`; `cycle_id,point_a,point_b,frame_a,frame_b`) |
| `incident.sql` | `cycle_flow_detail` | `list(struct_pack(hop,component,p95))` element order (`per_hop_added`) | none | `ORDER BY hop,component` |
| `session_events.sql` | `lifecycle`, `resets`, `storm_details` | `arg_min`/`arg_max` over `t` / `syn_t` | as given | `frame` / `flow` |
| `session_events.sql` | `lifecycle`, `resets`, `flows_by_volume` | `ORDER BY f.first_t`, `ORDER BY r.direction,r.t`, `ORDER BY greatest(...) DESC` | as given | `flow`; `frame`; `flow` |
| `pilot.sql` | `handshake_rtt` | `arg_min(ms, s.ts)` | `s.ts` | `s.frame,sa.ts,sa.frame` |
| `device_drops.sql` | `unplaced_groups`, `unplaced_frame` | `GROUP BY` output order (segment/finding order); `ORDER BY e.ts,p.frame LIMIT 1` | none; as given | `ORDER BY point_a,direction`; `p.proof_id` |

Left unchanged: `session_events.sql` `storm_details` `mode(o.ttl)` (a tie between two TTL values of one flow
and direction is possible in principle; DuckDB keeps the first value reaching the top count; changing it would
need an ordered-set reformulation, listed as "needs a method change" only if it is ever observed).
`sess_flows` `any_value(src/sport/dst/dport) FILTER(WHERE direction='forward')` takes one value of a column that
is constant per flow and direction.

Deliberate differences recorded in `tools/packet_analysis/parity/det_a_compare.py`: `incident` /
`cycles.flow_ports[*].per_hop_added` element order and the tied rows of `delivery.series`, `incident.delivery`,
`incident.quality` are compared after sorting by the Java key (a refinement of the reference order).

Deliberate differences recorded in `det_a_compare.py` (`DELIBERATE`, `DELIBERATE_TABLES`; `det_c_compare.py`
reuses them, `scenario_pilot` with `pilot`'s entries):

| Scenario | Section / table | Reference | Java | Cause |
|---|---|---|---|---|
| `pilot`, `pilot_ipv6`, `pilot_healthy`, `scenario_pilot` | `delivery.series[473..474].evidence_frame_b`, `incident.timeline/delivery/quality[*].evidence`, tables `delivery_series`, `adjacent_match_rates` | frame 453 | frame 454 | `delivery_series` `arg_max(c.frame_b, c.time_b)`: two copies share `time_b`; the pilot family takes the smaller frame, `duplicate` the larger, so no frame order reproduces both (Java keeps the largest) |
| `real_case_2`, `incident_replica` | `delivery.series[*].evidence_frame_b` (5 / 85 rows), tables as above | smaller frame (e.g. 4124, 349) | next frame (4125, 350) | same `delivery_series` tie |
| `real_case_2`, `incident_replica` | `host_path.items[0]`, `incident.timeline`, table `host_path_series` (`evidence_key`) | first scanned key | `key_a, key_b` order | `host_path` `arg_max(key_a, delay_ms)` tie |
| `pilot`, `pilot_healthy`, `pilot_ipv6`, `real_case_2`, `incident_replica`, `scenario_pilot` | `sessions.points[*]` (`first_frame` 2 vs 1) | first scanned SYN | smallest `syn_frame` | `security` `lifecycle_points` `arg_min(syn_frame, start)` tie |

## Export and compare (step S15)

Scope: `field_diff.sql`, `export.sql`, `engine_export.sql` and `com.securityexpert.nexus.ui2.packetanalysis.export`. Same rule as above: keys only appended after the reference ordering, among rows the reference treats as equal.

| File | Statement | Construct | Reference ordering | Appended keys |
|---|---|---|---|---|
| `field_diff.sql` | `matched_pairs` | `ORDER BY ... LIMIT ?` | `a.corrected, a.frame` | `b.frame` (several frames of the later point can carry the same `packet_key`) |
| `engine_export.sql` | `per_second` | `ORDER BY ... LIMIT ?` | `bucket, point, flow` | `port` |
| `engine_export.sql` | `flow_port` | `ORDER BY ... LIMIT ?` | `p95_ms DESC` | `NULLS LAST, point, flow, port` |
| `engine_export.sql` | `periods` | `ORDER BY ... LIMIT ?` | `start, point, flow` | `port, reason` |
| `engine_export.sql` | `rx_tx_components` | `ORDER BY ... LIMIT ?` | `point_a, point_b, start` | `frame_a, frame_b, cycle_id` |
| `engine_export.sql` | `capture_points` | `GROUP BY ALL ORDER BY o.point` | `o.point` | `o.capture_id` |
| `engine_export.sql` | `match_miss` | `GROUP BY ALL ORDER BY point_a, point_b, flow` | `point_a, point_b, flow` | `direction` |

There is one FindingsExport implementation: `Export.exportData` with `FieldDiff.fieldDiffs`, the path the neXus service
stores and serves (`CaseRunner.prepareAndExport`); `PacketAnalysisMain` writes `findings-export.json` through the same
call, so the corpus below verifies the shipped code. The single field-difference tie-break above is the only one in
that path.

`ExportAndFieldDiffTest.sameInputSameExportAcrossThreadCounts` verifies that `Export.exportData`, `Export.htmlReport` (with fixed timestamp) and `EngineExport.exportTables` (`csv` and `xlsx`) produce byte-identical outputs across `threads=1` and `threads=4`.

## End-to-end pipeline (`PacketAnalysisMain`, `tools/packet_analysis/parity/corpus_run.py`)

The corpus runner runs the whole Java pipeline (ingest, topology / case setup, engine, FindingsExport through the
product path `CaseRunner.prepareAndExport` -> `Export.exportData`) on all 206
frozen corpus cases at DuckDB threads 1 and 4 and requires identical strict output (capture ids named, other
32-hex ids masked, list order, key order and number types kept). Four sources of run-to-run variation showed up
there and got a deterministic choice; none changes a comparison, criterion, threshold or formula.

| Where | Construct | Reference behaviour | Java |
|---|---|---|---|
| `host_path.sql` `host_path_series`, `streams`, `direction_item` | `sum(greatest(0,delay_ms-uncertainty_ms)*bytes)` over DOUBLE (`delay_byte_ms`, and `delay_share_percent` from it) | summation order = scan order of `host_transit` (join output order), so the last bits change with the thread count | `sum(... ORDER BY time,key_a,key_b,delay_ms,bytes)`: one fixed summation order. At threads=1 the value can differ from the reference in the last digit (e.g. `8629284.271959316` vs `...318`); identical in the canonical 9-digit form, so no corpus case changes status |
| `topology/sql/suggest_stage_pairs.sql` | `ORDER BY spread_us LIMIT 200` | stage pairs with equal `spread_us` (e.g. `oct8_replica` `a_rx/b_rx` and `a_tx/b_tx`, 34.09 us) in hash order: `suggested_topology.inferences` order | `ORDER BY spread_us,a,b` |
| `topology/sql/order_pair_stats.sql` (path-order inference evidence) | `arg_min(a.frame, abs(delta))`, `arg_min(b.frame, abs(delta))` | many pairs share the minimum (delta 0 on `oct8_replica`): the frames come from whichever row the join scans first, and the two `arg_min` may even pick different rows | one struct key `(abs(delta), a.frame, b.frame)` for both: smallest frames of one row. The reference's threads=1 choice is scan order, which no row value reproduces (`oct8_replica`: reference frames 249/247 among zero-delta rows from frame 1 on, Java 1/1): a deliberate difference, accepted by `corpus_ties.py` |
| `ingest/BatchIngest` parallel workers, `CaptureIngest.Turn` | capture registration (first `captures` row write, capture id choice) in worker completion order | `captures` storage order (read without `ORDER BY`: report `prefilter_excluded`, inventory order) and counter capture ids follow scheduling with 4 workers | registrations are chained in input order (a file waits only for its predecessor's registration, not its ingest); storage order and ids follow the input order, as the reference's sequential ingest has them |

### Recorded tie-breaks observed on corpus inputs the lanes did not run

`corpus_run.py` accepts a section difference as RECORDED-TIES only for a recorded (scenario, section) pair and only
when the two sections are identical once the tied selection (evidence lists, evidence frames / keys, first / request
/ response frames, integrity id counters) is masked (`corpus_ties.py`). The lane tables above are reused as they are
(mapped to corpus scenarios: `cli-<scenario>--<variant>` -> scenario, `dns_diagnosis` -> `diagnostics`,
`scenario_*` / `*_ipv6` lane names -> the scenario). The same mechanisms on further inputs:

| Corpus cases | Section(s) | Mechanism (recorded above) |
|---|---|---|
| `integrity_all`, `oct8_replica`, `fifty_flow_replica` | `sessions.points[*]` (first SYN frame and its evidence) | `security` `lifecycle_points` `arg_min(syn_frame, start)` |
| `fifty_flow_replica`, `cli-duplicate--rounds-40` | `delivery.series[*].evidence_frame_b`, `incident` evidence | `delivery_series` `arg_max(c.frame_b, c.time_b)` |
| `fifty_flow_replica` | `host_path` evidence (`flow_details[*].evidence`) | `host_path` `arg_max(key_a, delay_ms)` |
| `cli-onset_delay` (all five variants) | `onsets.items[*].evidence`, `segments[*].onsets[*].evidence` | onset `max_pair` `ORDER BY time_b-time_a DESC LIMIT 1` (constant 30 ms delay: every pair ties) |
| `cli-delay--rounds-40` | `cycles.top[*].evidence` | `cycle_top_points` `request_frame` |
| `oct8_replica` | `suggested_topology.inferences[path_order].evidence` | `order_pair_stats` tie-break (table above) |

Not changed (no difference observed on the corpus at threads 1 and 4): topology `cidrs_flow_exemplars`
(`arg_min(x, p.ts)` per column), `cidrs_syn_direction` (`ORDER BY p.ts, p.frame` across captures) and
`suggest_host_captures` (`GROUP BY capture_id` output order).

## Host telemetry in the expert report (stage B)

The host-telemetry parsers (`...packetanalysis.telemetry`) and the Sunucu Korelasyonu sheet add no DuckDB query: their
input is the uploaded text files and the alignment the user confirmed, so the rules are about ordering and arithmetic.

* Timestamps are integer milliseconds. A zone-less wall clock becomes an instant through `ZoneRules.getOffset` (in a gap or an
  overlap of the zone it returns the offset before the transition, fixed by the rules), then the user's clock offset in
  whole seconds. A year-less syslog stamp takes the year, among previous/current/next, nearest the capture start; a tie is
  resolved by the smaller year.
* Samples are placed in time order, ties by file order (a stable sort). A gauge shows the last sample of a bucket, a counter
  the sum of its non-negative increases, a count the sum. Doubles are added in that fixed order.
* Files are processed by (display name, id); the busiest interfaces and the devices with the largest counter increase are
  chosen by (total descending, name ascending); syslog program tags by (count descending, name ascending); the files'
  `H<n>` codes follow that order, so the order of attachment never shows.
* No `HashMap` iteration order reaches the output: maps that are printed are `TreeMap`s or are sorted before use.
* `HostCorrelationTest.sameInputGivesByteIdenticalWorkbooksAcrossRunsThreadsTimeZonesAndLocales` pins this at DuckDB threads 1
  and 4, with the attachments reversed, and under another JVM time zone and locale.

## Resource guardrails (memory plan, spill guard, analysis window guard)

A guardrail never changes a result; it can only let a run finish, spill, or stop with a reason.

- **Memory and spill** (`store/MemoryPlan`, `store/SpillGuard`): DuckDB `memory_limit`, `temp_directory`,
  `max_temp_directory_size` and the watchdog decide whether a run completes, spills to the analysis volume or ends
  `FAILED` with `ANALYSIS_MEMORY_LIMIT`. They are not inputs of any query. DuckDB threads are covered by the thread
  invariance above.
- **Analysis window** (`topology/AnalysisWindowGuard`): within budget (no capture has more than
  `packetanalysis.window.max_packets_per_capture`, default 150,000, packets in the analysis range) the topology is
  untouched and the engine runs exactly as before; the report has no `analysis_window` key (the JUnit corpus and the
  end-to-end fixtures compare equal to the frozen outputs, and `run.json` says `mode: full`). Over budget, the window
  is a function of the stored packet timestamps and the budget only (same input, same window: widest width whose
  busiest capture fits, most captures covered, earliest start), it is written into the topology `start`/`end` and into
  the report as `analysis_window` with `reason: budget`. A topology that has its own `start`/`end` is never changed.
