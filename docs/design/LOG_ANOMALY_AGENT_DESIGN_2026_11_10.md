# Log anomaly agent — design (system-log analysis with an LLM explainer)

**Status: DRAFT — DO NOT IMPLEMENT.** This is a design proposal. It authorizes no code, schema, manifest, device
command, outbound connection or credential. Implementation needs the security review of this design, the PO decisions
in §12 and a frozen contract derived from it. Every value marked PROVISIONAL is a starting value, and every UNKNOWN
stays UNKNOWN until measured or cited.

- **Request (PO, 2026-11-10):** an agent inside the product that uses Claude and Antigravity/Gemini, first for device
  system-log analysis and anomaly explanation.
- **Conditions set in the PO session (2026-11-10):** only masked data leaves the product; deterministic detection runs
  first and the LLM only explains; the agent is read-only and audited; a provider abstraction allows an on-prem model
  later. The company's reference stack has no LLM slot, so approval of any outbound connection is uncertain.
- **Process:** this design, then an Opus security review, then the PO go-ahead, then coding lanes.
- **Inputs read:**
  [PO_DECISION_RECORD_2026_10_15_INTERFACE_MAC_ZONE_AND_SYSTEM_LOGS.md](PO_DECISION_RECORD_2026_10_15_INTERFACE_MAC_ZONE_AND_SYSTEM_LOGS.md)
  §3 (RATIFIED), [DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md](DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md) (DRAFT),
  [DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md](DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md) (APPROVED),
  [PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md](PO_DECISION_RECORD_2026_10_17_SYSTEM_LOG_PHASE_B.md),
  [DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md](DEVICE_SYSTEM_LOG_MEASUREMENT_FINDINGS_2026_10_17.md),
  [PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md](PO_DECISION_RECORD_2026_10_30_LOCAL_LOG_DIAGNOSTICS.md),
  [DATA_SANITIZER_CONTRACT.md](DATA_SANITIZER_CONTRACT.md),
  [PO_DECISION_RECORD_2026_11_05_ANALYSIS_CONTAINER_DIRECTION.md](PO_DECISION_RECORD_2026_11_05_ANALYSIS_CONTAINER_DIRECTION.md)
  (RATIFIED), [ANALYSIS_CONTAINER_DESIGN_2026_11_05.md](ANALYSIS_CONTAINER_DESIGN_2026_11_05.md) (DRAFT),
  [ANTIGRAVITY_PROVIDER_MEASUREMENT_2_2026_09_14.md](ANTIGRAVITY_PROVIDER_MEASUREMENT_2_2026_09_14.md) (measurement
  record, not authority), `AGENTS.md` (aiview masking law, UNKNOWN law, vendor semantics law, raw-evidence law) and
  `PRIVACY_AND_DATA_HANDLING.md`.
- **Source baseline:** `origin/main` at `eea3bafb`. Repository reading only: no live system, device or database was
  queried. `graphify-out/graph.json` does not exist in this checkout, so the graph was not used.
- **Provider facts:** checked on 2026-11-10 against the vendors' public documentation (§13). Prices and limits change;
  the adapters read them from configuration, never from this document.
- **Where the brief's eight items are:** masking §3, detection split §4, provider abstraction §5, tools §6,
  placement §7, UI and output §8, risks and rollout §9–§10, open PO decisions §12.

## 0. Summary

1. **Two parts, built in this order.** First a deterministic detector over the existing log pool (no LLM, no outbound
   connection, no new container). Then an LLM explainer on top of the detector's findings. The first part is useful by
   itself and is the missing phase C of the system-log programme; the second part is the only one that needs an
   egress decision.
2. **No finding without a deterministic trigger.** The LLM never scans logs for problems. It receives a finding that
   code has already produced and returns an explanation, a triage suggestion and a summary. It cannot create, close or
   re-grade a finding, and its text is shown as advice, never as evidence (§4.1).
3. **One masking choke point, fail closed.** An outbound request can be built only from a sealed, masked payload that
   passed an egress guard. Any doubt blocks the whole request; nothing is ever retried with weaker masking
   (§3.4–§3.5).
4. **Stricter than aiview.** The outbound form starts from the aiview projection (`logmask-v4`) and adds a closed
   vocabulary filter, request-scoped aliases and relative dates, so the provider cannot link two requests about the
   same device (§3.3).
5. **Two disclosure levels.** Level 1 sends message patterns and counts only. Level 2 also sends masked excerpt lines.
   The design supports both behind one setting and recommends piloting Level 1 first (§3.1).
6. **One provider interface, four adapters:** Claude API, Gemini API, a local model endpoint, and a mock for tests.
   The product calls the Gemini API directly, not the Antigravity SDK (§5.3).
7. **Read-only by construction.** Phase 2 has no tools at all (one structured call per finding). Phase 3 adds a bounded
   tool loop with seven read tools. No device command, no job start, no setting write (§6).
8. **Placement:** detection runs as a worker job; masking, RBAC and audit stay in ui2-service; one new small container,
   `ui2-llm-gateway`, is the only pod allowed to reach a provider and holds only the provider key and masked data (§7).
9. **Cost is small and bounded by design.** At the proposed caps a device-day with two findings costs about USD 0.05
   on Claude Opus 5.5 and about USD 0.001 on Claude Haiku 5.5; a quiet device costs nothing (§4.8).
10. **A wording conflict to settle.** The ratified record allows an AI API with "anonymised data only"; the DRAFT
    programme contract narrows that to "anonymised patterns and counts"; the 2026-11-10 brief says "masked excerpts".
    This design does not reconcile them; it is PO decision 1 (§1.2, §12).

## 1. Starting point

### 1.1 What exists today (from source and contracts)

| Fact | Where |
|---|---|
| Per-device system-log chunks are collected every 6 hours, redacted for secrets on write, gzip-compressed and envelope-encrypted in the artefact store; raw retention 30 days | phase B contract §3, §5, §12 |
| No table holds log text. `system_log_chunk` holds device, source, offsets, byte and line counts and first/last line time; `system_log_gap` records every known loss | phase B §5.2 |
| The parsed event index and the pattern catalog do not exist. Phase B "stores chunks and counters only"; the index "belongs to phase C" | phase B §2.2 |
| The masked read of stored log text is `LogMasking` (rule set `logmask-v4`): serial rule, JSON identity-field rule, Data Sanitizer text rules, estate dictionary (device and cluster names, addresses, serials), heuristic free-text pass. It refuses to serve a page when the dictionary cannot be built and no earlier one is cached | `ui2/service/.../systemlog/LogMasking.java`, `FreeTextIdentityRules.java`, `SystemLogReadConfiguration.java` |
| Masking runs in the service Gradle module: the worker and the other listed modules may not depend on the sanitizer | `SanitizerModuleFenceTest` |
| The Data Sanitizer has a mapping vault and a revert path: tokens that came back from an external tool are replaced by their originals only when the vault holds them | [DATA_SANITIZER_CONTRACT.md](DATA_SANITIZER_CONTRACT.md) §2, `TextReverter` |
| Deterministic log-derived facts already exist for one screen: sar CPU and memory p95, cpwd restart lines (rule PROVISIONAL), crash-file alerts | `SystemHealthService`, `CpwdRestartLines` |
| Volumes: Palo Alto `pan_system` median 1,770 entries per firewall per day (p90 4,267, max 21,985), about 1,168 bytes per entry, 39 firewalls. Check Point growth per day is PENDING (53 gateways measured for presence and line shape only) | findings record §5; phase B §11 |
| Line timestamps are device-local with no zone. `fwd.elg` carries a timestamp on 3.2 % of sampled lines, `cpd.elg` 57.7 %, `fwk.elg` about 69 %, `cpwd.elg` 95.9 %, `messages` and Palo Alto 100 % | phase B §4.7 |
| Management servers are excluded from collection by default; traffic and threat logs are out of scope of the pool | phase B §2.2, §2.3 |
| The product has no LLM client and no service-to-service authentication today. A search of URL literals in the service and worker sources and the manifests found no runtime call to an internet service | source search; analysis design §4.2 |

### 1.2 What the records already say about an agent — and a contradiction to report

- **Ratified (2026-10-15 record §3 D):** "An optional agent may search public sources and, when an account is
  connected in settings, query an AI API with **anonymised** data only, and show its suggestions as advisory warnings."
  Phase C is "AI-assisted mapping of log patterns to priorities ... the mapping stays editable". The same record says:
  "No log data leaves HOST-A un-anonymised."
- **DRAFT (programme contract §5):** "The optional AI agent (phase D) receives only anonymised patterns and counts,
  and is opt-in in settings."
- **Brief of 2026-11-10:** the LLM "explains, triages and summarises the flagged excerpts".

`AGENTS.md` forbids silently reconciling two authorities. The ratified record permits anonymised data; the DRAFT
contract is narrower (patterns and counts, no lines); the brief is wider (excerpt lines). §3.1 designs both as
levels, and §12 decision 1 asks the PO to choose. If Level 2 is chosen, the programme contract §5 sentence must be
amended by a PO record before implementation.

A terminology note for the security review: the records say "anonymised". What this design produces is
**pseudonymised** data: neXus keeps the key and the mapping, so neXus can reverse it and nobody else can. It is not
anonymous in the strict sense, and §3.7 lists what an outside party still learns.

### 1.3 Terms

| Term | Meaning here |
|---|---|
| Template | A log message with its variable parts removed, for example `State change: <STATE> -> <STATE>, reason: interface <IF> is down`. Many lines share one template |
| Finding | A record produced by a deterministic detector: device, time window, kind, severity, numbers, and pointers to the lines that triggered it |
| Excerpt | The few stored lines a finding points at, in masked form |
| Egress | Any byte that leaves the product for a model provider |
| Sealed prompt | The only object a provider adapter accepts: a masked payload that passed the egress guard, with its audit id |
| Provider | Whatever runs the model: Claude API, Gemini API, a local model server, or the test mock |
| Alias | A short stand-in that exists for one request only, for example `DEV-1`; the same device gets a different alias in the next request |

### 1.4 Non-goals

- No device command, diagnostic run, collection start, schedule or setting change by the agent, in any phase.
- No traffic or threat logs (not collected by neXus; §12 decision 2), no management-server logs (collection is off).
- No web search or other provider-side tool. The ratified record mentions public-source search; it is left out here
  because a search query is itself an egress channel and Gemini's search grounding stores prompts for 30 days.
- No automatic action from an explanation: no notification, ticket, readiness input or compliance result.
- No machine-learning model trained on customer logs.

## 2. Architecture overview

```
 firewalls --(existing collection, unchanged)--> ui2-worker --> artefact store (encrypted chunks)
                                                     |
                               [A] index + detect job (after each collection window)
                                                     |  template ids, counts, findings; no log text
                                                     v
                                                PostgreSQL   <-- read by ui2-service

 browser --> ui2-service   RBAC, audit, Findings API, masking authority
                 |   [B] build finding card: read <= 40 lines by pointer, mask, apply egress
                 |       profile, run egress guard --> sealed prompt (or EGRESS_BLOCKED)
                 |   signed internal call, masked payload only
                 v
           ui2-llm-gateway (new, phase 2)   provider adapters, rate limit, second shape check;
                 |                          holds the provider key; no database, no device route
                 v   HTTPS through an allowlisted forward proxy
       Claude API  |  Gemini API  |  local model endpoint
```

The response returns the same way. ui2-service validates it, stores it in masked form and writes the audit record. The
display path maps aliases back (§3.6). Step [A] needs no decision about egress and can ship alone.

## 3. Masking boundary

### 3.1 Disclosure levels

| Level | What leaves | Use |
|---|---|---|
| 0 — none | Nothing. Provider `none` (the default), or a local model inside the site | Detector only; local model later |
| 1 — patterns and counts | Templates, counts per time bucket, baseline numbers, detector facts, a device card with aliases, vendor, model family and software version. No log line | Matches the DRAFT contract wording. Recommended for the first external pilot |
| 2 — masked excerpts | Level 1 plus at most 40 masked lines per finding (§4.7) | Matches the brief. Better explanations for rare messages; larger disclosure |

Templates are built with the closed-vocabulary filter of §3.3 (§4.3), so a template holds only vocabulary words,
vendor enumeration values and typed placeholders. At egress the estate-dictionary pass and the guard run again, which
covers a name that happens to be a vocabulary word. The level is one setting, written by `security_admin`, audited,
default 0.

Recommendation: pilot Level 1, measure explanation quality on the evaluation set (§11), and move to Level 2 only if
Level 1 is measurably insufficient. Falling back from Level 2 to Level 1 is always allowed, because Level 1 is
stricter.

### 3.2 What is masked

"Today" is the aiview projection (`logmask-v4`). "At egress" is what this design adds.

| Field class | Today (aiview projection) | At egress (added) |
|---|---|---|
| IPv4/IPv6 addresses, CIDRs | Keyed prefix-preserving permutation; loopback, multicast, netmask ranges kept | Same construction under a per-request key, so addresses are not linkable across requests |
| Inventory device names | `TopologyNamePseudonymizer` pseudonym (`FW-TANGO-04` form) | Replaced by a request alias (`DEV-1`); cluster and peer by `CLS-1`, `PEER-1` |
| Other hostnames, FQDNs, e-mail, URL hosts | `h-` + 10 hex per label, public suffix kept; elg and sar header hosts; the word after a host keyword | Replaced by `HOST-n` aliases; URL paths and query strings dropped |
| Users and administrators | `USR-` + 10 hex for keyed fields and recognised mentions | Replaced by `USER-n` aliases |
| Serials | `SN-` + 10 hex (JSON serial keys, `serial=` pairs, serial-shaped tokens) | Replaced by `SER-n`; Palo Alto `serial` and `device_name` fields are not sent at all |
| MAC addresses | Vendor half kept, device half keyed | Replaced by `MAC-n` |
| Rule, policy, object, zone, virtual-system and domain names | **Gap for log text.** The policy screens mask these (`PolicyPrivacy`), the log projection has no rule for them | Names known from stored policy and inventory snapshots are added to the estate dictionary; every other non-vocabulary word is suppressed by the vocabulary filter |
| Interface names | Kept (`eth1-01`, `bond0.100`) | Kept: needed for diagnosis, not an identity |
| Software versions, daemon names, vendor event ids | Kept | Kept |
| Secrets | Redacted on write, before encryption (`cp-text-v1`, `pan-config-v2`) | Redaction re-run as a check; any new hit blocks the request |
| Calendar dates | Kept | Replaced by a day offset relative to the finding window (`D-1 03:12:44`); time of day and order kept |
| File names | Stored as keyed digests; masked on display | Not sent |

Sources eligible for Level 2 are an allowlist: `cp_messages`, `cp_cpwd_elg`, `cp_cpd_elg`, `cp_fwd_elg`,
`cp_fwk_elg` and `pan_system` (fields `receive_time`, `severity`, `type`, `subtype`, `eventid`, `module`, `object`,
`opaque` only). Excluded in every level: Palo Alto config-log change previews and paths, HCP archives and Spike
Detective data (not redacted), sar text (its numbers reach the model only as system-health facts), `cp_mq_mng_elg`
until its line form is measured, and crash-file names.

### 3.3 The egress profile

The aiview projection is a block list with heuristics: it masks what it recognises. The free-text pass states its own
rule, "over-masking is accepted, under-masking is not", but a plain-word hostname or user name in an unusual position
can still pass. That is acceptable for a masked screen inside the product. It is not enough for data that leaves. The
egress profile therefore adds an allow list on top:

1. **Closed vocabulary.** After masking, a word survives only if it is (a) in a fixed vocabulary shipped with the
   product (English words, vendor daemon and keyword lists, protocol and unit names), (b) an alias this request
   generated, or (c) an allowed shape: a number of up to five digits, a number right after a code keyword (`signal`,
   `exit`, `errno`, `status`, `code`), a hex literal of at most four hex digits after `0x` (an error code or an offset),
   an interface name, a time of day, a version, a vendor event id. `0x` plus five or more hex digits, eight in
   particular (the hex form of an IPv4 address), becomes the typed placeholder `<HEX>` (planted-identity test E5,
   finding 6; decision note `LOG_MASKING_V6_TURKISH_FOLD_AND_HEX_ADDRESS_2026_11_11.md`). Every other word
   becomes `<W>`. Organisation-specific names are, almost by definition, not vocabulary words.
2. **Estate dictionary check.** A vocabulary word that equals a known identity (a device called `falcon`) is still
   masked, because the dictionary pass runs first and the guard checks again (§3.5).
3. **Request-scoped aliases.** Every pseudonym is replaced by an alias numbered in order of appearance. The alias map
   (alias, standard pseudonym, vault reference to the original) is kept encrypted inside neXus with the call record.
4. **Relative dates.** Calendar dates become day offsets.
5. **Field and source allowlists** as in §3.2.

The profile has its own version id (`egress-v1`), carried in every payload and audit record next to `logmask-v4`. The
vocabulary is a build artefact with a digest; changing it changes the profile version. Over-suppression lowers
explanation quality and is measured as a suppression rate (§11); words suppressed most often can be reviewed by
`security_admin` and added to the vocabulary by a normal change.

### 3.4 Where masking is enforced in code

| Layer | Rule | How it is kept |
|---|---|---|
| Type | A provider adapter's only input type is the sealed prompt. Its constructor is reachable only from the egress guard | Package-private constructor; ArchUnit fence |
| Single builder | One class in ui2-service builds outbound payloads (`service.logagent.egress`). It calls `LogMasking`, then the egress profile, then the guard, on the final serialized bytes | ArchUnit: no other class may construct request bodies for the gateway client |
| Masking authority | Masking stays in ui2-service, where the sanitizer is allowed. The gateway never masks, never holds the privacy key and never sees unmasked text | `SanitizerModuleFenceTest` unchanged; manifest test on gateway secrets |
| Library fence | Only the gateway's adapter package may import a provider SDK or open a connection to a provider host | ArchUnit; dependency declared only for the gateway role |
| Tool results | In the tool loop every tool result is sealed the same way before it is appended to the conversation | Same builder; no second path |
| Network | Only the gateway pod has an egress rule beyond DNS and the database, and only to the forward proxy | NetworkPolicy; enforcement on HOST-A was measured on 2026-11-05 (analysis design §11.1 M6) |
| Second check | The gateway repeats the shape checks that need no key (§3.5 G3, G5) on the bytes it is about to send | Defence in depth across the process boundary |

### 3.5 The egress guard and the fail-closed rule

The guard runs on the exact bytes of the request body.

| # | Check | Blocks when |
|---|---|---|
| G1 | Rule-set pin | The payload does not carry the expected `logmask` and `egress` versions |
| G2 | Known-identity scan | Any word, or delimited part of a word, equals a known identity in the estate dictionary. Today it holds device and cluster names, addresses and serials; this design adds administrator names and policy, object, zone and domain names. The dictionary holds keyed digests only |
| G3 | Shape scan | Any address-, MAC-, e-mail-, FQDN- or URL-shaped string is not one of the aliases this request generated and is not in the kept ranges |
| G4 | Secret scan | Re-running the redaction rules finds anything; a long high-entropy or base64 run; a private-key marker |
| G5 | Structure | A field, section or source key outside the allowlist; more lines or bytes than the caps |
| G6 | Self-test | A planted canary identity is not caught at start-up or at the periodic self-test: the guard reports itself unavailable |

**Fail-closed rule.** A request is sent only if every condition below holds; otherwise nothing is sent and the finding
shows `EXPLANATION_WITHHELD` with a reason code.

1. The agent is enabled, the provider and model are on the configured allowlist, and the kill switch is off.
2. The estate dictionary, the privacy key and the vocabulary are available and current. A `MaskingUnavailable`
   condition blocks; there is no stale-dictionary mode for egress.
3. G1–G6 pass on the whole payload. One failing line blocks the whole request; lines are never dropped silently. The
   only automatic fallback is from Level 2 to Level 1, and it is recorded.
4. The audit intent record was written (§6.6). If it cannot be written, no call is made.
5. TLS verification succeeds against the configured trust store.
6. The budget and rate limits allow the call (§5.7).

A blocked request is never retried with fewer checks, a different masking mode or another provider. Blocks are counted
per reason and shown on the settings screen; the blocked payload is not stored. A response that fails validation
(§8.3) is stored for audit and not displayed.

### 3.6 De-pseudonymising model output for display

The model's answer is untrusted text that may contain aliases. It is stored exactly as returned, in alias form.

1. **Evidence by reference, not by value.** Each excerpt line in the request has a short reference (`E1` ... `En`).
   The output schema requires the model to cite references. neXus maps a reference to its pointer (chunk id, byte
   offset, length), kept locally with the call record, and the UI shows the cited lines through the normal Logs read
   path. Evidence therefore needs no reverse mapping, and the existing masked/unmasked rules and per-view audit apply.
2. **Aliases in the narrative.** At display time aliases are replaced from the request's alias map: by the standard
   pseudonym (`FW-TANGO-04`) for every viewer, and by the real value only for a role that may view unmasked log lines
   (`operation_admin`, `security_admin`; phase B decision Q10), behind an explicit toggle, audited per view. aiview
   always gets the pseudonym form.
3. **Only what was sent is mapped back.** As in the sanitizer's revert path, a token is replaced only if the request's
   map holds it. An alias-shaped token the model invented is left as it is and marked "unverified reference". A name
   the model produced from its own knowledge is never turned into a real device.
4. **Output scan.** The response passes G2 and G3 before it is stored. A hit quarantines the response.
5. **No real value is written into stored explanations**, exports or notifications. Reports that include an
   explanation use the pseudonym form.

### 3.7 What still leaves when masking works perfectly

The PO should accept these explicitly, because no masking removes them:

- that the organisation runs Check Point and Palo Alto firewalls of given model families and software versions;
- the operational events themselves: a failover, a crashing process, an interface flap, and their time of day;
- the network's shape inside one request: which masked addresses share a subnet;
- volumes and rhythms: how many findings per day, and when people ask;
- the product's prompts and detector vocabulary.

Request-scoped aliases and relative dates limit linking across requests. They do not make a single request anonymous.

## 4. Detection split

### 4.1 Rules

1. A finding is created only by a deterministic detector with an id and a version. Same input, same finding.
2. The LLM is called only for an existing finding (or, in phase 3, inside a session scoped to one device and window).
3. The LLM's output never changes a finding's severity, status or existence, never enters System health, readiness,
   compliance or a report as evidence, and never triggers a notification.
4. `AGENTS.md` vendor semantics law says a gap must not be filled "from general model/product knowledge". An LLM
   explanation is exactly that kind of knowledge. It is therefore labelled advisory everywhere, must cite the lines it
   relies on, and must list what it cannot conclude.
5. A window with a recorded gap, a `BACKLOG` or an `INITIAL_BASELINE` read is UNKNOWN for rate detectors. Silence
   caused by missing collection is never read as health, and never as an anomaly.

### 4.2 Data the detectors need

The pool provides encrypted chunks, per-chunk counters, gap rows, run outcomes, crash-file rows and sar files. It
lacks the parsed event index. Building it is the first implementation item and is what the programme calls phase C.

| New table (names provisional) | Content | Log text? |
|---|---|---|
| `system_log_template` | Template id (keyed digest of the normalised template, source key and rule version), vendor, source, process or event class, template text in closed-vocabulary form, first seen, catalogue class and its origin (`vendor_seed`, `operator`, `ai_suggested_pending`), knowledge link | Vocabulary-filtered template only |
| `system_log_event_count` | Device, source, template id, bucket start and time basis, count, up to three evidence pointers (chunk id, offset, length) | No |
| `system_log_index_state` | Chunk id, index rule version, lines parsed and unparsed | No |
| `log_finding` | Device, detector id and version, kind, window, severity, numeric facts, evidence pointers, incident group, review state | No |
| `log_finding_explanation`, `log_agent_call` | See §6.6 | Masked, encrypted artefact |

Retention follows the PO's earlier answer for the parsed index: 180 days. Raw chunks expire after 30 days, so an older
finding keeps its numbers and its explanation but shows "evidence expired". Each migration gets the BEGIN/ROLLBACK dry
run on the live database before deploy and audit triggers, as the phase B tables did.

Chunks are immutable, so indexing is idempotent per (chunk, rule version) and can be re-run when the rules change.

### 4.3 Template extraction

| Source | Method | Why |
|---|---|---|
| Palo Alto `pan_system` | Template = (`type`, `subtype`, `eventid`, `module`); `opaque` normalised only for sub-patterns | The vendor already supplies the enumeration. Whether an `eventid` means what its name suggests stays UNKNOWN until cited (field presence is not semantic proof) |
| Check Point `messages`, elg files | Vendor-aware tokeniser, then typed-variable normalisation: addresses, numbers, hex, paths, quoted strings, pids and timestamps become typed placeholders; the process name is the first key | Deterministic and independent of line order |
| Fallback for free text | Drain-style fixed-depth clustering, used offline to propose new normalisation rules, not online | An online clusterer depends on arrival order and a similarity threshold, so re-indexing would not reproduce ids. The masking P0 item of the analysis design is the same class of problem |

After normalisation the closed-vocabulary filter of §3.3 turns every remaining non-vocabulary word into a placeholder,
and the template id is computed from that result. Two consequences: a host or user name is not part of a template
unless it is itself a vocabulary word (a case the estate dictionary catches at egress, §3.3), and messages that differ
only in such a name share one template instead of splitting per device. The filter is a small plain library without
keys, usable by the worker (templates) and by ui2-service (egress); it is not part of the sanitizer.

Multi-line records (`cpd.elg`) are joined before normalisation. Lines the tokeniser cannot place are counted as
unparsed per chunk and never guessed.

### 4.4 Detectors (first set)

All thresholds are PROVISIONAL and belong to configuration with a version.

| Detector | Signal and algorithm | Data | Time grain |
|---|---|---|---|
| New template | A template first seen on this device after its learning period; rarer across the fleet scores higher (devices with the template / devices of that vendor) | Template table, event counts | Collection run |
| Rate change | Count per (device, template or process class) against that device's own baseline: same hour of day, workday and weekend kept apart, trailing 28 days. Robust score `(x - median) / (1.4826 x MAD)`; a finding needs score >= 6, count >= 20 and count >= 5 x median. For templates whose usual count is zero, a Poisson tail bound and an absolute floor | Event counts | Hour where lines carry a time; collection run (6 h) otherwise |
| Sustained shift (second set) | EWMA or CUSUM control chart on daily counts | Event counts | Day |
| Silence | A template seen in at least 90 % of baseline windows is absent while the source's run outcome is `COLLECTED` and no gap overlaps | Event counts, run outcomes, gaps | Collection run |
| Process crash and restart | Signature list: cpwd restart lines (the existing PROVISIONAL rule), kernel panic, oops, segfault and out-of-memory lines, new crash or dump files (`device_crash_alert`) | cpwd and messages templates, crash rows | Event |
| Flap | State-transition counting per object (interface, cluster member state, HA state, routing neighbour, VPN tunnel, log-server connection): at least 3 transitions in 10 minutes or 6 in 1 hour. The object is kept as a keyed token | Templates tagged as transitions | Event |
| Correlation | Findings on one device within 10 minutes form one incident; findings on both members of a cluster in the same window are linked (one member's report about its peer is not corroboration by itself); the same new template on many devices in one window becomes one fleet finding | Findings, inventory relationships | Window |
| Resource context | sar CPU and memory p95 and Spike Detective presence are attached as context, not as findings | `SystemHealthService` facts | Day |

Severity comes from a fixed table (detector kind x catalogue class), with the System health screen's words: CRITICAL,
ATTENTION, UNKNOWN, NORMAL. There is no composite score. Each signature carries its provenance (`MEASURED`,
`VENDOR_DOC` with a citation, or `PROVISIONAL`); a signature without provenance can produce INFO only.

Baselines exclude windows with gaps, the first-run baseline, the day of a software version change and windows already
flagged. A device with fewer than 14 usable days is `BASELINE_LEARNING` and gets only signature and new-template
findings. Baselines need uninterrupted scheduled collection; while collection is switched off, rate detectors report
UNKNOWN.

Rejected for the first set: isolation forests, sequence models and other trained models. They need training
infrastructure, are hard to explain to an operator and are not reproducible in the sense of rule 1.

### 4.5 What the LLM does

| Job | Input | Output |
|---|---|---|
| Explain | One incident: finding facts and, at Level 2, its excerpts | What happened, in plain language; cited lines; what cannot be concluded |
| Triage | The same | A suggested priority (`act_now`, `review_today`, `monitor`, `likely_benign`), a cause category, next checks chosen from a closed list of neXus screens and approved read gates. No free-form device command is ever rendered as runnable |
| Summarise | Finding metadata of a device-day or of the fleet (no excerpts) | A short digest |
| Classify templates (phase C first pass) | Templates only: no device, no time, no counts | A suggested class per template, stored as `ai_suggested_pending` until an operator confirms |

### 4.6 Is an agent needed at all?

For the first three jobs, no. One structured call per incident is enough, with no tools and no loop. That is cheaper,
easier to audit and has a smaller attack surface. A tool-using agent is justified only for follow-up questions ("what
else happened on the peer in that hour?"), so it is a separate, later phase (§6.1), entered only if operators ask for
it after phase 2.

### 4.7 Prompt shape and caps (PROVISIONAL)

| Part | Cap | Notes |
|---|---|---|
| Static prefix: role, rules, output schema, vendor glossary, tool definitions | about 4,500 tokens | Byte-stable: no time, device or request id. Deliberately above 4,096 tokens so it is cacheable on both providers (§5.8) |
| Device card | 300 tokens | Alias, vendor, model family, version, role, last collection outcome, system-health facts |
| Per finding: detector facts | 250 tokens | Counts, baseline, thresholds, window |
| Per finding: excerpts (Level 2) | 40 lines, 16 KiB, about 2,000 tokens | Chosen deterministically: first and last occurrence, the peak bucket, at most 3 per template, up to 10 context lines, correlated cpwd/messages lines within 2 minutes; identical masked lines collapsed with a count |
| Findings per call | 5 | More are split into further calls or left detector-only |
| Output | 350 tokens per finding plus a 200-token summary | Structured (§8.3) |

Log content sits in a delimited data block, and the prefix states that anything inside it is data, not instructions.

### 4.8 Token and cost estimate per device per day

Assumptions, all to be replaced by measurement in phase 0: about 50 tokens per masked line; a typical device-day with
findings has 2 findings, a heavy one hits the cap of 5; reasoning tokens are billed as output, and 600 (typical) and
1,050 (heavy) of them are assumed at low effort on top of the visible output of §4.7; the prefix is read from cache
after the first call of a run.

| | Typical (2 findings) | Heavy (5 findings) |
|---|---|---|
| Cached prefix tokens | 4,500 | 4,500 |
| Uncached input tokens | 4,800 | 11,550 |
| Output tokens (visible + reasoning) | 1,500 | 3,000 |

| Model (prices per 1M tokens: input / cached input / output) | Typical device-day | Heavy device-day | 92 devices x 30 days, all typical |
|---|---|---|---|
| `claude-opus-5-5` (4.00 / 0.20 / 20.00) | USD 0.050 | USD 0.107 | USD 138 |
| `claude-sonnet-5-5` (2.00 / 0.20 / 10.00) | USD 0.026 | USD 0.054 | USD 70 |
| `claude-haiku-5-5` (0.10 / about 0.01 / 0.50) | USD 0.0013 | USD 0.0027 | USD 3.5 |
| `gemini-4-argon` from 2026-12-01 (4.00 / 0.20 / 20.00; half that until 2026-11-30) | USD 0.050 | USD 0.107 | USD 138 |
| `gemini-3.8-flash` until 2026-12-31 (0.75 / 0.075 / 3.75; double from 2027-01-01) | USD 0.010 | USD 0.020 | USD 26 (then 53) |
| `gemini-3.5-flash-lite` (0.30 / 0.03 / 2.50) | USD 0.0053 | USD 0.011 | USD 15 |

Reading the table:

- The last column is an upper bound: it assumes every one of the 92 collected devices (53 Check Point gateways, 39
  Palo Alto firewalls) has findings every day. A device-day without findings makes no call. The real share is UNKNOWN
  until the detectors have run in shadow (§10 phase 1).
- Level 1 sends no excerpts, so its uncached input is roughly a quarter of the figure above. The output is unchanged,
  so the total falls by about a third (about USD 0.035 for the typical case on Claude Opus 5.5).
- Both vendors' batch interfaces halve the price for work nobody is waiting on (nightly digests).
- An interactive investigation of about 8 model turns with tool results is estimated at USD 0.2 on Claude Opus 5.5 and
  USD 0.1 on Claude Sonnet 5.5.
- The one-off template classification (assume 5,000 templates) is a few dollars on any of these models.
- Why detection first is also a cost argument: a median Palo Alto firewall writes about 2 MB of system log a day,
  roughly 590,000 tokens at 3.5 bytes per token. Sending that to Claude Opus 5.5 would cost about USD 2.4 per firewall
  per day, some fifty times the excerpt approach, and would disclose every line instead of at most 200.

Cost is decoupled from log volume: the caps of §4.7 bound it whatever a device writes.

## 5. Provider abstraction

### 5.1 One interface

The rest of the product talks to one interface with four operations. Nothing outside the gateway knows which vendor
is behind it.

| Operation | Purpose |
|---|---|
| `capabilities` | Model id, context window, output cap, structured-output support, tool-use support, cache kind (`explicit`, `implicit`, `none`) and minimum cacheable size, batch support, token-count support, data-handling class |
| `countTokens` | Size a sealed prompt before sending (budget check) |
| `complete` | One model turn: sealed prompt, tool definitions, output schema, effort, output cap, deadline. Returns a normalised result |
| `submitBatch` / `fetchBatch` | Optional, for digests |

The normalised result carries: status (`OK`, `REFUSED`, `TRUNCATED`, `TIMEOUT`, `RATE_LIMITED`, `PROVIDER_ERROR`,
`SCHEMA_INVALID`), the structured output, tool calls, usage (input, cache read, cache write, output, reasoning), the
model the provider reports, its request id, and latency. Vendor-specific reasoning blocks that must be replayed
unchanged in a tool loop are carried as an opaque blob the caller passes back without reading.

Capabilities drive behaviour. A provider without tool use cannot run the phase 3 loop; a provider without enforced
structured output gets schema validation plus one repair attempt, then `SCHEMA_INVALID`.

### 5.2 Claude adapter (capabilities checked 2026-11-10)

| Topic | Design |
|---|---|
| Surface | Messages API (`POST /v1/messages`) through the official Java SDK. Not Managed Agents: there Anthropic runs the loop and hosts the tool sandbox, while this design requires tools to run inside neXus and no state at the provider |
| Model | Configurable. Default `claude-opus-5-5`; `claude-sonnet-5-5` and `claude-haiku-5-5` are cheaper tiers the PO may choose per job (§12 decision 4). The Fable tier is excluded: it requires 30-day retention at the provider and is not available under zero data retention |
| Reasoning | Adaptive thinking is always on for the current Opus and Sonnet; depth is set with `output_config.effort` (`low` for explanations and digests, `medium` for investigation). Sampling parameters such as `temperature` are rejected by current models, so identical answers cannot be forced; reproducibility comes from storing the answer |
| Structured output | `output_config.format` with a JSON schema for the final answer; `strict: true` on tool definitions. Forced tool choice is rejected by the current Opus and Sonnet, so the loop uses `tool_choice: auto`. The provider's citation feature cannot be combined with a JSON output format, which is one reason evidence uses our own references |
| Prompt caching | A `cache_control` breakpoint at the end of the static prefix. Default lifetime 5 minutes (a read refreshes it), 1 hour optional. Minimum cacheable prefix 512 tokens on the current models. A cache read is billed at USD 0.20 per 1M tokens on Opus 5.5 and on Sonnet 5.5 (a twentieth and a tenth of their input prices); a write costs 1.25 x the input price (2 x for the 1-hour lifetime). Verified per call from `usage.cache_read_input_tokens` |
| Token counting | `POST /v1/messages/count_tokens` |
| Batches | Message Batches, half price, asynchronous; not available through Bedrock, Vertex AI or Foundry. Off until its retention at the provider is confirmed against the PO's decision |
| Refusals | `stop_reason: "refusal"` with a category. Security-related log content could trip a safety classifier. A refusal becomes `EXPLANATION_WITHHELD (PROVIDER_REFUSED)`; the request is not rephrased to get around it. The server-side fallback to another Claude model stays inside one provider and is a configuration option |
| Errors and limits | 429 `rate_limit_error` with a `retry-after` header; 529 `overloaded_error`. The SDK retries connection errors, 408, 409, 429 and 5xx twice by default with backoff, and its default timeout is 10 minutes; the gateway sets both explicitly (§5.7) |
| Data handling | Commercial API content is not used for training under the commercial terms. Inputs and outputs are deleted within 30 days by default; zero data retention is by arrangement and covers the Messages and token-counting endpoints. `inference_geo` selects the processing region on the direct API. The same models are also reachable through Amazon Bedrock, Google Vertex AI and Microsoft Foundry under those clouds' contracts and regions. To verify before contracting: the current Commercial Terms and data processing addendum |
| Not used | Files API, message threads, server-side tools (web search, code execution), memory: each keeps data or opens a channel at the provider |

### 5.3 Gemini adapter (capabilities checked 2026-11-10)

**Antigravity is not the integration point.** The measurement record of 2026-09-14 shows the Antigravity SDK is a
Python client over a bundled local harness binary of about 117 MB, platform-specific, that runs its own tools
(command execution, MCP servers). That shape fits a developer workstation, not a read-only Java service. The models
behind it are Gemini models, reachable with an API key through the Gemini API. The adapter therefore calls the Gemini
API directly through Google's Java SDK (`com.google.genai`).

| Topic | Design |
|---|---|
| Surface | Stateless requests only. The newer Interactions API stores conversation state at the provider by default; the adapter always sends `store: false` and never uses `previous_interaction_id` |
| Models | Configurable: `gemini-4-argon` (the model the team calls Argon), `gemini-3.8-flash`, `gemini-3.5-flash-lite`. Listed prices change on 2026-12-01 (Argon doubles) and 2027-01-01 (3.8 Flash doubles) |
| Structured output | `response_format` with a JSON schema. A subset of JSON Schema is enforced; unsupported keywords such as `maxLength` are silently dropped, so the product validates the answer itself. Combining structured output with function calling is marked preview |
| Tool use | Function declarations with `tool_choice` modes `auto`, `any`, `none`, `validated` and an allowed-tools list. In stateless mode every model step, including thought steps, must be passed back unchanged |
| Caching | Implicit caching is on by default, with a minimum of 4,096 tokens on Gemini 4 Argon and 3.8 Flash; from Gemini 4 a follow-up request is cached only if it adds at least 2,050 tokens. Explicit caches are not available for Gemini 4 Argon. Cached tokens are reported in the usage block. This is why the static prefix is sized above 4,096 tokens |
| Batches | Batch API at half price |
| Errors and limits | Limits are per project, in requests per minute, tokens per minute and requests per day; the daily quota resets at midnight Pacific time. Exceeding a limit returns 429 `RESOURCE_EXHAUSTED`. Limits rise with usage tiers tied to cumulative spend |
| Data handling | Paid-tier content is not used to improve Google's products. Prompts and responses are logged for abuse monitoring (55 days in the usage policy), may be reviewed by authorised staff, and this cannot be switched off on the Developer API. Google directs customers who need zero data retention to Vertex AI, where an abuse-monitoring exception can be requested, caching disabled and a regional endpoint chosen. The free tier must never be used: its content may be used for product improvement |
| Not used | Grounding with Google Search or Maps (stores prompts for 30 days, cannot be disabled), URL context, code execution, Files API, explicit caches, stored interactions |

### 5.4 Local or on-prem adapter (later)

- A third adapter speaks the OpenAI-compatible chat API that local inference servers expose (vLLM, llama.cpp server,
  Ollama and similar) to an endpoint inside the site. No request leaves the organisation.
- Capabilities differ: enforced JSON output depends on the server, tool use is uneven. The capability flags of §5.1
  decide which jobs run; explanation works with validation and one repair attempt, investigation may be unavailable.
- Masking stays on by default even for a local model, as defence in depth; the PO may later allow Level 2 always.
- HOST-A has 16 cores and about 60 GiB of memory, with memory limits already overcommitted (analysis design §11.1
  M8), and no GPU is recorded. Running a useful model there would be slow and would compete with policy collection and
  packet analysis. A local model realistically needs its own GPU host. That is a hardware decision, not designed
  here; the adapter only needs an endpoint URL.

### 5.5 Mock adapter

A fourth adapter returns canned, schema-valid answers from fixtures. It lets the whole path (seal, guard, gateway,
validation, audit, UI) run in tests and in the e2e job with no outbound connection, and lets aiview validate screens
without any provider.

### 5.6 Configuration

| Setting | Values | Default | Who |
|---|---|---|---|
| `log_agent.enabled` | on/off (also the kill switch) | off | `security_admin`, audited |
| `log_agent.provider` | `none`, `claude`, `gemini`, `local`, `mock` | `none` | `security_admin` |
| `log_agent.disclosure_level` | 0, 1, 2 | 0 | `security_admin` |
| Model per job (explain, digest, investigate, classify), effort, output cap | allowlisted model ids | — | `security_admin` |
| Budgets and limits (§5.7), price table for the cost estimate | numbers | conservative | `security_admin` |
| Provider credentials | A Kubernetes Secret mounted only in the gateway; never in the database, the settings API or the UI | — | deploy |

The provider is selected at start; switching it is a settings change plus a gateway restart, matching the product's
other restart-time switches. There is no automatic cross-provider fallback: sending the same data to a second outside
party must be a deliberate choice.

### 5.7 Timeouts, retries, rate limits, budgets

| Control | Value (PROVISIONAL) | Where |
|---|---|---|
| Connect timeout | 5 s | gateway |
| Request deadline | 120 s per explanation call, 180 s per investigation turn; streaming transport for long outputs | gateway |
| Retries | At most 2, only for 429 (honouring `retry-after`), 529, 5xx and timeouts, with exponential backoff and jitter. Never for 400, 401, 403, 413, a refusal or a guard block. SDK-internal retries are disabled so there is one retry policy | gateway |
| Total deadline per finding | 5 minutes, then `TIMEOUT` | service |
| Rate limit | Token bucket on requests per minute and tokens per minute, set below the provider tier's limits; at most 2 calls in flight | gateway |
| Circuit breaker | Open for 10 minutes after 5 consecutive provider failures | gateway |
| Budgets | Daily token and estimated-cost ceilings; calls per device-day; per session: 10 turns, 20 tool calls, 150,000 tokens | service, database-backed |
| Idempotency | An explanation is keyed by finding, detector version, masking versions, prompt version, provider and model. A repeat request returns the stored answer; regeneration is an explicit, audited action | service |

When a limit is reached the finding shows `BUDGET_EXHAUSTED` or `RATE_LIMITED`. Nothing is dropped silently, and the
detector's findings are unaffected by any provider failure.

### 5.8 Prompt caching across providers

The static prefix is identical for every device and both vendors' logs, is ordered tools, rules, schema, glossary, and
contains nothing that varies per request. On Claude it carries one explicit breakpoint; on Gemini it relies on implicit
caching; the local and mock adapters report `none`. A golden test pins the prefix bytes, because one changed byte
silently turns every cache read into a full-price write. Aliases, dates and excerpts sit after the breakpoint.

## 6. Agent tools, RBAC and audit

### 6.1 Two modes

| Mode | Phase | Tools | Trigger |
|---|---|---|---|
| Explain / triage / summarise / classify | 2 | none: one structured call | A permitted user asks for an explanation of a finding; optionally a nightly digest (§12 decision 5) |
| Investigate | 3 | the read tools below, in a bounded loop run by ui2-service | A permitted user opens a session scoped to one device (or one cluster) and one time window |

In the loop, ui2-service sends a turn to the gateway, receives tool calls, executes them locally, seals each result and
sends the next turn. The gateway never calls back into the product.

### 6.2 Tool catalogue (all read-only, all masked, all inside the session's scope)

| Tool | Returns | Caps |
|---|---|---|
| `list_findings` | Findings for the scoped device or fleet overview counts by kind and severity | 50 rows |
| `get_finding` | One finding's facts and evidence references | — |
| `log_stats` | Counts from the event index grouped by template, process class, severity or hour | 200 rows; no text |
| `search_logs` | Masked lines by template id, process class, severity and time range | 50 lines, 16 KiB |
| `get_log_context` | Masked lines before and after an evidence reference | 20 + 20 lines |
| `template_info` | Catalogue class, first seen, share of devices with the template, curated knowledge link | — |
| `device_context` | Device card: alias, vendor, model family, version and hotfix level, role, cluster and peer aliases, collection outcomes, gaps, system-health facts, count and time of recent configuration-change events | — |

`search_logs` matches on indexed attributes, not on raw text. A free-text search over unmasked lines would let the
model probe for real values through hit counts, so it is not offered.

### 6.3 What is deliberately not a tool

Device commands and diagnostics of any class (including the CLASS_0 reads aiview may run), collection or readiness
starts, job admission, schedules, settings, exports, the sanitizer's revert, unmasked reads, notifications, policy
and topology data, HCP archives, Spike Detective data, Palo Alto config-log change contents, web access.

### 6.4 Enforcement

| Mechanism | Rule |
|---|---|
| Closed registry | The tool list is a compile-time registry; a tool name the model invents is answered with an error result, never dispatched |
| Architecture fence | The tool package may depend only on read repositories. It must not depend on job admission, the job engine, transport, the capability registry, write repositories, notification senders or the sanitizer's revert |
| Scope | Device set and time window are fixed when the session starts, from the requesting user's own permissions; every tool call is checked against them |
| Masked view | Tools always run in the masked view, whoever the user is, because their results leave the product |
| Database | The loop's reads go through a database role with SELECT on the index, finding and inventory projections only |
| Gateway | No database credentials, no artefact-store key, no privacy key, no credential-store or role-binding key, no route to device networks |
| Rendering | Model text is rendered as plain text. No links, images or HTML are rendered, which closes the browser as an exfiltration path |

### 6.5 RBAC

| Action id (proposed) | What it allows | Minimum role | aiview |
|---|---|---|---|
| `log_finding_read` | Read detector findings and stored explanations | `operator` | yes, masked |
| `log_finding_review` | Mark reviewed or dismissed; give feedback | `operator` | no |
| `log_template_classify` | Edit catalogue classes; confirm AI suggestions | `operation_admin` | no |
| `log_agent_explain` | Request an explanation (causes an outbound call) | `operation_admin` | no |
| `log_agent_investigate` | Open an investigation session (phase 3) | `operation_admin` | no |
| `log_agent_unmasked_view` | Show real names in an explanation | `operation_admin`, audited per view | never |
| `log_agent_settings_read` / `_write` | Provider, model, level, budgets, kill switch | `security_admin` | no |
| `log_agent_audit_read` | Read call records and the stored prompts and responses | `security_admin` | no |

The agent has its own identity: a system principal `system:log-agent` bound to a role `log_agent` whose only
permissions are the read tools. It has no session and cannot log in. A tool call is allowed only if both the
requesting user and the agent role may perform it, so the agent can never do more than the person who asked, and never
more than read.

### 6.6 Audit

Every model call writes two records: an intent record before the call (without it the call is not made) and a result
record after it.

| Field | Content |
|---|---|
| Identity | Call id, time, actor fingerprint (user, or `system:log-agent` for scheduled work), action id |
| Scope | Job (explain, digest, investigate, classify), finding ids, device ids as opaque references |
| Provider | Provider, model requested and model reported, effort, provider request id |
| Masking | Disclosure level, `logmask` and `egress` versions, vocabulary digest, guard verdict with counts per token kind and any block reason |
| Content | The exact request body as sent and the exact response, stored as an encrypted artefact (envelope encryption, own artefact class), referenced by a keyed digest. Both are masked by construction |
| Tools | Per tool call: name, arguments, result size, guard verdict |
| Cost | Token usage by kind, estimated cost, latency, status, stop reason |

Rules: the records are append-only for the application role; the alias map is stored encrypted with the call;
transcripts are kept 180 days by default (PO-settable) and pruned by the existing retention mechanism with a ledger
entry; reading a transcript is itself audited. User-facing actions also produce the usual `authz_decisions` rows, and
the settings table gets `audit_log` triggers. Because the stored request is byte for byte what left, an auditor can
answer "what exactly did we send on that day?" from the product alone.

### 6.7 Prompt injection

Log lines are partly attacker-controlled: a login name or a host field can carry text aimed at the model. Mitigations:
the fields an attacker controls most easily (users, hosts) are exactly the ones replaced by aliases; log content is
delimited as data; the model has no tool with a side effect; tools are scoped and capped, so an injected instruction to
"search everything" cannot widen the disclosure beyond the session; the answer is schema-bound, validated and rendered
as plain text. An injected line can still make an explanation wrong. That risk is carried by rule 3 of §4.1: the text
is advice and changes nothing.

## 7. Placement

### 7.1 The principle

The 2026-11-05 decision says the container count grows with a measured need, justified by load isolation, failure
isolation or an independent deploy cadence, and that each new container costs a deploy target and drain barrier,
service-to-service authentication, and masking re-established at the new boundary.

### 7.2 The three workloads and where each goes

| Workload | Character | Placement | Reason |
|---|---|---|---|
| Index and detect | Batch, CPU-bound, reads decrypted chunks after each collection window | A job in the existing worker (`diagnostics` module) | No new container. The worker already owns the artefact store and parses these lines for redaction. If measurement shows the job delays collection, move it then |
| Build, mask, seal, authorize, audit, display | Light, request-bound | ui2-service | Masking authority, RBAC, `authz_decisions` and the Logs read path are already here; the sanitizer may be used only by this module |
| Call the provider | I/O-bound, the only code that talks to the internet | New container `ui2-llm-gateway` (same image, new launcher role) | §7.3 |

### 7.3 Why the provider call gets its own container, and why not the analysis container

| Option | Assessment |
|---|---|
| Inside ui2-service | Rejected. ui2-service holds every secret (device credential store key, role-binding key, migration credentials) and the public API. Giving that pod a route to the internet is the largest possible blast radius, and a hung provider call would hold API threads |
| Inside the planned `ui2-analysis` | Rejected. That design's premise is "no device egress" and egress limited to DNS and the database, while it reads every stored policy and topology. Adding internet egress and a provider key there breaks its network premise. It is also DRAFT and gated on its own measurements, so it would delay this work, and its deploy cadence (policy and path code) differs from prompts and provider SDKs |
| Own container `ui2-llm-gateway` | **Recommended.** It holds one kind of secret (the provider key and the internal signing key), sees only sealed masked payloads, has no database and no route to devices, and accepts calls only from ui2-service |

Against the principle:

- **Failure isolation: yes.** Provider outages, slow streams and SDK faults stay in a pod the API does not depend on.
  ui2-service limits its calls in flight and answers `EXPLANATION_WITHHELD`; detection and the Logs screen continue.
- **Independent deploy cadence: yes.** Prompts, model ids and SDK versions change more often than the API.
- **Load isolation: no.** The gateway is light. The heavy part (indexing) is deliberately not in it.
- **A fourth reason the principle does not name: a trust boundary.** The strongest argument is that the only pod with a
  route to the internet should hold no device credentials and no unmasked data. The ratified principle lists three
  justifications and this is not literally one of them. This design does not stretch the wording; §12 decision 6 asks
  the PO to accept the container on that ground or to choose the ui2-service option with its risk.

Costs, stated plainly:

- One more deploy target. It owns no job type, no endpoint admission and no task lease, so, like `configuration`,
  `compliance` and the proposed analysis pod, it stays outside the drain roles and cannot form a drain-barrier cycle.
- Service-to-service authentication, which the repository does not have yet. The signed-request scheme of the analysis
  design §4.2 (HMAC over method, path, body digest, timestamp and request id) is reused; whichever design lands first
  builds it once.
- Masking is **not** re-established in the new container. It only ever receives sealed payloads; the masking authority
  stays single, in ui2-service.

### 7.4 Gateway properties

- Stateless: no database, no volume. Budgets and audit live in ui2-service; rate-limit state is in memory (one
  replica, `Recreate`).
- Secrets: the provider key Secret and the internal signing key. Nothing else is mounted.
- NetworkPolicy: ingress only from the service component on one internal port; egress only to DNS and the forward
  proxy. Kubernetes network policies match addresses, not host names, so a forward proxy with a host allowlist (the
  provider API hosts only) is required; without one the gateway is not deployed.
- TLS is verified against a configured trust store. If the corporate proxy inspects TLS it can read the masked prompts
  and the provider key in the request header; that is a finding for the security review.
- Whether HOST-A has any permitted route to a provider, directly or through a proxy, is UNKNOWN (§10 phase 0).
- Small footprint (proposed 256 MiB request, 512 MiB limit), lowest priority class, same pod security context as the
  other pods.
- Three independent off switches: the setting, scaling the deployment to zero, and removing the egress rule.

## 8. UI touchpoints and output format

### 8.1 Screens (masked state in every preview and fixture)

| Place | Change |
|---|---|
| Operations › System health | Each device row gains a findings count and the worst finding severity, linking to the device's findings |
| Device logs panel | A Findings view for the device and day. Each finding shows kind, severity, window, the numbers against the baseline, and its evidence lines; a click opens the log viewer at that chunk and offset with the lines highlighted |
| Finding card: explanation block | Visually separate and labelled "AI explanation — advisory, not evidence", with provider, model, time, disclosure level and masking versions. Controls by role: Explain, Regenerate (audited), useful / wrong feedback, and for `security_admin` "what was sent" (the stored request) |
| Operations: findings feed | Fleet list filtered by kind, severity, vendor and review state; a daily digest |
| Administration › Platform › Log agent | Enable switch, provider, model, disclosure level, budgets, kill switch, usage and estimated cost, block counters by reason, the audit viewer, the template catalogue with pending AI suggestions |
| Phase 3: investigation drawer | A session scoped to one device and window, with a visible trace of each tool call and its size |

Every UI action has a CLI command, as the phase B contract requires (for example `log-findings`, `log-finding <id>`,
`log-agent-explain <findingId>`, `log-agent-settings`, `log-agent-audit`). A finding with no explanation is complete
by itself; the explanation block is absent, not empty, when the agent is off.

### 8.2 Finding (detector output)

| Field | Content |
|---|---|
| Identity | Finding id, detector id and version, kind, incident group |
| Where and when | Device reference, source, window start and end with the time basis (`DEVICE_LOCAL` hour or collection run) |
| Severity | CRITICAL, ATTENTION, INFO, with the rule that produced it |
| Facts | Observed count, baseline median and spread, score, thresholds, transitions, signature id and its provenance |
| Evidence | Up to 40 pointers (chunk id, offset, length), each resolvable by the Logs read path until the chunk expires |
| State | OPEN, REVIEWED, DISMISSED, with who and when |

### 8.3 Explanation (model output, schema `log_explanation_v1`)

| Field | Rule |
|---|---|
| `finding_id` | Must echo a finding in the request |
| `summary` | One or two sentences |
| `what_happened` | Plain-language account |
| `likely_cause` | Category from a closed list (link, hardware, high availability, resource, software fault, configuration change, external dependency, benign, unknown), a statement, and a confidence of low, medium or high |
| `evidence` | At least one entry of {reference, why}. Every reference must exist in the request |
| `unknowns` | What cannot be concluded from the data given |
| `triage` | Suggested priority and reason; displayed beside, never instead of, the detector's severity |
| `next_checks` | Entries of a closed kind: open a neXus screen, look at an approved read gate by id, compare with the peer, a vendor documentation topic. Vendor article numbers are shown only when they match the curated knowledge table |

Validation before display: schema-valid; references resolve; the output scan of §3.6 passes; length caps hold; no URL
is kept. An explanation with no valid evidence is rejected as `UNGROUNDED` and the finding stays detector-only.

## 9. Risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | An identity passes masking in free text | high | Closed vocabulary on top of the block list; estate dictionary with policy names; guard on final bytes; second check in the gateway; canary self-test; Level 1 pilot first; planted-identity tests |
| R2 | Disclosure that masking cannot remove (§3.7) | medium | Stated for PO acceptance; request aliases and relative dates; caps on lines |
| R3 | Provider-side retention and review (Claude 30 days by default; Gemini Developer API 55 days with possible human review) | high for Level 2 | Contract route decision (§12 decision 3): zero-data-retention arrangement, Vertex AI, or a local model |
| R4 | The reference stack has no LLM slot; egress is not approved | high | Phases 0–1 need no egress; provider `none` by default; mock and local adapters |
| R5 | A wrong but plausible explanation is acted on | high | Advisory label; mandatory cited evidence and unknowns; no effect on severity or automation; feedback and a labelled evaluation set; the "wrong and plausible" rate is a release gate |
| R6 | Prompt injection through log content | medium | §6.7 |
| R7 | The provider refuses security-related content | low | Withheld status; no rephrasing; detector unaffected |
| R8 | Provider key theft or misuse | medium | Key only in the gateway; dedicated workspace or project with a spend limit; rotation procedure; proxy allowlist |
| R9 | Cost runaway | low | Caps, budgets, idempotency, kill switch |
| R10 | False positives and alert fatigue from baselines | medium | Shadow period; absolute floors; exclusions for gaps and upgrades; operator dismissal feeds tuning; no notifications in the first phases |
| R11 | Data quality: missing timestamps, device-local time without a zone, gaps, redaction over-reach in `fwk.elg` | medium | Two time grains; UNKNOWN for uncovered windows; unparsed-line counts shown |
| R12 | Personal data in logs (administrator names, client addresses) crossing a border | UNKNOWN | Whether pseudonymised log data is personal data under the organisation's data-protection rules, and what a transfer abroad requires, is a legal question for the PO's organisation; not decided here |
| R13 | Model or price change at the provider (for example the listed Gemini price increases) | low | Model ids pinned; price table in configuration; evaluation set re-run before a model change |
| R14 | One more container and the first service-to-service channel | medium | No barrier state; signed requests; manifest tests; shared scheme with the analysis design |
| R15 | Index job competes with collection in the worker | medium | Runs after the window, bounded concurrency, measured in phase 0; move only on evidence |
| R16 | Vocabulary filter suppresses too much and explanations become vague | medium | Suppression rate measured; reviewed vocabulary additions |

## 10. Phased rollout

Each phase is behind the enable switch and its own setting; rollback is switching it off. No phase starts before the
previous exit criterion is met and recorded.

| Phase | Content | Egress | New container | Exit criterion |
|---|---|---|---|---|
| 0 — decide and measure | PO decisions of §12; security review; frozen contract (tables, actions, schemas); the measurements below | none | none | Decisions recorded; measurements in a committed record |
| 1 — deterministic detection | Indexer, template catalogue, baselines, the first detector set, Findings screens and CLI. Detectors run in shadow for 14 days first | none | none | The PO reviews a week of findings; false-positive rate and volume accepted |
| 1b — pattern catalogue | Operator-editable classes (programme phase C). The AI-assisted first pass is optional and sends templates only | Level 1 if used | gateway if used | Catalogue classes confirmed by an operator |
| 2 — explanation | Gateway, adapters, egress profile and guard, audit, budgets. Order: mock provider in e2e; then one real provider on synthetic logs only; then real findings of one pilot cluster, triggered by `security_admin` only, at Level 1; then Level 2 if approved | Level 1, then 2 | `ui2-llm-gateway` | Zero guard escapes on the planted-identity corpus; evaluation rubric passed; cost within estimate |
| 3 — investigation | Bounded read-tool loop, per-device sessions, fleet digest | as approved | — | Tool scope tests; operator feedback |
| 4 — later | Local model adapter evaluation; other vendors' logs; traffic logs only under their own contract | — | — | — |

Phase 0 measurements (counts, sizes, durations and booleans only; no raw value leaves HOST-A):

| # | Question | How |
|---|---|---|
| E1 | Is there a permitted route from HOST-A to a provider, and through which proxy? Does it inspect TLS? | Infrastructure answer plus one connection test to the proxy |
| E2 | Check Point lines and bytes per device-day and source | Aggregates over `system_log_chunk` (percentiles only) |
| E3 | Template count per source, share of lines covered by the top 100 templates, unparsed share | A dry-run index job over one week of chunks |
| E4 | Share of lines with a parsed time per source | Same dry run |
| E5 | Masking residual: planted identities through profile and guard; suppression rate on real chunks | Synthetic corpus for escapes; counts only for suppression |
| E6 | Tokens per masked line and per finding card | The providers' token-count endpoints on synthetic excerpts, once a key exists; offline estimate before that |
| E7 | Findings per device-day in shadow | Phase 1 shadow output |
| E8 | Index job duration and memory per window in the worker | Job telemetry |

## 11. Tests and evaluation (outline)

- **Guard:** property tests in which a planted identity of every class, in every position and source format, never
  survives; canary self-test; a block is total, never partial; rule-set pin.
- **Fences:** sealed-prompt constructor; provider SDK imports only in the gateway adapters; tool package dependencies;
  the sanitizer fence unchanged.
- **Manifests:** gateway secrets exactly as §7.4; network policy ingress and egress; no database credentials.
- **Adapters:** one contract suite run against all four adapters with recorded synthetic exchanges: caching fields,
  refusal, truncation, 429 with `retry-after`, 529, timeout, schema-invalid answer.
- **Audit:** every call path writes the intent and result records; a failed intent write prevents the call.
- **RBAC:** route tests per action and role; aiview never triggers a call and never sees real names.
- **Determinism:** re-indexing the same chunks gives the same template ids and the same findings.
- **Cache:** golden test on the static prefix bytes.
- **UI:** render harness with masked fixtures; model text rendered as plain text.
- **Evaluation set:** known events (failover exercises, known crash nights) for detector recall; a rubric scored by the
  PO or an engineer on at least 30 explanations: useful, harmless, or wrong. The share that is wrong and plausible is
  the number that decides whether phase 2 goes wider.

## 12. Open PO decisions

1. **External model at all, and at which level?** May pseudonymised log data go to an external cloud model?
   Options: (a) no — detector only now, local model later; (b) yes, Level 1: patterns and counts only, as the current
   contract text says; (c) yes, Level 2: masked excerpt lines too, which needs the programme contract §5 sentence
   amended. Recommendation: (b) for the pilot, (c) only if (b) proves insufficient.
2. **Scope of the first phase: system logs only, or traffic logs too?** Recommendation: system logs only. neXus does
   not collect traffic logs; adding them means a new collection programme with new device reads, volumes far larger
   than system logs, and far more personal data.
3. **Which provider first, and under which contract?** Claude, Gemini or both. Direct API with standard retention, a
   zero-data-retention arrangement, or through a cloud tenancy (Vertex AI for Gemini; Bedrock, Vertex AI or Foundry
   for Claude). Note that the Gemini Developer API keeps prompts 55 days with possible human review and cannot be
   switched to zero retention.
4. **Model tier and monthly ceiling.** Default Claude Opus 5.5 (about USD 140 a month as an upper bound for the
   fleet), or a cheaper tier (Sonnet 5.5 about USD 70, Haiku 5.5 under USD 5). Which monthly ceiling should stop
   calls?
5. **Who triggers a call?** Proposed: `operation_admin` and `security_admin`, on demand only, so every outbound request
   has a named person behind it. Should a nightly automatic digest be allowed later?
6. **One new container.** Accept `ui2-llm-gateway` on the ground of a trust boundary (the only pod with internet access
   holds no device credentials and no unmasked data), which the 2026-11-05 principle does not name; or keep the
   provider call inside ui2-service and accept internet egress on the pod that holds every key.
7. **Real names in explanations.** May `operation_admin` and `security_admin` switch an explanation to real names
   inside the product (audited per view, as for log lines), or should explanations stay pseudonymised for everyone?
8. **Audit retention.** Keep every sent prompt and received answer for 180 days, readable by `security_admin` only?
9. **Excluded content.** Confirm that Palo Alto config-log change contents, HCP archives, Spike Detective data and
   management-server logs never reach the model in this design.
10. **Legal check.** Does the organisation need a data-protection assessment before pseudonymised administrator names
    and addresses are processed by a provider abroad (risk R12)?

Questions for the security review rather than the PO: whether request-scoped aliases and relative dates are worth
their complexity; whether the closed vocabulary should be the default at Level 1 as well; whether the gateway's second
check should also get a derived dictionary key; whether transcripts should be hash-chained.

## 13. Sources checked on 2026-11-10

Repository sources are named inline. Provider facts come from these public pages, read on 2026-11-10; they are
vendor statements, not measurements, and must be re-read before a contract freezes any of them.

- Claude: model ids, prices, caching, effort, structured outputs, batches, errors, platform availability — the Claude
  API reference bundled with the engineering tool (cached 2026-10-06), whose live sources are
  `https://platform.claude.com/docs/en/about-claude/pricing`,
  `https://platform.claude.com/docs/en/build-with-claude/prompt-caching`,
  `https://platform.claude.com/docs/en/build-with-claude/batch-processing` and
  `https://platform.claude.com/docs/en/api/rate-limits`.
- Claude data retention: `https://platform.claude.com/docs/en/build-with-claude/api-and-data-retention` and the
  Anthropic privacy centre articles on commercial data handling and zero data retention (`https://privacy.claude.com`).
- The Gemini pages below were requested at `ai.google.dev` and, after a redirect, served from
  `https://aistudio.google.com/docs/` under the same page names.
- Gemini prices and models: `https://ai.google.dev/gemini-api/docs/pricing`.
- Gemini caching: `https://ai.google.dev/gemini-api/docs/caching`.
- Gemini rate limits: `https://ai.google.dev/gemini-api/docs/rate-limits`.
- Gemini structured output and function calling: `https://ai.google.dev/gemini-api/docs/structured-output`,
  `https://ai.google.dev/gemini-api/docs/function-calling`.
- Gemini data handling: `https://ai.google.dev/gemini-api/docs/zdr`,
  `https://ai.google.dev/gemini-api/docs/usage-policies`, and for Vertex AI
  `https://docs.cloud.google.com/vertex-ai/generative-ai/docs/data-governance`.

Not verified, and marked so in the text: private connectivity options of the cloud platforms, the retention of batch
requests at either provider, the Maven coordinates of Google's Java SDK, and every figure in §4.8 that rests on an
assumed token count.
