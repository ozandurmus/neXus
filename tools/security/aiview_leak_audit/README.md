# aiview masking leak audit

Compares what the aiview persona (`role:replay_viewer`) can read through the real service against the raw device
tables and counts how many raw identifiers survive masking. Output is counts by route and category only. No raw
value, hash, identifier, header or response fragment is ever printed or stored (unit-tested).

Files:

- `export_reference.sql` -- read-only SQL, run inside `ui2-db-0`; prints salted sha256 hashes of normalized raw
  identifiers (device hostnames, management/interface/discovery addresses and each IPv4 in them, serials and recorded
  identities, cluster references, policy-change authors and credential user names, Check Point server/domain names)
  and of the P1 planes where their tables exist (ACI cutover segment names, blocks, peer blocks and assessment flow
  addresses; Zone Matrix zone names and blocks from the version content; static-route preview destinations, next hops,
  representative hosts, requesters and units; segment, assessment and zone-matrix actors).
- `probe.mjs` + `core.mjs` -- zero-dependency Node probe; opens a machine session as aiview, walks `routes.json`,
  hashes every token of every response body and header with the same salt and normalization, counts matches.
- `routes.json` -- the 157 GET routes the golden decision table lets replay_viewer call (PERMIT), static/health/SAML
  routes removed. Regenerate: `python3 tools/security/aiview_leak_audit/gen_routes.py` (a test fails when stale).
- `probe-pod.yaml` -- one-shot pod (e2e image, same labels and machine-token secret as the `ui2-e2e` job).

## Run (orchestrator, from the repository root)

Do not run any step while a deploy e2e or a ship is running (the machine session of an overlapping run is revoked).
The probe session is a probe-purpose machine session; the walk is GET-only, 2 concurrent requests, 150 ms apart.

```sh
E2E_IMAGE=$(kubectl -n ui2 get cronjob ui2-e2e -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].image}')
sed "s#SET_E2E_IMAGE#$E2E_IMAGE#" tools/security/aiview_leak_audit/probe-pod.yaml | kubectl apply -f -
kubectl -n ui2 wait --for=condition=Ready pod/aiview-leak-audit --timeout=180s

# 1. code (not sensitive) into the pod
tar -C tools/security/aiview_leak_audit -cf - probe.mjs core.mjs routes.json | kubectl -n ui2 exec -i aiview-leak-audit -- tar -C /work -xf -

# 2. random salt, generated in the pod
SALT=$(kubectl -n ui2 exec aiview-leak-audit -- sh -c 'head -c 16 /dev/urandom | od -An -tx1 | tr -d " \n"')

# 3. reference hashes, streamed from the database pod into the probe pod (never written to this Mac)
kubectl -n ui2 exec -i ui2-db-0 -- sh -c \
  'psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-$POSTGRESQL_USER}" -d ui2 -At -v salt='"$SALT"' -f -' \
  < tools/security/aiview_leak_audit/export_reference.sql \
  | kubectl -n ui2 exec -i aiview-leak-audit -- sh -c 'cat > /work/ref.tsv'

# 4. the audit (about 320 requests; table to stdout, add --json for machine-readable)
kubectl -n ui2 exec aiview-leak-audit -- node /work/probe.mjs --ref /work/ref.tsv --salt "$SALT" --json > leak-audit-result.json

# 5. clean up
kubectl -n ui2 delete pod aiview-leak-audit
```

The result file holds counts and route templates only and may be read freely. Options: `--max-requests 400`
(default and hard cap 1000), `--concurrency 2` (max 4), `--delay-ms 150`, `--details-per-route 3`.

## Reading the result

- `reference.entries_by_category` and `source_columns` show what the comparison covered; a category with 0 entries was not tested.
- `totals.matches` -- raw tokens found, per category. HOST, ADDR, IPV4 and SERIAL matches are leaks to investigate;
  USER and CLUSTER matches are leaks unless the value is a deliberate public label; NAME_WORD is low confidence (words
  of 4+ characters of user and cluster names, so ordinary words can match) -- use it to find candidates, not as a verdict.
- `routes[<route>].matches` and `header_matches` -- where it was found. Re-run the route by hand under aiview and
  inspect the masked field name (never print the value) to fix the projection.
- `routes_without_ok_response` -- routes with no 2xx (e.g. 404 for a sampled identifier): not covered, not clean.
- `routes_not_probed` -- templated routes for which no identifier was found in earlier responses, or the budget ran out.

## Estimates

- Requests: 75 routes without identifiers + up to 3 sampled identifiers for each of 82 routes = at most 321 (cap 400).
- Runtime: about 3-8 minutes (2 concurrent, 150 ms pause, 0.2-2 s per request).
- Memory: bodies are read up to 4 MiB each (larger bodies are scanned for the first 4 MiB and counted as truncated; a
  declared size above 32 MiB is skipped and counted).

## Covered and not covered

Covered: every token of the response body and of all response headers (except Set-Cookie) of every replay_viewer GET
route in the decision table, for hostnames (full name, dot prefixes, host before :port), IPv4 addresses anywhere in
the text, serials/recorded identities, cluster references, user names, and 4+ character words of user and cluster names.

Source columns (`export_reference.sql`, each guarded by `information_schema.columns`, so a schema without a table is
skipped and shows as absent in the `#src` lines):

| Category | Columns |
| --- | --- |
| HOST | `devices.observed_hostname`, `discovery_candidate.display_name`, `cp_domain_server_placement.*_name`, `cutover_segment.name`, `zone_matrix_version.content` `zones[].name` |
| ADDR | endpoint, interface, discovery and trust-store addresses; `cutover_segment.blocks[]` and `peer_blocks[]`; `cutover_assessment_flow.flow` `flowSource`, `flowDestination`, `flowSources[]`, `sourceRange`; `zone_matrix_version.content` `zones[].blocks[]`; `static_route_preview.destination_cidr`, `nexthop_ip`, `representative_host` |
| SERIAL | platform serials, recorded and presented identities, discovery stable identifiers, Panorama serials, `ha_pair_identity` unit serials |
| CLUSTER | `devices.cluster_member_ref`, `devices.ha_pair_claim`, `discovery_candidate.cluster_reference`, `static_route_preview.unit_id` |
| USER | `policy_rule_history.changed_by`, `credentials.username`, `cutover_segment.created_by`/`updated_by`, `cutover_assessment.actor`, `zone_matrix_version.created_by`/`updated_by`/`published_by`, `zone_matrix_revision.actor`, `static_route_preview.requested_by` |

The ACI cutover plane is stored as `cutover_segment` (name, `blocks`, `peer_blocks`) and `cutover_assessment_flow.flow`
(JSONB); there is no `aci_segment` table and no flow address column. The zone-matrix version content is TEXT holding
canonical JSON and is parsed per row (`pg_input_is_valid`, PostgreSQL 16); a value that is not valid JSON or not of the
expected shape contributes nothing. A block `a/n` is also exported as its address part `a` (ADDR, with trailing
separators removed as the probe tokenizes), so a block printed as text is found by its network address (IPv4 also by
the IPv4 scan, an IPv6 block by its `2001:db8:a` style prefix token).

Not covered: values shorter than 4 characters and the placeholders localhost/unknown/none/null/true/false/default/
system/test; loopback and 0.0.0.0; IPv6 addresses other than a whole stored value or the prefix of a stored IPv6
block (an IPv6 address printed in another spelling, or a host inside an IPv6 block); values split, encoded or transformed in the response (base64, URL
encoding, HTML entities, JSON unicode escapes, split across fields, digits inserted); non-GET routes (aiview POSTs for
diagnostics, readiness, policy collection and Packet Analysis are not exercised); detail ids the walk could not discover
from list responses; request parameters (default parameters only, so filtered/paged views and `?device=` style
variants are not walked); masked-but-reversible values (a pseudonym that encodes the raw value) -- this audit finds raw
values, not weak masking; raw values that are not in the source columns listed in `export_reference.sql` (for example
policy rule/object names, Zone Matrix finding rule/section names and witness tuples in `zone_matrix_finding`, zone
descriptions, cell rationale and regulation references, which the system-log catalog and the in-process sweep
`AiviewRawVersusMaskedSweepTest` cover separately) and binary payloads (scanned as text).
Multi-word user names are matched word by word (NAME_WORD), not as a phrase.
