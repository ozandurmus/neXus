# Packet Analysis — numeric and formatting inventory of the frozen PacketBreaker engine

Status: reference inventory for the Java port (`docs/design/PACKET_ANALYSIS_PORT_CONTRACT.md`). Scanned from the
frozen PacketBreaker sources (`src/packetbreaker`, revision `adcf962`, CPython 3.12.8) by
`tools/packet_analysis/numeric/scan_inventory.py`; regenerate the tables below rather than editing them by hand
(see `README.md` in this module). The scan is an AST pass, so every Python call, operator and f-string format spec is
counted; SQL text inside string literals runs in DuckDB and is listed only for reference.

## How each construct is ported

| Python construct | Trap | Java helper (`...packetanalysis.numeric`) |
|---|---|---|
| `statistics.median` (10) | odd length returns the element itself (an int stays an int), even length `(a+b)/2`; sorted with CPython's sort | `PyStatistics.median(double[])`, `median(List<PyNum>)` |
| `statistics.quantiles(n=100\|20, method="inclusive")` (7) | `divmod(i*(ld-1), n)` interpolation, exact for ints; `StatisticsError` below 2 points | `PyStatistics.quantiles(..., n, Method.INCLUSIVE)` (EXCLUSIVE also ported) |
| `round(x)` (6) / `round(x, n)` (7) | half-to-even on the exact binary value (`round(2.675, 2) == 2.67`), signed zero kept, OverflowError | `PyRound.round(double)`, `round(double, int)`, `round(long, int)` |
| f-string `:.1f :.2f :.3f :.9f` (42), `:.1%` (3), `:g` (3) | digits from the exact value, half-even (`f"{0.125:.2f}" == "0.12"`); `String.format` rounds half-up and follows the default locale (Turkish: decimal comma) | `PyFormat.format(double\|long\|PyNum, spec)` |
| f-string `:02d :03d :04d :05d :03 :x :04x :,` (14) | zero padding, hex, grouping | `PyFormat.format(long, spec)` |
| `str(x)` of floats, `json.dumps` floats (89 + 83) | shortest round-trip repr in Python layout (`1e-05`, `100.0`, `1e+16`, `5e-324`); JSON `NaN`/`Infinity`; `allow_nan=False` raises | `PyFloatRepr.repr`, `json`, `jsonStrict` |
| `sum()` (43) | CPython 3.12 Neumaier-compensated float sum; ints after the first float added uncompensated; `sum([-0.0]) == 0.0` | `PyBuiltins.sum(double[])`, `sum(List<PyNum>)` |
| `min()` / `max()` (53 / 73, plus `key=`/`default=`) | first extreme kept, strict `<`/`>` only: `max(0.0, -0.0)` is `0.0`, `min(1.0, nan)` is `1.0`; `Math.min/max` differ | `PyBuiltins.min/max(double...)`, `min/max(List<PyNum>)`; `key=` forms: `Collections.max` with a comparator keeps the first maximum too |
| `sorted()` / `.sort()` (55 + 16 `key=` + 8 + 1 `reverse=`) | stable; only `<` is used; NaN makes the order algorithm-dependent; `reverse=True` keeps equal elements in original order | `PyTimSort` (line-by-line CPython 3.12 listsort); for NaN-free keys any stable Java sort is equivalent |
| `a % b` (63), `a // b` (18), `divmod` (2) | floor semantics: `-len(x) % 4`, `(a - b) % 65536`, `% 4294967296` with negative left side; Java `%` and `/` truncate | `PyMath.mod`, `floorDiv`, `divmod` (long and double) |
| `a / b` (83) | int/int is correctly rounded beyond 2^53; division by `0.0` raises | `PyMath.trueDivide(long,long)`, `trueDivide(double,double)`, `PyArith.trueDiv` |
| `math.floor` / `math.ceil` (2 / 3), `int(float)` | return ints; NaN raises ValueError, inf OverflowError | `PyMath.floor`, `ceil`, `trunc`; `PyConvert.toInt` |
| `math.isfinite` (6) | none | `Double.isFinite` |
| `float(str)` (10) | Python grammar (underscores, `inf`/`nan` any case, whitespace), not Java's (hex, `1d`) | `PyConvert.parseFloat` |
| `int(str)`, `int(str, 0\|16)` (65 `int(...)` sites, most on tshark strings) | `0x` prefixes, underscores, whitespace, base-0 leading-zero rule | `PyConvert.parseInt(String[, base])` |
| `datetime.fromtimestamp(round(ts, 3), utc)` (1) | microseconds rounded half-even from the float fraction | `PyTime.fromTimestampMicros`; `isoformat(timespec="milliseconds")` truncates to ms — a later stage with java.time |
| int/float mixing everywhere | `median([1, 3])` is `2.0` but `median([1, 2, 3])` is `2`; int vs float comparisons are exact | `PyNum` = `PyInt` \| `PyFloat`, `PyArith` |

Not used by PacketBreaker and therefore not ported: `statistics.mean/fmean/pstdev/stdev/mode`, `math.fsum`, other
`math.*` functions (only `floor`, `ceil`, `isfinite` appear). `Decimal(stamp) * 10**9` in `fortinet.py` (Fortinet
text import) is exact decimal arithmetic: port it with `BigDecimal` when that importer is ported. The JSON writer
itself (string escaping, `ensure_ascii`, `indent=2`, `separators`, key order) is outside this layer; only number
text is covered here.

Python ints are unbounded; `PyInt` holds a `long` and raises `ArithmeticException` where CPython would return a
wider int (statistics interpolation uses `BigInteger` internally, so it never overflows). No PacketBreaker value
observed needs more than 64 bits (sequence numbers are reduced `% 2**32`).

## Inventory

| Construct | Sites |
|---|---:|
| `str(x)` | 89 |
| `a / b (true division)` | 83 |
| `max()` | 73 |
| `int(expr)` | 65 |
| `a % b (modulo)` | 63 |
| `sorted()` | 55 |
| `min()` | 53 |
| `sum()` | 43 |
| `json.dumps()` | 37 |
| `json.dumps(indent)` | 30 |
| `f-string :.3f` | 20 |
| `a // b` | 18 |
| `sorted(key)` | 16 |
| `float(expr)` | 10 |
| `statistics.median` | 10 |
| `f-string :.1f` | 9 |
| `.sort(key)` | 8 |
| `json.dumps(separators)` | 8 |
| `f-string :.2f` | 7 |
| `max(default)` | 7 |
| `round(x, n)` | 7 |
| `f-string :.9f` | 6 |
| `math.isfinite` | 6 |
| `round(x)` | 6 |
| `statistics.quantiles(n=100, method="inclusive")` | 6 |
| `datetime.isoformat` | 5 |
| `max(key)` | 5 |
| `f-string :02d` | 4 |
| `f-string :.1%` | 3 |
| `f-string :g` | 3 |
| `f-string :x` | 3 |
| `json.dumps(sort_keys)` | 3 |
| `math.ceil` | 3 |
| `divmod()` | 2 |
| `f-string :04d` | 2 |
| `math.floor` | 2 |
| `min(default)` | 2 |
| `min(key)` | 2 |
| `Decimal()` | 1 |
| `datetime.fromtimestamp` | 1 |
| `f-string :,` | 1 |
| `f-string :03` | 1 |
| `f-string :03d` | 1 |
| `f-string :04x` | 1 |
| `f-string :05d` | 1 |
| `json.dump(indent)` | 1 |
| `json.dumps(allow_nan)` | 1 |
| `json.dumps(allow_nan,ensure_ascii,indent)` | 1 |
| `json.dumps(allow_nan,ensure_ascii,separators)` | 1 |
| `json.dumps(ensure_ascii,indent)` | 1 |
| `sorted(key,reverse)` | 1 |
| `statistics.quantiles(n=20, method="inclusive")` | 1 |
| `str.format` | 1 |
| `DuckDB SQL quantile_cont()` (runs in DuckDB, not ported) | 35 |
| `DuckDB SQL median()` (runs in DuckDB, not ported) | 23 |
| `DuckDB SQL floor()` (runs in DuckDB, not ported) | 20 |
| `DuckDB SQL ceil()` (runs in DuckDB, not ported) | 2 |
| `DuckDB SQL avg()` (runs in DuckDB, not ported) | 1 |
| `DuckDB SQL stddev_pop()` (runs in DuckDB, not ported) | 1 |

Python files scanned: 69. Python sites: 789. SQL text sites: 82.

### True division sites per file (individual rows omitted: 83 sites, all plain `/` handled by `PyMath.trueDivide` / `PyArith.trueDiv`)

| File | Sites |
|---|---:|
| `aggregates.py` | 1 |
| `analysis.py` | 9 |
| `anonymize.py` | 1 |
| `app.py` | 1 |
| `batch_ingest.py` | 1 |
| `clock.py` | 7 |
| `device_drops.py` | 1 |
| `diagnostic_synthetic.py` | 1 |
| `dns.py` | 2 |
| `fortinet.py` | 1 |
| `fragments.py` | 1 |
| `headlines.py` | 5 |
| `hold_release.py` | 1 |
| `host_path.py` | 9 |
| `incident.py` | 1 |
| `ingest.py` | 1 |
| `integrity.py` | 4 |
| `matching.py` | 3 |
| `microbursts.py` | 2 |
| `onset.py` | 8 |
| `pilot.py` | 2 |
| `pilot_synthetic.py` | 3 |
| `proxy_analysis.py` | 1 |
| `proxy_resets.py` | 1 |
| `reference.py` | 1 |
| `sip.py` | 1 |
| `synthetic.py` | 1 |
| `timeseries.py` | 7 |
| `translation_synthetic.py` | 2 |
| `transport.py` | 2 |
| `udp.py` | 1 |
| `voip_synthetic.py` | 1 |

### Site list

| File:line | Construct | Source |
|---|---|---|
| `aggregates.py:158` | `max(default)` | `max((r["max_silence_ms"] for r in h["silence"]), default=None)` |
| `analysis.py:63` | `sorted(key)` | `sorted(topology.points, key=lambda p: p.host_capture)` |
| `analysis.py:151` | `json.dumps()` | `json.dumps( dict( adapter="paloalto", stage=point.vendor_stage, device=point.device, stage_source="user_file_t...` |
| `analysis.py:175` | `sorted()` | `sorted( { point for a, b in zip(topology.forward, topology.forward[1:]) if points[a].device == points[b].devic...` |
| `analysis.py:208` | `sum()` | `sum(x["tuple_a"] == item["tuple_a"] or x["tuple_b"] == item["tuple_b"] for x in found)` |
| `analysis.py:364` | `sorted(key)` | `sorted(members, key=lambda c: (c not in topology.clock_overrides, c != ref, c))` |
| `analysis.py:401` | `sorted(key)` | `sorted(order, key=lambda point: abs(order.index(point) - order.index(b)))` |
| `analysis.py:440` | `max()` | `max(models[aid].uncertainty, shared_bound)` |
| `analysis.py:477` | `sorted(key)` | `sorted(data, key=lambda row: row[1] - models[aid].correct(row[0]))` |
| `analysis.py:524` | `min()` | `min(members)` |
| `analysis.py:570` | `max()` | `max(model.timestamp_jitter, noise)` |
| `analysis.py:627` | `max()` | `max(x["start"] for x in coverage)` |
| `analysis.py:628` | `min()` | `min(x["end"] for x in coverage)` |
| `analysis.py:708` | `max()` | `max(0.001, tolerance_ms)` |
| `analysis.py:762` | `sum()` | `sum(r["count"] for r in q)` |
| `analysis.py:763` | `sum()` | `sum(r["count"] for r in q if r["reason"] == "eligible")` |
| `analysis.py:772` | `sum()` | `sum(time_bad.values())` |
| `analysis.py:775` | `min()` | `min(current, temporal_ratio)` |
| `analysis.py:786` | `min()` | `min(ratios)` |
| `analysis.py:789` | `sorted()` | `sorted(excluded_counts.items())` |
| `analysis.py:794` | `f-string :.1f` | `100 * (1 - eligible_ratio) -> :.1f` |
| `analysis.py:969` | `sum()` | `sum( x["count"] for x in classes if x["kind"] in ( "recovered_loss", "impactful_loss", "unrecovered_loss", "ha...` |
| `analysis.py:1038` | `sum()` | `sum( x["missing_bytes"] or 0 for x in classes if x["kind"] in ("recovered_loss", "impactful_loss", "unrecovere...` |
| `analysis.py:1132` | `.sort(key)` | `findings.sort(key=lambda f: (rank[f["severity"]], f["time_range"][0]))` |
| `analysis.py:1254` | `datetime.isoformat` | `datetime.now(timezone.utc).isoformat()` |
| `analysis.py:1490` | `.sort(key)` | `items.sort(key=lambda f: order.get(f["id"], len(order)))` |
| `anonymize.py:102` | `str(x)` | `str(path)` |
| `anonymize.py:106` | `sorted()` | `sorted((HEADERS - {"sll2", "loop"}) \| {"frame", "tcp", "udp", "icmp", "icmpv6"})` |
| `anonymize.py:141` | `int(expr)` | `int(element.get("pos", "0"))` |
| `anonymize.py:141` | `int(expr)` | `int(element.get("size", "0"))` |
| `anonymize.py:149` | `min()` | `min(end, len(old))` |
| `anonymize.py:153` | `min()` | `min(i + 2, b)` |
| `anonymize.py:154` | `min()` | `min(i + 2, b)` |
| `anonymize.py:177` | `min()` | `min(following)` |
| `anonymize.py:177` | `min()` | `min(size, min(following) - pos)` |
| `anonymize.py:180` | `int(expr)` | `int(field.get("show"))` |
| `anonymize.py:200` | `int(expr)` | `int(field.get("show"))` |
| `anonymize.py:213` | `max()` | `max(b for _, b in keep)` |
| `anonymize.py:240` | `int(expr)` | `int(p.find("field[@name='ip.hdr_len']").get("show"))` |
| `anonymize.py:244` | `int(expr)` | `int(net.find("field[@name='ip.len']").get("show"))` |
| `anonymize.py:248` | `int(expr)` | `int(net.find("field[@name='ipv6.plen']").get("show"))` |
| `anonymize.py:265` | `a % b (modulo)` | `-len(value) % 4` |
| `anonymize.py:288` | `int(expr)` | `int(length.get("show"))` |
| `anonymize.py:318` | `a % b (modulo)` | `size % 4` |
| `anonymize.py:331` | `a % b (modulo)` | `-len(raw) % 4` |
| `anonymize.py:400` | `f-string :03` | `index -> :03` |
| `anonymize.py:404` | `json.dumps(indent)` | `json.dumps( dict( files=results, note="Payload removed. Content-dependent HTTP/TLS/SIP/DNS/RTP results are unk...` |
| `app.py:79` | `str(x)` | `str(exc)` |
| `app.py:81` | `str(x)` | `str(exc)` |
| `app.py:123` | `str(x)` | `str(exc)` |
| `app.py:187` | `str(x)` | `str(exc)` |
| `app.py:193` | `str(x)` | `str(project.path)` |
| `app.py:211` | `str(x)` | `str(project.path)` |
| `app.py:241` | `str(x)` | `str(i)` |
| `app.py:285` | `str(x)` | `str(destination)` |
| `app.py:366` | `str(x)` | `str(directory / "anonymized-captures")` |
| `app.py:403` | `json.dumps(indent)` | `json.dumps(reference_template(saved), indent=2)` |
| `app.py:438` | `str(x)` | `str(exc)` |
| `app.py:498` | `str(x)` | `str(exc)` |
| `app.py:527` | `str(x)` | `str(exc)` |
| `application_evidence.py:20` | `sorted()` | `sorted({(p["point"], p["frame"]) for p in packets if p and p.get("frame")})` |
| `application_evidence.py:44` | `max()` | `max(stamps)` |
| `application_evidence.py:44` | `min()` | `min(stamps)` |
| `application_index.py:44` | `int(expr)` | `int(str(value), 0)` |
| `application_index.py:44` | `int(expr)` | `int(value)` |
| `application_index.py:44` | `str(x)` | `str(value)` |
| `application_index.py:44` | `str(x)` | `str(value)` |
| `application_index.py:53` | `str(x)` | `str(ipaddress.ip_address(token.strip()))` |
| `application_index.py:135` | `float(expr)` | `float(stamp)` |
| `application_index.py:162` | `float(expr)` | `float(value)` |
| `application_index.py:183` | `str(x)` | `str(raw_length[0])` |
| `application_index.py:188` | `a // b` | `len(body.replace(":", "")) // 2` |
| `application_index.py:211` | `a // b` | `len(body.replace(":", "")) // 2` |
| `application_index.py:289` | `f-string :.9f` | `inventory['start'] - 1e-6 -> :.9f` |
| `application_index.py:290` | `f-string :.9f` | `inventory['end'] + 1e-6 -> :.9f` |
| `application_index.py:299` | `json.dumps()` | `json.dumps(stage[0])` |
| `application_index.py:301` | `json.dumps()` | `json.dumps(stage[1:])` |
| `application_index.py:303` | `json.dumps()` | `json.dumps("e" if stage == "o" else "E")` |
| `application_index.py:305` | `json.dumps()` | `json.dumps(point.vendor_interface)` |
| `application_index.py:372` | `json.dumps(sort_keys)` | `json.dumps( [ capture[1], point.model_dump( exclude={"x", "y", "label", "device", "kind", "translation", "payl...` |
| `application_index.py:408` | `json.dumps()` | `json.dumps(state_buffer)` |
| `application_index.py:419` | `str(x)` | `str(source)` |
| `application_index.py:431` | `str(x)` | `str(inventory.get('checkpoint_uuid', False))` |
| `application_index.py:443` | `sorted()` | `sorted({tuple_filter(*t, True) for t in tuples if t[0] in ("TCP", "UDP")})` |
| `application_index.py:462` | `str(x)` | `str(source)` |
| `application_index.py:466` | `str(x)` | `str(selected)` |
| `application_index.py:474` | `str(x)` | `str(inventory["frame_limit"])` |
| `application_index.py:490` | `str(x)` | `str(selected)` |
| `application_index.py:526` | `json.dumps()` | `json.dumps(event["metadata"])` |
| `application_index.py:577` | `sorted()` | `sorted(ordinals)` |
| `application_index.py:610` | `json.dumps()` | `json.dumps([mapping[f]["frame"] for f in event["components"]])` |
| `application_index.py:612` | `json.dumps()` | `json.dumps(meta)` |
| `application_index.py:619` | `json.dumps()` | `json.dumps(records)` |
| `application_index.py:624` | `str(x)` | `str(exc)` |
| `assistant.py:79` | `f-string :03d` | `counts[kind] -> :03d` |
| `assistant.py:87` | `str(x)` | `str(ip_address(clean))` |
| `assistant.py:98` | `sorted(key)` | `sorted(known_hosts, key=lambda h: (-len(h), h))` |
| `attribution_synthetic.py:170` | `a % b (modulo)` | `-len(body) % 4` |
| `attribution_synthetic.py:178` | `sorted()` | `sorted( (ts, i, packet) for i, p in enumerate(points) for ts, packet in captures[p] )` |
| `attribution_synthetic.py:181` | `round(x)` | `round(ts * 1e6)` |
| `attribution_synthetic.py:216` | `str(x)` | `str(file)` |
| `attribution_synthetic.py:217` | `json.dumps(indent)` | `json.dumps(truth, indent=2)` |
| `batch_ingest.py:17` | `str(x)` | `str(Path(p).expanduser().resolve())` |
| `batch_ingest.py:23` | `int(expr)` | `int(ram / RAM_PER_WORKER)` |
| `batch_ingest.py:23` | `max()` | `max(0, cores - 1)` |
| `batch_ingest.py:23` | `max()` | `max(0, int(ram / RAM_PER_WORKER))` |
| `batch_ingest.py:23` | `min()` | `min(files, max(0, cores - 1), max(0, int(ram / RAM_PER_WORKER)))` |
| `batch_ingest.py:63` | `sorted()` | `sorted((c, i) for i, c in counts.items())` |
| `batch_ingest.py:84` | `str(x)` | `str(path)` |
| `batch_ingest.py:158` | `min()` | `min(count, 1)` |
| `batch_ingest.py:162` | `min()` | `min(count, workers)` |
| `batch_ingest.py:167` | `str(x)` | `str(i)` |
| `batch_ingest.py:170` | `str(x)` | `str(i)` |
| `batch_ingest.py:180` | `sum()` | `sum(s.get("frames", 0) for s in states)` |
| `batch_ingest.py:185` | `str(x)` | `str(i)` |
| `batch_ingest.py:194` | `str(x)` | `str(exc)` |
| `batch_ingest.py:209` | `min()` | `min(count, len(indices))` |
| `batch_ingest.py:243` | `str(x)` | `str(i)` |
| `batch_ingest.py:257` | `sorted()` | `sorted(set(pair_counts_i.keys()) & ref_pairs)` |
| `batch_ingest.py:258` | `sum()` | `sum(pair_counts_i[p] for p in matched_pairs)` |
| `batch_ingest.py:285` | `sorted()` | `sorted(ref_pairs)` |
| `capture_layers.py:27` | `str(x)` | `str(v)` |
| `capture_layers.py:103` | `str(x)` | `str(datetime.fromisoformat(stamp.replace("Z", "+00:00")).timestamp())` |
| `capture_layers.py:108` | `str(x)` | `str(reassembled + number(data.get("ip.hdr_len", "0")))` |
| `capture_layers.py:110` | `str(x)` | `str(reassembled)` |
| `capture_layers.py:117` | `int(expr)` | `int(raw[1])` |
| `capture_layers.py:117` | `int(expr)` | `int(raw[2])` |
| `capture_layers.py:119` | `int(expr)` | `int(raw[2])` |
| `capture_layers.py:123` | `max()` | `max(0, size)` |
| `capture_layers.py:149` | `json.dumps()` | `json.dumps(meta)` |
| `capture_layers.py:170` | `json.dumps(sort_keys)` | `json.dumps( [ capture["identity"], selection.model_dump( exclude={ "x", "y", "label", "device", "kind", "trans...` |
| `capture_layers.py:194` | `str(x)` | `str(source)` |
| `capture_layers.py:212` | `json.dumps()` | `json.dumps(batch)` |
| `capture_layers.py:213` | `json.dumps()` | `json.dumps([dict(layer="VARCHAR", local_frame="BIGINT", packet=PACKET_COLUMNS)])` |
| `capture_layers.py:237` | `str(x)` | `str(source)` |
| `capture_layers.py:241` | `str(x)` | `str(selected)` |
| `capture_layers.py:250` | `str(x)` | `str(inventory["frame_limit"])` |
| `capture_layers.py:265` | `str(x)` | `str(selected)` |
| `capture_layers.py:296` | `str(x)` | `str(exc)` |
| `capture_layers.py:342` | `json.dumps()` | `json.dumps(PACKET_COLUMNS)` |
| `capture_layers.py:383` | `str(x)` | `str(v)` |
| `capture_layers.py:395` | `int(expr)` | `int(layer["raw"][1])` |
| `capture_layers.py:395` | `int(expr)` | `int(raw_fragment[1])` |
| `capture_layers.py:395` | `int(expr)` | `int(raw_fragment[2])` |
| `capture_layers.py:408` | `int(expr)` | `int(data["frame.number"])` |
| `capture_layers.py:408` | `int(expr)` | `int(target)` |
| `capture_layers.py:409` | `int(expr)` | `int(v)` |
| `capture_layers.py:410` | `int(expr)` | `int(data["frame.number"])` |
| `case_setup.py:23` | `sorted()` | `sorted(root.rglob("*"))` |
| `case_setup.py:52` | `sorted()` | `sorted({i["snaplen"] for i in info.get("interfaces", []) if i.get("snaplen") is not None})` |
| `case_setup.py:89` | `max()` | `max(a for a, b in intervals)` |
| `case_setup.py:90` | `min()` | `min(b for a, b in intervals)` |
| `case_setup.py:140` | `str(x)` | `str(exc)` |
| `case_setup.py:142` | `str(x)` | `str(p.resolve())` |
| `case_setup.py:173` | `a % b (modulo)` | `n % 4` |
| `case_setup.py:174` | `a // b` | `n // 4` |
| `case_setup.py:193` | `str(x)` | `str(root)` |
| `cli.py:20` | `str(x)` | `str(Path.home() / "PacketBreaker" / "default")` |
| `cli.py:140` | `json.dump(indent)` | `json.dump(reference_template(saved), target, indent=2)` |
| `cli.py:148` | `json.dumps(indent)` | `json.dumps(result, indent=2)` |
| `cli.py:158` | `sorted()` | `sorted( p for p in args.input.iterdir() if p.suffix.lower() in (".pcap", ".pcapng", ".cap") and p.is_file() )` |
| `cli.py:168` | `json.dumps(indent)` | `json.dumps(result, indent=2)` |
| `cli.py:179` | `f-string :,` | `s.get('frames', 0) -> :,` |
| `cli.py:182` | `json.dumps(indent)` | `json.dumps(overview, indent=2)` |
| `cli.py:189` | `str(x)` | `str(project.path)` |
| `cli.py:205` | `json.dumps(indent)` | `json.dumps(topology, indent=2)` |
| `cli.py:206` | `json.dumps(indent)` | `json.dumps(report, indent=2)` |
| `cli.py:218` | `json.dumps(indent)` | `json.dumps(analyze(project, topology), indent=2)` |
| `cli.py:223` | `json.dumps(indent)` | `json.dumps( import_text( Project(args.project, memory_limit_mb=args.memory_limit_mb), args.path, args.start_ti...` |
| `cli.py:286` | `str(x)` | `str(exc)` |
| `clock.py:34` | `min()` | `min(x[0] for x in samples)` |
| `clock.py:35` | `max()` | `max(x[0] for x in samples)` |
| `clock.py:38` | `min()` | `min(len(forward), len(reverse))` |
| `clock.py:42` | `int(expr)` | `int((a - epoch) / max(span, 0.001) * 20)` |
| `clock.py:42` | `max()` | `max(span, 0.001)` |
| `clock.py:42` | `min()` | `min(19, int((a - epoch) / max(span, 0.001) * 20))` |
| `clock.py:49` | `statistics.quantiles(n=100, method="inclusive")` | `quantiles(fw, n=100, method="inclusive")` |
| `clock.py:50` | `statistics.quantiles(n=100, method="inclusive")` | `quantiles(rv, n=100, method="inclusive")` |
| `clock.py:52` | `statistics.median` | `median([a for a, _, _ in points])` |
| `clock.py:62` | `statistics.median` | `median(slopes)` |
| `clock.py:67` | `statistics.quantiles(n=100, method="inclusive")` | `quantiles(fw, n=100, method="inclusive")` |
| `clock.py:68` | `statistics.quantiles(n=100, method="inclusive")` | `quantiles(rv, n=100, method="inclusive")` |
| `clock.py:71` | `statistics.quantiles(n=100, method="inclusive")` | `quantiles(fw, n=100, method="inclusive")` |
| `clock.py:72` | `statistics.quantiles(n=100, method="inclusive")` | `quantiles(rv, n=100, method="inclusive")` |
| `clock.py:73` | `min()` | `min(0.002, median([3 * (fq[24] - fq[0]), 3 * (rq[98] - rq[74])]))` |
| `clock.py:73` | `statistics.median` | `median([3 * (fq[24] - fq[0]), 3 * (rq[98] - rq[74])])` |
| `clock.py:74` | `max()` | `max(2 * jitter, 1e-6)` |
| `clock.py:98` | `max()` | `max(a for a, _, _ in samples)` |
| `clock.py:98` | `min()` | `min(a for a, _, _ in samples)` |
| `clock.py:101` | `min()` | `min(map(len, groups))` |
| `clock.py:103` | `statistics.median` | `median(g)` |
| `clock.py:104` | `max()` | `max(centers)` |
| `clock.py:104` | `min()` | `min(centers)` |
| `clock.py:107` | `statistics.quantiles(n=20, method="inclusive")` | `quantiles(residuals, n=20, method="inclusive")` |
| `clock.py:112` | `max()` | `max(0.001, tolerance_ms)` |
| `clock.py:120` | `f-string :.3f` | `tolerance_ms -> :.3f` |
| `cycles.py:127` | `sorted()` | `sorted(keys)` |
| `cycles.py:127` | `sorted()` | `sorted(keys)` |
| `cycles.py:187` | `f-string :.2f` | `r['max_ms'] -> :.2f` |
| `cycles.py:187` | `f-string :.3f` | `r['baseline_ms'] -> :.3f` |
| `cycles.py:187` | `f-string :.3f` | `r['uncertainty_ms'] -> :.3f` |
| `delivery.py:150` | `f-string :.1f` | `row['max_recovery_ms'] -> :.1f` |
| `device_drops.py:65` | `json.dumps(separators)` | `json.dumps([proto, dst, dport, src, sport], separators=(",", ":"))` |
| `device_drops.py:78` | `sorted()` | `sorted(confirmed_tuples)` |
| `device_drops.py:145` | `sorted()` | `sorted({f["device"] for f in facts})` |
| `device_drops.py:146` | `sorted()` | `sorted({f["stage"] for f in facts})` |
| `device_drops.py:147` | `sorted()` | `sorted({f["time_source"] for f in facts})` |
| `device_drops.py:176` | `str(x)` | `str(p.vendor_stage)` |
| `device_drops.py:242` | `sum()` | `sum(x["count"] for x in grouped)` |
| `diagnostic_synthetic.py:22` | `f-string :x` | `n -> :x` |
| `diagnostic_synthetic.py:108` | `sum()` | `sum(e <= t for e in echoes)` |
| `diagnostic_synthetic.py:177` | `sorted()` | `sorted(deliveries)` |
| `diagnostic_synthetic.py:180` | `sorted()` | `sorted(received)` |
| `diagnostic_synthetic.py:182` | `max()` | `max(end, b)` |
| `diagnostic_synthetic.py:190` | `max()` | `max(b for a, b in holes)` |
| `diagnostic_synthetic.py:190` | `min()` | `min(a for a, b in holes)` |
| `diagnostic_synthetic.py:205` | `max(default)` | `max((a for stamp, a in history if stamp <= t), default=isn + 1)` |
| `diagnostic_synthetic.py:218` | `max(default)` | `max((a for stamp, a in transmissions if stamp <= t + 0.009), default=isn + 1)` |
| `diagnostic_synthetic.py:307` | `sorted()` | `sorted(captures[i])` |
| `diagnostic_synthetic.py:308` | `str(x)` | `str(path.resolve())` |
| `diagnostic_synthetic.py:328` | `json.dumps(indent)` | `json.dumps(truth, indent=2)` |
| `diagnostic_synthetic.py:329` | `json.dumps(indent)` | `json.dumps(topology, indent=2)` |
| `engine_export.py:25` | `divmod()` | `divmod(index - 1, 26)` |
| `engine_export.py:109` | `int(expr)` | `int(v)` |
| `engine_export.py:113` | `str(x)` | `str(v)` |
| `engine_export.py:128` | `json.dumps(indent)` | `json.dumps( dict( note="Epoch timestamps; ms durations. Opaque data cycles are not application messages. Frame...` |
| `evidence.py:24` | `int(expr)` | `int(pa)` |
| `evidence.py:24` | `int(expr)` | `int(pb)` |
| `evidence.py:29` | `sorted()` | `sorted([forward, one(dst, src, dport, sport)])` |
| `evidence.py:39` | `sorted(key,reverse)` | `sorted(scope, key=len, reverse=True)` |
| `evidence.py:46` | `int(expr)` | `int(tunnel['session'], 0)` |
| `evidence.py:48` | `int(expr)` | `int(tunnel['vni'], 0)` |
| `evidence.py:56` | `int(expr)` | `int(packet['ipid'])` |
| `evidence.py:58` | `f-string :04x` | `packet['flags'] -> :04x` |
| `evidence.py:89` | `min()` | `min(group["start"], item["start"])` |
| `evidence.py:90` | `max()` | `max(group["end"], item["end"])` |
| `evidence.py:94` | `sorted()` | `sorted(g["expressions"])` |
| `evidence.py:97` | `f-string :.9f` | `g['end'] + 1e-6 -> :.9f` |
| `evidence.py:97` | `f-string :.9f` | `g['start'] - 1e-6 -> :.9f` |
| `evidence.py:111` | `int(expr)` | `int(limit)` |
| `evidence.py:172` | `a % b (modulo)` | `(e["seq"] - e["canonical_seq"]) % 4294967296` |
| `evidence.py:173` | `a % b (modulo)` | `(e["ack"] - e["canonical_ack"]) % 4294967296` |
| `evidence.py:197` | `json.dumps()` | `json.dumps(vendor["fw1.direction"])` |
| `evidence.py:199` | `json.dumps()` | `json.dumps(vendor["fw1.chain"])` |
| `evidence.py:215` | `int(expr)` | `int(fragment['id'], 0)` |
| `evidence.py:239` | `a // b` | `physical[frame]['off'] // 8` |
| `export.py:144` | `datetime.isoformat` | `datetime.now(timezone.utc).isoformat()` |
| `export.py:161` | `datetime.isoformat` | `datetime.now(timezone.utc).isoformat()` |
| `export.py:180` | `str(x)` | `str(value)` |
| `export.py:183` | `json.dumps(ensure_ascii,indent)` | `json.dumps(value, indent=2, ensure_ascii=False)` |
| `export.py:220` | `str.format` | `"<article><h3>{} · {} Mbps</h3><p>Duration {} ms; cause unknown.</p>{}<ul>{}</ul></article>".format( h(b["poin...` |
| `export.py:231` | `json.dumps(allow_nan,ensure_ascii,separators)` | `json.dumps(data, ensure_ascii=True, allow_nan=False, separators=(",", ":"))` |
| `export.py:311` | `json.dumps(allow_nan,ensure_ascii,indent)` | `json.dumps(data, indent=2, ensure_ascii=False, allow_nan=False)` |
| `field_diff.py:36` | `a % b (modulo)` | `(packet["raw_seq"] - packet["seq"]) % 4294967296` |
| `field_diff.py:51` | `a // b` | `limit // max(1, sum(max(0, len(path) - 1) for _, path in paths))` |
| `field_diff.py:51` | `max()` | `max(0, len(path) - 1)` |
| `field_diff.py:51` | `max()` | `max(1, limit // max(1, sum(max(0, len(path) - 1) for _, path in paths)))` |
| `field_diff.py:51` | `max()` | `max(1, sum(max(0, len(path) - 1) for _, path in paths))` |
| `field_diff.py:51` | `sum()` | `sum(max(0, len(path) - 1) for _, path in paths)` |
| `fortinet.py:27` | `int(expr)` | `int((fraction[1] + "0" * 9)[:9])` |
| `fortinet.py:33` | `int(expr)` | `int(dt.replace(tzinfo=timezone.utc).timestamp() if dt.tzinfo is None else dt.timestamp())` |
| `fortinet.py:47` | `int(expr)` | `int(proto[1], 0)` |
| `fortinet.py:63` | `str(x)` | `str(probe)` |
| `fortinet.py:87` | `int(expr)` | `int(field, 0)` |
| `fortinet.py:90` | `int(expr)` | `int(decoded[2], 0)` |
| `fortinet.py:90` | `int(expr)` | `int(packet_type[1], 0)` |
| `fortinet.py:91` | `int(expr)` | `int(decoded[3], 0)` |
| `fortinet.py:91` | `int(expr)` | `int(hardware[1], 0)` |
| `fortinet.py:130` | `f-string :02d` | `len(files) -> :02d` |
| `fortinet.py:134` | `str(x)` | `str(path.resolve())` |
| `fortinet.py:143` | `divmod()` | `divmod(current["time_ns"], 10**9)` |
| `fortinet.py:166` | `a % b (modulo)` | `total_lines % 1000` |
| `fortinet.py:181` | `Decimal()` | `Decimal(stamp)` |
| `fortinet.py:181` | `int(expr)` | `int(Decimal(stamp) * 10**9)` |
| `fortinet.py:214` | `int(expr)` | `int(match[1], 16)` |
| `fortinet.py:226` | `sum()` | `sum(skipped.values())` |
| `fortinet.py:232` | `sum()` | `sum(skipped.values())` |
| `headlines.py:10` | `datetime.fromtimestamp` | `datetime.fromtimestamp(round(ts, 3), timezone.utc)` |
| `headlines.py:10` | `round(x, n)` | `round(ts, 3)` |
| `headlines.py:13` | `datetime.isoformat` | `local.isoformat(sep=" ", timespec="milliseconds")` |
| `headlines.py:14` | `datetime.isoformat` | `utc.isoformat(sep=" ", timespec="milliseconds")` |
| `headlines.py:30` | `f-string :.2f` | `wait -> :.2f` |
| `headlines.py:41` | `f-string :.3f` | `s['offset_uncertainty_ms'] -> :.3f` |
| `headlines.py:107` | `f-string :.2f` | `rate -> :.2f` |
| `headlines.py:112` | `f-string :.1f` | `100 * quick / lost -> :.1f` |
| `headlines.py:112` | `f-string :.1f` | `100 * stalls / lost -> :.1f` |
| `headlines.py:113` | `f-string :.3f` | `max_stall / 1000 -> :.3f` |
| `headlines.py:116` | `f-string :.1f` | `100 * other / lost -> :.1f` |
| `hold_release.py:39` | `f-string :.1f` | `v -> :.1f` |
| `hold_release.py:39` | `f-string :.1f` | `v -> :.1f` |
| `host_path.py:143` | `max(default)` | `max((s.get("uncertainty_ms") or 0 for s in report["streams"]), default=0)` |
| `host_path.py:144` | `max()` | `max(1, 2 * uncertainty)` |
| `host_path.py:150` | `sum()` | `sum(s["delay_byte_ms"] or 0 for s in report["streams"] if not s["reason"])` |
| `host_path.py:157` | `max(key)` | `max(affected, key=lambda s: s["max_ms"])` |
| `host_path.py:188` | `max()` | `max(topology.stall_ms, 2 * item["uncertainty_ms"])` |
| `host_path.py:189` | `max()` | `max(1, 2 * item["uncertainty_ms"])` |
| `host_path.py:191` | `max()` | `max( 1, 2 * uncertainty )` |
| `host_path.py:218` | `sorted()` | `sorted({s["hop"] for s in affected})` |
| `host_path.py:250` | `f-string :.3f` | `aggregate['p95_ms'] -> :.3f` |
| `host_path.py:252` | `f-string :.3f` | `aggregate['p95_ms'] -> :.3f` |
| `host_path.py:262` | `f-string :.3f` | `item['uncertainty_ms'] -> :.3f` |
| `host_path.py:370` | `max(key)` | `max(delays, key=lambda f: f["metrics"]["max_ms"] - f["metrics"]["uncertainty_ms"])` |
| `host_path.py:377` | `max(default)` | `max(competing, default=0)` |
| `host_path.py:387` | `.sort(key)` | `findings.sort(key=lambda f: (f is not dominant, rank[f["severity"]], f["time_range"][0]))` |
| `host_path.py:432` | `max()` | `max(1e-6, w_start - start)` |
| `host_path.py:433` | `max()` | `max(1e-6, w_end - w_start)` |
| `host_path.py:434` | `max()` | `max(1e-6, end - w_end)` |
| `host_path.py:482` | `max()` | `max(rate_before, rate_during, rate_after)` |
| `host_path.py:484` | `max()` | `max( 1e-9, min(rate_before, rate_during, rate_after) )` |
| `host_path.py:484` | `max()` | `max(rate_before, rate_during, rate_after)` |
| `host_path.py:485` | `min()` | `min(rate_before, rate_during, rate_after)` |
| `host_path.py:487` | `max()` | `max(1e-9, min(lps_before, lps_during, lps_after))` |
| `host_path.py:487` | `max()` | `max(lps_before, lps_during, lps_after)` |
| `host_path.py:487` | `min()` | `min(lps_before, lps_during, lps_after)` |
| `host_path.py:488` | `min()` | `min( lps_before, lps_after )` |
| `host_path.py:488` | `min()` | `min(rate_before, rate_after)` |
| `host_path.py:497` | `round(x)` | `round(med_rto)` |
| `host_path.py:502` | `round(x, n)` | `round(rate_before, 4)` |
| `host_path.py:503` | `round(x, n)` | `round(rate_during, 4)` |
| `host_path.py:504` | `round(x, n)` | `round(rate_after, 4)` |
| `host_path.py:505` | `round(x, n)` | `round(lps_before, 3)` |
| `host_path.py:506` | `round(x, n)` | `round(lps_during, 3)` |
| `host_path.py:507` | `round(x, n)` | `round(lps_after, 3)` |
| `host_path.py:510` | `f-string :.2f` | `lps_before -> :.2f` |
| `host_path.py:510` | `f-string :.3f` | `rate_before -> :.3f` |
| `host_path.py:511` | `f-string :.2f` | `lps_during -> :.2f` |
| `host_path.py:511` | `f-string :.3f` | `rate_during -> :.3f` |
| `host_path.py:512` | `f-string :.2f` | `lps_after -> :.2f` |
| `host_path.py:512` | `f-string :.3f` | `rate_after -> :.3f` |
| `host_path.py:527` | `f-string :.3f` | `s['loss_percent'] -> :.3f` |
| `host_path.py:570` | `max()` | `max(1, m.get("count", 1))` |
| `host_path.py:573` | `max(key)` | `max(candidates, key=score)` |
| `host_path.py:574` | `.sort(key)` | `findings.sort( key=lambda f: ( f is not primary, bool(f.get("chronic_loss") or f["metrics"].get("chronic_loss"...` |
| `incident.py:9` | `sorted()` | `sorted({(p, f) for bundle in bundles for p, f in bundle if p and f})` |
| `incident.py:135` | `min(default)` | `min( (r["request_first"] for r in points if r["request_first"] is not None), default=None )` |
| `incident.py:138` | `max(default)` | `max((r["cycle_ms"] for r in points if r["cycle_ms"] is not None), default=None)` |
| `incident.py:141` | `sum()` | `sum(r["ms"] for r in parts if r["component"] == "RX")` |
| `incident.py:144` | `sum()` | `sum(r["ms"] for r in parts if r["component"] == "TX")` |
| `incident.py:181` | `min()` | `min(r["unknown"], offload_index.get(key, 0))` |
| `ingest.py:50` | `str(x)` | `str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Wireshark/tshark.exe")` |
| `ingest.py:56` | `str(x)` | `str(Path(candidate).resolve())` |
| `ingest.py:62` | `int(expr)` | `int(value == "True")` |
| `ingest.py:63` | `int(expr)` | `int(value)` |
| `ingest.py:63` | `int(expr)` | `int(value, 16)` |
| `ingest.py:68` | `json.dumps(separators)` | `json.dumps([proto, src, sport, dst, dport], separators=(",", ":"))` |
| `ingest.py:73` | `json.dumps()` | `json.dumps(identity)` |
| `ingest.py:112` | `max()` | `max(0, number(g("udp.length")) - 8)` |
| `ingest.py:147` | `float(expr)` | `float(g("frame.time_epoch"))` |
| `ingest.py:177` | `float(expr)` | `float(g("tcp.analysis.ack_rtt"))` |
| `ingest.py:186` | `json.dumps(separators)` | `json.dumps( { "transport_index": 1, **({"sip_probe": 1} if "sip" in g("frame.protocols").split(":") else {}), ...` |
| `ingest.py:215` | `float(expr)` | `float(g("tcp.analysis.rto"))` |
| `ingest.py:271` | `str(x)` | `str(path)` |
| `ingest.py:278` | `str(x)` | `str(path)` |
| `ingest.py:287` | `json.dumps()` | `json.dumps([*base_identity, *([read_filter] if read_filter else [])])` |
| `ingest.py:288` | `json.dumps()` | `json.dumps(base_identity)` |
| `ingest.py:313` | `json.dumps()` | `json.dumps(info)` |
| `ingest.py:313` | `str(x)` | `str(path)` |
| `ingest.py:321` | `str(x)` | `str(path)` |
| `ingest.py:353` | `str(x)` | `str(checkpoint_uuid)` |
| `ingest.py:357` | `str(x)` | `str(info["frame_limit"])` |
| `ingest.py:389` | `str(x)` | `str(batch_path)` |
| `ingest.py:432` | `float(expr)` | `float(values[1])` |
| `ingest.py:443` | `math.isfinite` | `math.isfinite(observed_ts)` |
| `ingest.py:490` | `f-string :.2f` | `info['zero_tail_bytes'] / 1_000_000 -> :.2f` |
| `ingest.py:501` | `sum()` | `sum(reasons.values())` |
| `ingest.py:502` | `sorted()` | `sorted(reasons.items())` |
| `ingest.py:512` | `min()` | `min(total_records, info["frame_limit"])` |
| `ingest.py:513` | `sum()` | `sum(reasons.values())` |
| `ingest.py:514` | `max()` | `max(0, total_records - prefilter_retained)` |
| `ingest.py:532` | `sum()` | `sum(reasons.values())` |
| `ingest.py:535` | `sum()` | `sum(reasons.values())` |
| `ingest.py:577` | `json.dumps()` | `json.dumps(info)` |
| `ingest.py:586` | `str(x)` | `str(exc)` |
| `integrity.py:25` | `sorted()` | `sorted({p["ttl"] for p in samples})` |
| `integrity.py:35` | `a % b (modulo)` | `(packet["ipid"] - p["ipid"]) % 65536` |
| `integrity.py:38` | `a % b (modulo)` | `(b - a) % 65536` |
| `integrity.py:214` | `sorted(key)` | `sorted(refs, key=lambda r: order.get((r["point"], r["frame"]), len(order)))` |
| `integrity.py:339` | `sorted()` | `sorted({p["ttl"] for p in local_samples})` |
| `integrity.py:883` | `max(key)` | `max(candidates, key=lambda item: (item[0], item[1]["time_range"][0]))` |
| `integrity.py:887` | `sum()` | `sum(df["metrics"].get("count", 1) for df in downstreams)` |
| `integrity_synthetic.py:43` | `a % b (modulo)` | `(identity * 7919) % 65536` |
| `integrity_synthetic.py:129` | `str(x)` | `str(path)` |
| `integrity_synthetic.py:141` | `json.dumps(indent)` | `json.dumps(truth, indent=2)` |
| `matching.py:120` | `sorted()` | `sorted((a, b))` |
| `matching.py:126` | `sorted()` | `sorted(pair)` |
| `matching.py:142` | `max()` | `max(left, right)` |
| `matching.py:142` | `min()` | `min(left, right)` |
| `matching.py:150` | `max()` | `max(left, right)` |
| `matching.py:150` | `min()` | `min(left, right)` |
| `matching.py:161` | `max()` | `max(left, right)` |
| `matching.py:161` | `min()` | `min(left, right)` |
| `matching.py:206` | `max()` | `max(ra, rb)` |
| `matching.py:206` | `min()` | `min(ra, rb)` |
| `matching.py:209` | `sorted()` | `sorted(active)` |
| `matching.py:209` | `sorted()` | `sorted(active)` |
| `metadata.py:26` | `max()` | `max(0, end - 1024 * 1024)` |
| `metadata.py:54` | `a // b` | `(size - start) // record_size` |
| `metadata.py:61` | `a % b (modulo)` | `(size - start) % record_size` |
| `metadata.py:93` | `a % b (modulo)` | `length % 4` |
| `metadata.py:100` | `max()` | `max(result["ifdrop"], drops)` |
| `metadata.py:158` | `a % b (modulo)` | `length % 4` |
| `metadata.py:188` | `max()` | `max(counters.get(key, 0), struct.unpack(endian + "Q", value)[0])` |
| `metadata.py:195` | `sum()` | `sum(values)` |
| `microbursts.py:32` | `min()` | `min(stamps)` |
| `microbursts.py:38` | `min()` | `min(stamps)` |
| `microbursts.py:53` | `sorted()` | `sorted(set(frames) - {None})` |
| `microbursts.py:66` | `f-string :.9f` | `r['ts'] + 1e-6 -> :.9f` |
| `microbursts.py:66` | `f-string :.9f` | `r['ts'] - 1e-6 -> :.9f` |
| `microbursts.py:149` | `json.dumps()` | `json.dumps([dict(point=p, frame=f) for p, f in keys])` |
| `offload_synthetic.py:21` | `a % b (modulo)` | `ack % 2**32` |
| `offload_synthetic.py:21` | `a % b (modulo)` | `seq % 2**32` |
| `offload_synthetic.py:35` | `f-string :04d` | `round -> :04d` |
| `offload_synthetic.py:37` | `min()` | `min(mss, size - off)` |
| `offload_synthetic.py:58` | `a // b` | `(hi - 1) // mss` |
| `offload_synthetic.py:107` | `str(x)` | `str(p.resolve())` |
| `offload_synthetic.py:120` | `json.dumps(indent)` | `json.dumps(truth, indent=2)` |
| `offload_synthetic.py:121` | `json.dumps(indent)` | `json.dumps(topology, indent=2)` |
| `onset.py:59` | `sum()` | `sum(usable(x) for x in complete[after_prefix:])` |
| `onset.py:64` | `statistics.median` | `median(initial)` |
| `onset.py:65` | `statistics.median` | `median(abs(v - center) for v in initial)` |
| `onset.py:67` | `max()` | `max(floor, multiplier * 1.4826 * mad)` |
| `onset.py:67` | `max()` | `max(initial)` |
| `onset.py:67` | `min()` | `min(initial)` |
| `onset.py:68` | `max()` | `max(floor, abs(center) * 0.25)` |
| `onset.py:72` | `math.ceil` | `math.ceil(WINDOW_SECONDS / bucket_width)` |
| `onset.py:72` | `max()` | `max(MIN_EVENT_BUCKETS, math.ceil(WINDOW_SECONDS / bucket_width))` |
| `onset.py:98` | `statistics.median` | `median(samples)` |
| `onset.py:99` | `statistics.median` | `median(abs(v - baseline) for v in samples)` |
| `onset.py:100` | `max()` | `max(floor, multiplier * 1.4826 * spread)` |
| `onset.py:104` | `sum()` | `sum(v[denominator_key] for v in recent)` |
| `onset.py:106` | `sum()` | `sum(v[denominator_key] for v in reference)` |
| `onset.py:109` | `sum()` | `sum(v[count_key] for v in reference)` |
| `onset.py:110` | `max()` | `max(floor, multiplier * 1.4826 * spread)` |
| `onset.py:111` | `sum()` | `sum(v[count_key] for v in recent)` |
| `onset.py:114` | `max()` | `max( MIN_EVENTS, math.ceil(expected + MIN_EVENTS), math.floor(threshold * exposure / scale) + 1 )` |
| `onset.py:115` | `math.ceil` | `math.ceil(expected + MIN_EVENTS)` |
| `onset.py:115` | `math.floor` | `math.floor(threshold * exposure / scale)` |
| `onset.py:188` | `sum()` | `sum(n for status, n in counts.items() if status != "unknown")` |
| `onset.py:222` | `sum()` | `sum(v["capture_misses"] for v in values)` |
| `onset.py:223` | `sum()` | `sum(v["unknown_events"] for v in values)` |
| `onset.py:224` | `sorted()` | `sorted({v["reason"] for v in values if v["reason"] and v["coverage"] == "capturing"})` |
| `onset.py:231` | `max()` | `max(1.0, 2 * (s["offset_uncertainty_ms"] or 0))` |
| `onset.py:300` | `f-string :.3f` | `item['baseline_value'] -> :.3f` |
| `onset.py:300` | `f-string :.3f` | `item['threshold'] -> :.3f` |
| `onset.py:301` | `f-string :.3f` | `floor -> :.3f` |
| `onset.py:302` | `f-string :.3f` | `item['value'] -> :.3f` |
| `onset.py:307` | `f-string :.3f` | `item['baseline_value'] -> :.3f` |
| `onset.py:307` | `f-string :.3f` | `item['mad'] -> :.3f` |
| `onset.py:307` | `f-string :.3f` | `item['threshold'] -> :.3f` |
| `onset.py:308` | `f-string :.3f` | `item['expected_events'] -> :.3f` |
| `onset.py:308` | `f-string :g` | `item['window_seconds'] -> :g` |
| `onset.py:325` | `min(key)` | `min(found, key=lambda x: x["time"])` |
| `onset.py:327` | `f-string :g` | `topology.bucket_seconds -> :g` |
| `onset.py:334` | `sorted(key)` | `sorted( (x for x in network_detections if x["direction"] == d), key=lambda x: (x["time"], x["segment"]) )` |
| `onset.py:347` | `max()` | `max(groups[-1]["end"], upper)` |
| `onset.py:355` | `min(key)` | `min(network_detections, key=lambda x: x["time"])` |
| `onset.py:381` | `sorted(key)` | `sorted( items, key=lambda x: ( 2 if x.get("scope") == "capture_signal" else 0 if x["segment"] in primes else 1...` |
| `onset.py:402` | `statistics.median` | `median(baseline)` |
| `onset.py:403` | `statistics.median` | `median(abs(v - center) for v in baseline)` |
| `onset.py:404` | `max()` | `max(floor, 6 * 1.4826 * mad)` |
| `onset.py:405` | `max()` | `max(baseline)` |
| `onset.py:405` | `max()` | `max(floor, 6 * 1.4826 * mad)` |
| `onset.py:405` | `min()` | `min(baseline)` |
| `onset.py:440` | `int(expr)` | `int(first["start"])` |
| `onset.py:441` | `int(expr)` | `int(first["start"])` |
| `onset.py:442` | `int(expr)` | `int(valid[changed[-1]]["start"])` |
| `onset.py:450` | `int(expr)` | `int(v["start"])` |
| `onset_synthetic.py:87` | `int(expr)` | `int(scenario.rsplit("_", 1)[1])` |
| `onset_synthetic.py:93` | `a % b (modulo)` | `i % (interval * 10)` |
| `onset_synthetic.py:97` | `a % b (modulo)` | `i % 3` |
| `onset_synthetic.py:118` | `f-string :04d` | `i -> :04d` |
| `onset_synthetic.py:123` | `a // b` | `(i - 120) // 30` |
| `onset_synthetic.py:148` | `str(x)` | `str(path.resolve())` |
| `onset_synthetic.py:190` | `json.dumps(indent)` | `json.dumps(truth, indent=2)` |
| `onset_synthetic.py:191` | `json.dumps(indent)` | `json.dumps(topology, indent=2)` |
| `pilot.py:51` | `sorted()` | `sorted(labels)` |
| `pilot.py:126` | `sum()` | `sum(classes.get(k, 0) for k in LOSS_CLASSES)` |
| `pilot.py:148` | `sum()` | `sum(r["packets"] for r in notes)` |
| `pilot.py:149` | `sum()` | `sum(r["retrans_observations"] for r in notes)` |
| `pilot.py:150` | `sum()` | `sum( sum(s["classes"].get(k, 0) for k in LOSS_CLASSES) for s in owned if s["loss_percent"] is not None )` |
| `pilot.py:151` | `sum()` | `sum(s["classes"].get(k, 0) for k in LOSS_CLASSES)` |
| `pilot_synthetic.py:58` | `a % b (modulo)` | `(identity if identity is not None else seq + ack + flags) % 65536` |
| `pilot_synthetic.py:81` | `a % b (modulo)` | `n % 3` |
| `pilot_synthetic.py:81` | `a % b (modulo)` | `n % 4` |
| `pilot_synthetic.py:82` | `a % b (modulo)` | `n % 3` |
| `pilot_synthetic.py:82` | `a % b (modulo)` | `n % 4` |
| `pilot_synthetic.py:129` | `a % b (modulo)` | `(base_seq // 800 + offset // 800 + port) % 5000` |
| `pilot_synthetic.py:129` | `a // b` | `base_seq // 800` |
| `pilot_synthetic.py:129` | `a // b` | `offset // 800` |
| `pilot_synthetic.py:195` | `a % b (modulo)` | `i % 1000` |
| `pilot_synthetic.py:196` | `a % b (modulo)` | `i % 600000` |
| `pilot_synthetic.py:197` | `int(expr)` | `int(t)` |
| `pilot_synthetic.py:197` | `int(expr)` | `int(t)` |
| `pilot_synthetic.py:197` | `round(x)` | `round((t - int(t)) * 1e6)` |
| `pilot_synthetic.py:218` | `str(x)` | `str(directory / (p + ".pcap"))` |
| `pilot_synthetic.py:225` | `json.dumps(indent)` | `json.dumps(truth, indent=2)` |
| `pilot_synthetic.py:226` | `json.dumps(indent)` | `json.dumps(topology, indent=2)` |
| `proxy_analysis.py:53` | `sorted()` | `sorted(keys)` |
| `proxy_analysis.py:196` | `json.dumps()` | `json.dumps(p)` |
| `proxy_matching.py:37` | `json.dumps()` | `json.dumps(clients)` |
| `proxy_matching.py:39` | `json.dumps()` | `json.dumps(servers)` |
| `proxy_matching.py:65` | `max()` | `max(e["rank"] for e in edges)` |
| `proxy_matching.py:67` | `min()` | `min(e["distance"] for e in edges)` |
| `proxy_matching.py:68` | `max()` | `max(2 * uncertainty_s, 1e-6)` |
| `proxy_resets.py:72` | `max()` | `max(c["complete"], s["complete"])` |
| `proxy_resets.py:74` | `max()` | `max(c["complete"], s["complete"])` |
| `proxy_waterfall.py:48` | `json.dumps()` | `json.dumps(p)` |
| `proxy_waterfall.py:100` | `sorted(key)` | `sorted(seeds.values(), key=lambda r: r["start"] or 0)` |
| `proxy_waterfall.py:236` | `sorted()` | `sorted({r["flow"] for r in stage.values()})` |
| `proxy_waterfall.py:254` | `a % b (modulo)` | `(syn["seq"] + 1) % 4294967296` |
| `proxy_waterfall.py:265` | `a % b (modulo)` | `(sa["seq"] + 1) % 4294967296` |
| `realistic.py:27` | `a % b (modulo)` | `ipid % 65536` |
| `realistic.py:46` | `sorted()` | `sorted(packets)` |
| `realistic.py:47` | `round(x)` | `round(ts * 1e6)` |
| `realistic.py:53` | `a % b (modulo)` | `(-len(packet)) % 4` |
| `realistic.py:88` | `a % b (modulo)` | `i % 2` |
| `realistic.py:88` | `f-string :x` | `i + 1 -> :x` |
| `realistic.py:139` | `a // b` | `rounds // 2` |
| `realistic.py:142` | `str(x)` | `str(r)` |
| `realistic.py:145` | `a % b (modulo)` | `r % 7` |
| `realistic.py:149` | `a % b (modulo)` | `i % 9` |
| `realistic.py:149` | `a % b (modulo)` | `r % 11` |
| `realistic.py:155` | `a % b (modulo)` | `i % 9` |
| `realistic.py:155` | `a % b (modulo)` | `r % 11` |
| `realistic.py:173` | `a // b` | `rounds // 2` |
| `realistic.py:244` | `f-string :02d` | `h -> :02d` |
| `realistic.py:246` | `str(x)` | `str(path.resolve())` |
| `realistic.py:284` | `json.dumps(indent)` | `json.dumps(truth, indent=2)` |
| `realistic.py:285` | `json.dumps(indent)` | `json.dumps(topology, indent=2)` |
| `reference.py:50` | `math.isfinite` | `math.isfinite(value)` |
| `reference.py:80` | `int(expr)` | `int(match[2])` |
| `reference.py:99` | `math.isfinite` | `math.isfinite(value)` |
| `reference.py:146` | `sum()` | `sum(r["status"] == s for r in results)` |
| `reference.py:222` | `sorted()` | `sorted(a.keys() \| b.keys())` |
| `reference.py:226` | `max()` | `max(len(a), len(b))` |
| `reference.py:261` | `sorted(key)` | `sorted(a.keys() \| b.keys(), key=str)` |
| `retransmissions.py:116` | `sorted()` | `sorted( { (event["point"], event[field]) for field in ("frame", "rto_frame", "ack_trigger_frame", "delivered_f...` |
| `security.py:229` | `min()` | `min( topology.security_scan_targets, topology.security_half_open_floor, topology.security_syn_rate * width )` |
| `security.py:304` | `f-string :.1f` | `wave['syn_rate'] -> :.1f` |
| `security.py:305` | `f-string :.1%` | `wave['established_ratio'] -> :.1%` |
| `security.py:305` | `f-string :.1%` | `wave['synack_ratio'] -> :.1%` |
| `security.py:325` | `sum()` | `sum(estimates)` |
| `security.py:374` | `f-string :.1%` | `c['failed_ratio'] -> :.1%` |
| `security.py:374` | `f-string :g` | `width -> :g` |
| `security_synthetic.py:25` | `a % b (modulo)` | `n % 250` |
| `security_synthetic.py:25` | `a // b` | `n // 250` |
| `security_synthetic.py:25` | `f-string :x` | `n + 1 -> :x` |
| `security_synthetic.py:97` | `a % b (modulo)` | `n % 3` |
| `security_synthetic.py:126` | `str(x)` | `str(file)` |
| `security_synthetic.py:161` | `json.dumps(indent)` | `json.dumps(topology, indent=2)` |
| `security_synthetic.py:162` | `json.dumps(indent)` | `json.dumps(truth, indent=2)` |
| `store.py:73` | `a // b` | `psutil.virtual_memory().total // (4 * 1024**2)` |
| `store.py:73` | `max()` | `max(1024, psutil.virtual_memory().total // (4 * 1024**2))` |
| `store.py:73` | `min()` | `min(8192, max(1024, psutil.virtual_memory().total // (4 * 1024**2)))` |
| `store.py:135` | `str(x)` | `str(self.path / "project.duckdb")` |
| `store.py:138` | `max()` | `max(1, (os.cpu_count() or 1) - 1)` |
| `store.py:138` | `min()` | `min(8, max(1, (os.cpu_count() or 1) - 1))` |
| `store.py:138` | `str(x)` | `str(min(8, max(1, (os.cpu_count() or 1) - 1)))` |
| `store.py:140` | `str(x)` | `str(allow_external)` |
| `store.py:141` | `str(x)` | `str(self.path / ".spill")` |
| `store.py:171` | `json.dumps()` | `json.dumps(value)` |
| `store.py:192` | `json.dumps()` | `json.dumps(info)` |
| `stream_charts.py:125` | `max()` | `max(capture[0], lo)` |
| `stream_charts.py:128` | `min()` | `min(capture[1], hi)` |
| `stream_charts.py:132` | `max()` | `max(capture[0], lo)` |
| `stream_charts.py:135` | `min()` | `min(capture[1], hi)` |
| `symptoms.py:47` | `sorted()` | `sorted(downstream)` |
| `symptoms.py:52` | `sorted()` | `sorted(downstream)` |
| `synthetic.py:11` | `a % b (modulo)` | `len(data) % 2` |
| `synthetic.py:13` | `a // b` | `len(data) // 2` |
| `synthetic.py:13` | `str(x)` | `str(len(data) // 2)` |
| `synthetic.py:13` | `sum()` | `sum(struct.unpack("!" + str(len(data) // 2) + "H", data))` |
| `synthetic.py:28` | `a % b (modulo)` | `-len(options) % 4` |
| `synthetic.py:30` | `a // b` | `len(options) // 4` |
| `synthetic.py:40` | `a % b (modulo)` | `ipid % 65536` |
| `synthetic.py:54` | `sorted(key)` | `sorted(packets, key=lambda x: x[0])` |
| `synthetic.py:55` | `int(expr)` | `int(ts)` |
| `synthetic.py:56` | `round(x)` | `round((ts - sec) * 1e6)` |
| `synthetic.py:165` | `max()` | `max(0, hops - 5)` |
| `synthetic.py:166` | `max()` | `max(0, hops - 5)` |
| `synthetic.py:223` | `f-string :05d` | `i -> :05d` |
| `synthetic.py:226` | `f-string :02d` | `body_length -> :02d` |
| `synthetic.py:227` | `a % b (modulo)` | `i % 256` |
| `synthetic.py:231` | `a % b (modulo)` | `i % 7` |
| `synthetic.py:233` | `a % b (modulo)` | `i % 2` |
| `synthetic.py:243` | `a % b (modulo)` | `i % 13` |
| `synthetic.py:256` | `a % b (modulo)` | `i % 13` |
| `synthetic.py:315` | `f-string :02d` | `h -> :02d` |
| `synthetic.py:317` | `str(x)` | `str(path.resolve())` |
| `synthetic.py:360` | `json.dumps(indent)` | `json.dumps(topology, indent=2)` |
| `synthetic.py:361` | `json.dumps(indent)` | `json.dumps(truth, indent=2)` |
| `timeseries.py:96` | `min(default)` | `min((c["start"] for c in valid), default=None)` |
| `timeseries.py:97` | `max(default)` | `max((c["end"] for c in valid), default=None)` |
| `timeseries.py:112` | `math.floor` | `math.floor(lo / width)` |
| `timeseries.py:113` | `math.ceil` | `math.ceil((hi - origin) / width)` |
| `timeseries.py:127` | `max()` | `max(ca["start"], cb["start"])` |
| `timeseries.py:128` | `min()` | `min(ca["end"], cb["end"])` |
| `timeseries.py:183` | `min()` | `min( x.get("matchable", 0) / x["packets"] if x.get("packets") else 1, downstream_quality.get(i, 1) )` |
| `timeseries.py:187` | `f-string :.1f` | `100 * (1 - ratio) -> :.1f` |
| `timeseries.py:205` | `max()` | `max(window["start"], window["common_start"])` |
| `timeseries.py:206` | `min()` | `min(window["end"], window["common_end"])` |
| `timeseries.py:214` | `sum()` | `sum(metrics[k + "_percent"] for k in CLASSES[:5])` |
| `timeseries.py:238` | `sum()` | `sum(ev.get(k, {}).get("n", 0) for k in CLASSES[:5])` |
| `timeseries.py:250` | `json.dumps()` | `json.dumps([{column[0]: column[1] for column in columns}])` |
| `timeseries.py:251` | `json.dumps(allow_nan)` | `json.dumps([dict(zip(names, record)) for record in records], allow_nan=False)` |
| `timestamps.py:7` | `math.isfinite` | `math.isfinite(value)` |
| `topology.py:57` | `math.isfinite` | `math.isfinite(self.x)` |
| `topology.py:57` | `math.isfinite` | `math.isfinite(self.y)` |
| `topology.py:83` | `json.dumps(separators)` | `json.dumps(parts, separators=(",", ":"))` |
| `topology.py:165` | `.sort(key)` | `block.sort(key=lambda p: points[p].side != "ingress")` |
| `topology_suggest.py:99` | `min()` | `min(p["deltas_us"])` |
| `topology_suggest.py:101` | `max()` | `max(p["deltas_us"])` |
| `topology_suggest.py:180` | `sorted()` | `sorted(client_ips)` |
| `topology_suggest.py:197` | `sorted()` | `sorted(client_ips)` |
| `topology_suggest.py:198` | `sorted()` | `sorted(server_ips)` |
| `topology_suggest.py:237` | `int(expr)` | `int(r["sport"])` |
| `topology_suggest.py:238` | `int(expr)` | `int(r["dport"])` |
| `topology_suggest.py:244` | `int(expr)` | `int(r["sport"])` |
| `topology_suggest.py:245` | `int(expr)` | `int(r["dport"])` |
| `topology_suggest.py:274` | `sorted()` | `sorted(client_ips)` |
| `topology_suggest.py:291` | `sorted()` | `sorted(client_ips)` |
| `topology_suggest.py:292` | `sorted()` | `sorted(server_ips)` |
| `topology_suggest.py:293` | `sorted()` | `sorted(server_ports)` |
| `topology_suggest.py:297` | `sorted()` | `sorted(server_ports)` |
| `topology_suggest.py:326` | `a % b (modulo)` | `i % 4` |
| `topology_suggest.py:327` | `a // b` | `i // 4` |
| `topology_suggest.py:343` | `int(expr)` | `int(inv.get("prefilter_excluded") or 0)` |
| `topology_suggest.py:344` | `int(expr)` | `int(v)` |
| `topology_suggest.py:344` | `sum()` | `sum(int(v) for v in (inv.get("timestamp_excluded_counts") or {}).values())` |
| `topology_suggest.py:346` | `int(expr)` | `int(row[0] or 0)` |
| `topology_suggest.py:355` | `int(expr)` | `int(uniq_row[0] or 0)` |
| `topology_suggest.py:358` | `int(expr)` | `int(inv.get("prefilter_total") or 0)` |
| `topology_suggest.py:358` | `max()` | `max(int(inv.get("prefilter_total") or 0), prefilter_excluded + ts_excluded + total_packets)` |
| `topology_suggest.py:669` | `sorted()` | `sorted(c)` |
| `topology_suggest.py:669` | `sorted(key)` | `sorted(components, key=lambda c: sorted(c)[0])` |
| `topology_suggest.py:688` | `float(expr)` | `float(vals[-1])` |
| `topology_suggest.py:688` | `float(expr)` | `float(vals[0])` |
| `topology_suggest.py:692` | `float(expr)` | `float(vals[-1])` |
| `topology_suggest.py:692` | `float(expr)` | `float(vals[0])` |
| `topology_suggest.py:840` | `sorted(key)` | `sorted(range(len(units)), key=lambda idx: -wins[idx])` |
| `topology_suggest.py:862` | `sorted(key)` | `sorted(separate_unit_indices, key=lambda idx: -wins[idx])` |
| `topology_suggest.py:1042` | `sorted()` | `sorted(seen_cidrs & set(g_cidrs))` |
| `topology_suggest.py:1070` | `.sort(key)` | `groups.sort(key=lambda g: g["name"])` |
| `translation.py:73` | `min()` | `min(row["nf"], row["nr"])` |
| `translation_synthetic.py:55` | `a % b (modulo)` | `(seq + (delta if forward else r)) % 2**32` |
| `translation_synthetic.py:63` | `a % b (modulo)` | `(qseq + 777) % 2**32` |
| `translation_synthetic.py:65` | `a % b (modulo)` | `(ack + (r if forward else delta)) % 2**32` |
| `translation_synthetic.py:87` | `str(x)` | `str(port)` |
| `translation_synthetic.py:89` | `json.dumps(indent)` | `json.dumps(truth, indent=2)` |
| `translation_synthetic.py:90` | `json.dumps(indent)` | `json.dumps(topology, indent=2)` |
| `transport.py:72` | `sum()` | `sum([receiver, network, application])` |
| `transport.py:242` | `json.dumps()` | `json.dumps([report["generated_at"], 7])` |
| `transport.py:354` | `max(key)` | `max(valid, key=lambda p: p["ms"])` |
| `transport.py:379` | `int(expr)` | `int(v)` |
| `transport.py:417` | `json.dumps()` | `json.dumps(result)` |
| `udp_index.py:51` | `json.dumps(sort_keys)` | `json.dumps( [ VERSION, batch_headers, capture["identity"], topology.rtp_port_ranges, point.model_dump( exclude...` |
| `udp_index.py:75` | `str(x)` | `str(source)` |
| `udp_index.py:95` | `str(x)` | `str(inv.get('checkpoint_uuid', False))` |
| `udp_index.py:108` | `sum()` | `sum(p.capture_id == point.capture_id for p in topology.points)` |
| `udp_index.py:127` | `str(x)` | `str(source)` |
| `udp_index.py:131` | `str(x)` | `str(selected)` |
| `udp_index.py:137` | `str(x)` | `str(inv["frame_limit"])` |
| `udp_index.py:153` | `str(x)` | `str(selected)` |
| `udp_index.py:170` | `str(x)` | `str(inv["frame_limit"])` |
| `udp_index.py:183` | `json.dumps()` | `json.dumps([{field: "VARCHAR" for field in FIELDS}])` |
| `udp_index.py:190` | `json.dumps()` | `json.dumps(buffer)` |
| `udp_index.py:241` | `str(x)` | `str(exc)` |
| `udp_index.py:260` | `json.dumps()` | `json.dumps(selected)` |
| `udp_index.py:283` | `int(expr)` | `int(value)` |
| `udp_index.py:283` | `int(expr)` | `int(value, 16)` |
| `udp_index.py:290` | `json.dumps()` | `json.dumps(value)` |
| `udp_index.py:306` | `json.dumps()` | `json.dumps(wanted)` |
| `vendors.py:55` | `json.dumps()` | `json.dumps(decoded)` |
| `voip_synthetic.py:24` | `sorted()` | `sorted((i, t, pkt) for i, packets in enumerate(captures) for t, pkt in packets)` |
| `voip_synthetic.py:25` | `round(x)` | `round(t * 1e6)` |
| `voip_synthetic.py:31` | `a % b (modulo)` | `-len(pkt) % 4` |
| `voip_synthetic.py:176` | `json.dumps(separators)` | `json.dumps([proto, client, sport, server, dport], separators=(",", ":"))` |
| `voip_synthetic.py:179` | `json.dumps(separators)` | `json.dumps(["UDP", bad, 41000, server, 50000], separators=(",", ":"))` |
| `voip_synthetic.py:192` | `str(x)` | `str(file)` |
| `voip_synthetic.py:200` | `json.dumps(indent)` | `json.dumps(topology, indent=2)` |
| `voip_synthetic.py:201` | `json.dumps(indent)` | `json.dumps(truth, indent=2)` |
| `voip_synthetic.py:226` | `json.dumps(separators)` | `json.dumps(["UDP", clients[call], sport, peers[call], dport], separators=(",", ":"))` |
| `voip_synthetic.py:227` | `json.dumps(separators)` | `json.dumps(["UDP", pools[call], mapped, peers[call], dport], separators=(",", ":"))` |
| `voip_synthetic.py:269` | `a % b (modulo)` | `(65530 + n) % 65536` |
| `voip_synthetic.py:270` | `a % b (modulo)` | `(4294967000 + n * 160) % 4294967296` |
| `voip_synthetic.py:303` | `a % b (modulo)` | `n % 5` |
| `waterfall.py:17` | `a % b (modulo)` | `(p["seq"] - packet["seq"]) % MOD` |
| `waterfall.py:17` | `sorted(key)` | `sorted(packets, key=lambda p: (p["seq"] - packet["seq"]) % MOD)` |
| `waterfall.py:18` | `a % b (modulo)` | `(p["seq"] - packet["seq"]) % MOD` |
| `waterfall.py:22` | `min()` | `min(len(payload), len(data) - offset)` |
| `waterfall.py:40` | `int(expr)` | `int(lengths[0])` |
| `waterfall.py:48` | `sorted(key)` | `sorted(packets, key=lambda x: (x["ts"], x["frame"]))` |
| `waterfall.py:51` | `a % b (modulo)` | `(p["seq"] - seed["seq"]) % MOD` |
| `waterfall.py:52` | `min()` | `min(size, lo + p["length"])` |
| `waterfall.py:57` | `sorted()` | `sorted(covered)` |
| `waterfall.py:60` | `max()` | `max(end, b)` |
| `waterfall.py:160` | `max()` | `max(0.001, uncertainty or 0)` |
| `waterfall.py:229` | `a % b (modulo)` | `(syn["seq"] + 1) % MOD` |
| `waterfall.py:241` | `a % b (modulo)` | `(sa["seq"] + 1) % MOD` |
| `waterfall.py:242` | `a % b (modulo)` | `(syn["seq"] + 1) % MOD` |
| `waterfall.py:286` | `.sort(key)` | `candidates.sort(key=lambda p: p["ts"])` |
| `waterfall.py:295` | `min()` | `min(len(candidates), 100)` |
| `waterfall.py:311` | `a % b (modulo)` | `(p["ack"] - req["seq"]) % MOD` |
| `waterfall.py:312` | `a % b (modulo)` | `(p["ack"] - req["seq"]) % MOD` |
| `waterfall.py:312` | `a // b` | `MOD // 2` |
| `waterfall.py:439` | `.sort(key)` | `requests.sort(key=lambda p: p["ts"])` |

### DuckDB SQL aggregate sites (Python string literals)

| File:line | Construct | Source |
|---|---|---|
| `aggregates.py:168` | `DuckDB SQL floor()` | `SELECT point,floor(corrected) AS time,count(*) AS count,min(frame) AS frame FROM obs` |
| `aggregates.py:170` | `DuckDB SQL floor()` | `AND (? IS NULL OR corrected>=?) AND (? IS NULL OR corrected<?) GROUP BY point,floor(corrected)` |
| `analysis.py:567` | `DuckDB SQL quantile_cont()` | `SELECT least(.002,coalesce(quantile_cont(abs(delta-previous),.5),0)*4) FROM pairs` |
| `analysis.py:701` | `DuckDB SQL quantile_cont()` | `quantile_cont((time_b-time_a)*1000,.5) AS p50_ms,` |
| `analysis.py:702` | `DuckDB SQL quantile_cont()` | `quantile_cont((time_b-time_a)*1000,.05) AS p05_ms,` |
| `analysis.py:704` | `DuckDB SQL quantile_cont()` | `quantile_cont((time_b-time_a)*1000,.95) AS p95_ms,` |
| `analysis.py:1212` | `DuckDB SQL median()` | `SELECT point,median(ttl) AS median_ttl,median(corrected) AS median_time,count(*) AS samples` |
| `analysis.py:1212` | `DuckDB SQL median()` | `SELECT point,median(ttl) AS median_ttl,median(corrected) AS median_time,count(*) AS samples` |
| `cycles.py:54` | `DuckDB SQL floor()` | `CREATE OR REPLACE TABLE cycle_series AS SELECT point,flow,port,floor(request_first/?) * ? AS start,bool_and(cl` |
| `cycles.py:57` | `DuckDB SQL quantile_cont()` | `quantile_cont(cycle_ms,.5) FILTER(WHERE reason IS NULL) AS p50_ms,` |
| `cycles.py:58` | `DuckDB SQL quantile_cont()` | `quantile_cont(cycle_ms,.95) FILTER(WHERE reason IS NULL) AS p95_ms,` |
| `cycles.py:65` | `DuckDB SQL quantile_cont()` | `quantile_cont(cycle_ms,.95) FILTER(WHERE reason IS NULL) AS p95_ms,` |
| `cycles.py:67` | `DuckDB SQL quantile_cont()` | `quantile_cont(turnaround_ms,.95) FILTER(WHERE reason IS NULL) AS local_turnaround_p95_ms,` |
| `cycles.py:73` | `DuckDB SQL quantile_cont()` | `quantile_cont(cycle_ms,.95) FILTER(WHERE p.reason IS NULL) AS p95_ms,` |
| `cycles.py:163` | `DuckDB SQL median()` | `SELECT p.hop,p.flow,median(p.added_ms) AS baseline_ms,count(*) AS samples` |
| `delivery.py:28` | `DuckDB SQL avg()` | `count(*)>=4 AND count(DISTINCT id_a)>1 AND avg((id_a=id_b)::INTEGER)>=.5 AS preserved_ids` |
| `delivery.py:100` | `DuckDB SQL floor()` | `SELECT point_a,point_b,direction,flow,floor(time_a/?) * ? AS start,count(*) AS observations,` |
| `dns.py:129` | `DuckDB SQL quantile_cont()` | `quantile_cont(latency_ms,.5) AS p50_ms,quantile_cont(latency_ms,.95) AS p95_ms,max(latency_ms) AS max_ms` |
| `dns.py:129` | `DuckDB SQL quantile_cont()` | `quantile_cont(latency_ms,.5) AS p50_ms,quantile_cont(latency_ms,.95) AS p95_ms,max(latency_ms) AS max_ms` |
| `export.py:285` | `DuckDB SQL ceil()` | `canvas.width=Math.ceil(left+cell*count+10); canvas.height=segments.length*height+40;` |
| `export.py:297` | `DuckDB SQL floor()` | `ctx.fillStyle='#172a2d';for(const onset of (data.report.onsets?.items\|\|[])){if(onset.metric!==key)continue;con` |
| `export.py:302` | `DuckDB SQL floor()` | `const i=Math.floor(y/height), j=Math.floor((x-left)/cell);if(i<0\|\|i>=segments.length\|\|j<0\|\|j>=count)return;` |
| `export.py:302` | `DuckDB SQL floor()` | `const i=Math.floor(y/height), j=Math.floor((x-left)/cell);if(i<0\|\|i>=segments.length\|\|j<0\|\|j>=count)return;` |
| `host_path.py:53` | `DuckDB SQL floor()` | `CREATE OR REPLACE TABLE host_path_series AS SELECT hop,direction,flow,floor(time) AS start,floor(time)+1 AS "e` |
| `host_path.py:53` | `DuckDB SQL floor()` | `CREATE OR REPLACE TABLE host_path_series AS SELECT hop,direction,flow,floor(time) AS start,floor(time)+1 AS "e` |
| `host_path.py:54` | `DuckDB SQL quantile_cont()` | `count(*) AS matched_ranges,sum(bytes) AS bytes,quantile_cont(delay_ms,.5) AS p50_ms,quantile_cont(delay_ms,.95` |
| `host_path.py:54` | `DuckDB SQL quantile_cont()` | `count(*) AS matched_ranges,sum(bytes) AS bytes,quantile_cont(delay_ms,.5) AS p50_ms,quantile_cont(delay_ms,.95` |
| `host_path.py:62` | `DuckDB SQL quantile_cont()` | `SELECT point,flow,count(*) AS samples,quantile_cont((response-complete)*1000,.95) AS p95_ms,` |
| `host_path.py:84` | `DuckDB SQL quantile_cont()` | `SELECT q.point,q.flow,count(*) AS samples,quantile_cont((r.first_time-q.last_time)*1000,.95) AS p95_ms,` |
| `host_path.py:95` | `DuckDB SQL quantile_cont()` | `SELECT count(*),quantile_cont((response-complete)*1000,.95) FROM proxy_requests` |
| `host_path.py:101` | `DuckDB SQL quantile_cont()` | `SELECT hop,direction,flow,count(*) AS matched_ranges,sum(bytes) AS bytes,quantile_cont(delay_ms,.95) AS p95_ms` |
| `host_path.py:162` | `DuckDB SQL quantile_cont()` | `quantile_cont(delay_ms,.95) AS p95_ms,max(delay_ms) AS max_ms,` |
| `host_path.py:170` | `DuckDB SQL floor()` | `SELECT floor(time) AS start,floor(time)+1 AS "end",` |
| `host_path.py:170` | `DuckDB SQL floor()` | `SELECT floor(time) AS start,floor(time)+1 AS "end",` |
| `host_path.py:171` | `DuckDB SQL quantile_cont()` | `quantile_cont(delay_ms,.95) AS p95_ms,max(delay_ms) AS max_ms,` |
| `host_path.py:207` | `DuckDB SQL quantile_cont()` | `count(*) AS matched_ranges,sum(bytes) AS bytes,quantile_cont(delay_ms,.95) AS p95_ms,max(delay_ms) AS max_ms` |
| `host_path.py:241` | `DuckDB SQL quantile_cont()` | `SELECT count(*) AS samples,quantile_cont(turnaround_ms,.95) AS p95_ms` |
| `host_path.py:445` | `DuckDB SQL median()` | `median(recovery_ms) AS median_recovery_ms` |
| `incident.py:37` | `DuckDB SQL floor()` | `retrans AS(SELECT floor(coalesce(corrected,ts)) AS start,count(*) AS retransmissions,arg_min(frame,coalesce(co` |
| `incident.py:156` | `DuckDB SQL quantile_cont()` | `FROM(SELECT flow,hop,component,quantile_cont(added_ms,.95) AS p95_ms FROM cycle_pairs GROUP BY flow,hop,compon` |
| `incident.py:165` | `DuckDB SQL floor()` | `SELECT e.point_a,e.point_b,e.direction,floor(e.time_a) AS start,count(DISTINCT e.key_a) AS offload_mismatch` |
| `matching.py:47` | `DuckDB SQL median()` | `SELECT o.point,o.direction,median(o.corrected-r.corrected) AS shift` |
| `microbursts.py:108` | `DuckDB SQL floor()` | `SELECT point,floor(ts/` |
| `microbursts.py:109` | `DuckDB SQL median()` | `SELECT point,median(bps) AS baseline_bps,greatest(1000000,4*median(bps)) AS threshold_bps FROM bins GROUP BY p` |
| `microbursts.py:109` | `DuckDB SQL median()` | `SELECT point,median(bps) AS baseline_bps,greatest(1000000,4*median(bps)) AS threshold_bps FROM bins GROUP BY p` |
| `pilot.py:84` | `DuckDB SQL quantile_cont()` | `SELECT point_a,point_b,direction,quantile_cont((time_b-time_a)*1000,.95) AS p95_ms` |
| `retransmissions.py:29` | `DuckDB SQL median()` | `LEFT JOIN LATERAL(SELECT count(*) AS prior_rtts,median(rtt) AS prior_median,stddev_pop(rtt) AS variation` |
| `retransmissions.py:29` | `DuckDB SQL stddev_pop()` | `LEFT JOIN LATERAL(SELECT count(*) AS prior_rtts,median(rtt) AS prior_median,stddev_pop(rtt) AS variation` |
| `rtp.py:59` | `DuckDB SQL floor()` | `floor((rn-1)/512)::BIGINT AS block,((rn-1)%512)+1 AS pos FROM d` |
| `rtp.py:81` | `DuckDB SQL quantile_cont()` | `CASE WHEN count(DISTINCT rate)=1 AND count(*) FILTER(WHERE abs(ds)>3000)=0 THEN quantile_cont(jitter_ms,.95) E` |
| `rtp.py:146` | `DuckDB SQL quantile_cont()` | `quantile_cont(abs(transit_ms-previous_transit),.95) AS added_variation_p95_ms,` |
| `security.py:269` | `DuckDB SQL floor()` | `INSERT INTO security_cohorts SELECT point,floor((s.start-` |
| `security.py:396` | `DuckDB SQL median()` | `) AS baseline_rate,median(b.half_open) AS baseline_half_open,` |
| `security.py:396` | `DuckDB SQL median()` | `SELECT b.point,median(b.syns/` |
| `stream_charts.py:101` | `DuckDB SQL floor()` | `WITH b AS(SELECT floor((` |
| `stream_charts.py:103` | `DuckDB SQL median()` | `(median(rtt) FILTER(WHERE direction<>? AND trusted))*1000 AS rtt_ms,` |
| `stream_charts.py:109` | `DuckDB SQL ceil()` | `SELECT unnest(range(0,least(10000,ceil((?-?)/?)::BIGINT+1))) AS bucket)` |
| `timeseries.py:86` | `DuckDB SQL floor()` | `SELECT floor((ts-?)/?)::BIGINT AS bucket,kind,count(*) AS n,` |
| `timeseries.py:121` | `DuckDB SQL floor()` | `floor((corrected-` |
| `timeseries.py:137` | `DuckDB SQL median()` | `median(rtt)*1000 AS rtt_ms,count(DISTINCT flow) FILTER(WHERE proto='TCP' AND (flags&18)=2 AND NOT retrans) AS ` |
| `timeseries.py:156` | `DuckDB SQL floor()` | `SELECT floor((time_a-?)/?)::BIGINT AS bucket,count(*) AS matched,` |
| `timeseries.py:157` | `DuckDB SQL quantile_cont()` | `quantile_cont((time_b-time_a)*1000,.5) AS latency_p50_ms,` |
| `timeseries.py:158` | `DuckDB SQL quantile_cont()` | `quantile_cont((time_b-time_a)*1000,.95) AS latency_p95_ms,` |
| `topology_suggest.py:67` | `DuckDB SQL median()` | `WHERE a.capture_id<b.capture_id), med AS(SELECT a,b,direction,count(*) AS n,median(delta) AS us,` |
| `topology_suggest.py:68` | `DuckDB SQL median()` | `quantile_cont(delta,.95)-quantile_cont(delta,.05) AS spread_us,median(ttl) AS ttl,` |
| `topology_suggest.py:68` | `DuckDB SQL quantile_cont()` | `quantile_cont(delta,.95)-quantile_cont(delta,.05) AS spread_us,median(ttl) AS ttl,` |
| `topology_suggest.py:68` | `DuckDB SQL quantile_cont()` | `quantile_cont(delta,.95)-quantile_cont(delta,.05) AS spread_us,median(ttl) AS ttl,` |
| `topology_suggest.py:508` | `DuckDB SQL median()` | `median(p.ttl) FILTER (WHERE p.src IN (` |
| `topology_suggest.py:509` | `DuckDB SQL median()` | `median(p.ttl) FILTER (WHERE p.dst IN (` |
| `topology_suggest.py:568` | `DuckDB SQL median()` | `median((b.ts - a.ts) * 1e6) AS delta_us,` |
| `topology_suggest.py:569` | `DuckDB SQL quantile_cont()` | `quantile_cont((b.ts - a.ts) * 1e6, 0.95) - quantile_cont((b.ts - a.ts) * 1e6, 0.05) AS spread_us,` |
| `topology_suggest.py:569` | `DuckDB SQL quantile_cont()` | `quantile_cont((b.ts - a.ts) * 1e6, 0.95) - quantile_cont((b.ts - a.ts) * 1e6, 0.05) AS spread_us,` |
| `topology_suggest.py:570` | `DuckDB SQL median()` | `median(a.ttl - b.ttl) AS ttl_step,` |
| `transport.py:183` | `DuckDB SQL quantile_cont()` | `count(*) FILTER(WHERE ack_delay_ms IS NOT NULL) AS ack_samples,quantile_cont(ack_delay_ms,.95) AS ack_delay_p9` |
| `transport.py:215` | `DuckDB SQL quantile_cont()` | `SELECT flow,direction,hop,count(*) AS samples,quantile_cont(ms,.95) AS p95_ms,` |
| `transport.py:216` | `DuckDB SQL median()` | `median(ms) FILTER(WHERE ts<=first_ts+(last_ts-first_ts)*.25) AS baseline,` |
| `transport.py:217` | `DuckDB SQL median()` | `median(ms) FILTER(WHERE ts>first_ts+(last_ts-first_ts)*.25) AS later,` |
| `transport.py:224` | `DuckDB SQL median()` | `SELECT flow,point,direction,count(*) AS rtt_samples,median(rtt)*1000 AS rtt_ms,` |
| `transport.py:226` | `DuckDB SQL median()` | `AND median(rtt) FILTER(WHERE ts>lo+(hi-lo)*.25)>greatest(.1,2*median(rtt) FILTER(WHERE ts<=lo+(hi-lo)*.25)) AS` |
| `transport.py:226` | `DuckDB SQL median()` | `AND median(rtt) FILTER(WHERE ts>lo+(hi-lo)*.25)>greatest(.1,2*median(rtt) FILTER(WHERE ts<=lo+(hi-lo)*.25)) AS` |
| `udp.py:88` | `DuckDB SQL quantile_cont()` | `quantile_cont(latency_ms,.5) AS p50_ms,quantile_cont(latency_ms,.95) AS p95_ms,max(latency_ms) AS max_ms FROM ` |
| `udp.py:88` | `DuckDB SQL quantile_cont()` | `quantile_cont(latency_ms,.5) AS p50_ms,quantile_cont(latency_ms,.95) AS p95_ms,max(latency_ms) AS max_ms FROM ` |
