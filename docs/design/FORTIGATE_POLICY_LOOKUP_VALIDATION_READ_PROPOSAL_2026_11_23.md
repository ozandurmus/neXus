# FortiGate policy lookup — real-environment validation read proposal

Status: DRAFT -- DO NOT FREEZE; proposes a read for PO literal approval; approves nothing

Date: 2026-11-23. Backlog id `asa_fortigate_policy_model`. Controlling text:
`ASA_FORTIGATE_POLICY_DESIGN_2026_11_14.md` section 8, last paragraph ("a FortiGate equivalent to be researched and
proposed then"). Layout follows `PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md` and the R1/R2 rows of the design's section 6.

This document runs no command, adds no gate row, changes no capability file and no code. It is a research record plus one
question for the Product Owner (AGENTS.md 2026-09-26 amendment: the PO approves the exact literal; approval of one command
does not approve the next). Until the PO answers, the FortiGate side of the lookup validation stays unvalidated and the
build stays `AUTOMATED_VALIDATED`.

Sources: official Fortinet documentation only (docs.fortinet.com), fetched 2026-11-23. No blog, forum, community or memory
source is used for any grade below; two search results from `community.fortinet.com` mentioned an implicit-deny output
shape and a user-policy behaviour and were deliberately **not** used — those semantics are `UNKNOWN` here.
Source keys: **CLI-FW** = FortiOS 7.6.4 CLI reference, `diagnose firewall`
(`https://docs.fortinet.com/document/fortigate/7.6.4/cli-reference/253375604/diagnose-firewall`; the 7.4.0 CLI reference
lists only `config` commands, so the `diagnose` reference is the 7.6.4 page). **NF-741** = FortiOS 7.4.0 New Features, "Update
policy lookup tool with policy match tool 7.4.1"
(`https://docs.fortinet.com/document/fortigate/7.4.0/new-features/482902/update-policy-lookup-tool-with-policy-match-tool-7-4-1`).
**AG-FP** = FortiOS 7.4.1 Administration Guide, "Firewall policy"
(`https://docs.fortinet.com/document/fortigate/7.4.1/administration-guide/656084/firewall-policy`).
**AG-PL** = FortiOS 7.0.12 Administration Guide, "Policy views and policy lookup"
(`https://docs.fortinet.com/document/fortigate/7.0.12/administration-guide/497952/policy-views-and-policy-lookup`).
**AG-DBG** = FortiOS 7.4.0 Administration Guide, "Debugging the packet flow"
(`https://docs.fortinet.com/document/fortigate/7.4.0/administration-guide/54688/debugging-the-packet-flow`) and "Using the
debug flow tool" (`.../38044/using-the-debug-flow-tool`). **AG-API** = FortiOS 7.4.3 Administration Guide, "REST API
administrator" (`https://docs.fortinet.com/document/fortigate/7.4.3/administration-guide/399023/rest-api-administrator`).
Fetch limits: the search box of docs.fortinet.com is script-rendered and could not be queried from this environment;
pages were reached by URL (found through the library's own page index and a web search restricted to docs.fortinet.com).
The FortiOS REST API **reference** (the per-endpoint field and parameter table) and the 7.4 CLI reference for
`diagnose` were not found as pages; everything that would have come from them is `UNKNOWN` below.

## 1. Where the collector stands (read, not changed)

- One interactive SSH shell per device per run, reads only; context moves (`config global`, `config vdom`, `edit <vdom>`,
  `end`) change no setting; VDOM names come from the device and are checked against `[A-Za-z0-9_.-]{1,31}`
  (`FORTINET_CONTRACT.md`; gate row `fgt_context_moves`, SIGNED_OFF, 30 s, no retry).
- Existing VDOM-context read for precedent: `fgt_get_routing_table` (`shell_context: vdom`, SSH_EXEC, 180 s, no retry,
  once per VDOM per inventory, one session per device per run).
- `FORTINET_CONTRACT.md` describes FortiGate access as SSH only (FortiManager is JSON-RPC, a different system); no REST
  token or HTTPS path to a FortiGate is described there, and no `diagnose` command of the policy kind is gated (a
  repository-wide search for such a path was not made).
- So a context switch into a VDOM is already possible and approved for reads; a `diagnose` read inside that context
  needs its own gate row and PO literal approval, and a REST candidate needs a new credential boundary.

## 2. Candidates

All three answer the question the validation asks: "for this 5-tuple entering this interface, which policy does the
device itself say matches?". Placeholders are filled only from a PO-chosen sample (source address, destination address,
numeric ports, numeric protocol, an interface name that exists in the stored inventory of that unit/VDOM); never from
free text. Examples use `192.0.2.x` / `198.51.100.x` and `FW-TANGO-04`.

### C1 — CLI policy lookup (recommended)

| Field | Proposal |
|---|---|
| Literal | `diagnose firewall iprope lookup <SRC> <SPORT> <DST> <DPORT> <PROTO> <IFACE> policy` — example shape `diagnose firewall iprope lookup 192.0.2.10 40000 198.51.100.20 443 6 port2 policy`. No `[auth type]`, `[user/group]`, `[server]` argument is sent. The sibling `diagnose firewall iprope clear` ("Clear policy statistic", CLI-FW) is **never** part of the literal or of any wrapper. `iprope6` (IPv6) is out of scope. |
| Why this shape | CLI-FW prints the syntax `diagnose firewall iprope lookup <src_ip> <src_port> <dst_ip> <dst_port> <protocol> <device> <pol_type> [<auth type>] [<user/group>] [<server>] [<user-db>] [<group-attr-type>]` with `<pol_type>` = "Firewall policy type: policy, proxy" and `<device>` = "Source interface". NF-741 gives the same with `<policy_type>` and the example `diagnose firewall iprope lookup 192.0.2.10 55555 <DST> 443 6 port2 policy user <USER>` (the page's own address, host and user name replaced by placeholders) (protocol 6 = numeric). Only `policy` (not `proxy`) is proposed: `proxy-policy` is outside the model (A0-FG-14). |
| Vendor / context | Fortinet FortiGate; shell context `vdom` — on a multi-VDOM unit the existing session issues `config vdom`, `edit <vdom>`, the lookup(s) for that VDOM's samples, then `end`; on a single-VDOM unit the lookup is sent at the top level. Whether `diagnose firewall iprope lookup` evaluates the VDOM entered with `edit` (and not another) is **UNKNOWN** in the documents found (section 3, row S1), so the first run records, per sample, which VDOM context was entered and the PO reads the MATCH rate per VDOM before trusting a multi-VDOM result. |
| Read/write class | Intended CLASS 0 (read). The CLI reference describes the command as "Lookup firewall policy that matches provided criteria"; it states nothing about changing state. It sends no packet and creates no session (NF-741 and AG-PL describe a lookup on a policy table, not traffic) — graded MEDIUM, not proven. It needs no debug filter and nothing to reset. |
| Privilege | **UNKNOWN.** No official page found names the administrator profile permission a CLI `diagnose firewall iprope lookup` needs. The first run therefore expects a possible refusal; a refusal is `NOT_EVALUABLE (LOOKUP_REFUSED)`, never retried, never escalated. No `sudo`-like or profile change is proposed. |
| Expected output size | NF-741 shows 8 lines (about 150 bytes). Official maximum: UNKNOWN. Proposal: cap 16 KB per call, fail closed (over cap = `NOT_EVALUABLE (OUTPUT_OVER_CAP)`). |
| Timeout / retry | 30 s per call (engineer proposal, same as `fgt_context_moves`; no official figure); no retry. |
| Frequency | At most 20 calls in total per run, one run per PO-chosen sample set; one call per sample; no polling; calls sent one at a time with the existing pause between commands. |
| Session reuse | The existing single SSH session per device per run (as the other FortiGate reads); no second session, no new credential. |
| Unsupported behaviour | (a) FortiOS older than 7.4.1: the 7-argument form is the one documented for 7.4.1 and later (NF-741 titles it "7.4.1"; CLI-FW is the 7.6.4 page). The 6-argument older form is not documented on any page found, so units reporting a lower FortiOS version (already stored from `get system status`) are `NOT_EVALUABLE (FGT_LOOKUP_FORM_UNPROVEN_FOR_VERSION)` and no command is sent. (b) Transparent mode: "Transparent mode does not support policy lookup function." (AG-PL, AG-FP) -> `NOT_EVALUABLE (TRANSPARENT_MODE)`. (c) Missing route: "When executing the policy lookup, you need to confirm whether the relevant route required for the policy work already exists." -> a sample whose route cannot be confirmed is reported as such, not as MISMATCH. (d) VIP, central NAT, zone, any-interface, schedule and user/group policies: see section 3. (e) Unparsable, refused, timed-out or empty output -> `NOT_EVALUABLE`, never `MISMATCH`. |
| Secret-output risk | Low. The call carries only the sample's addresses and ports (synthetic or masked in every report); the answer is a policy id, a type and an action. NF-741 shows the output may also carry web-filter fields (`webf_profile`, `webf_action`, `webf_cate`, `urlf_entry_id`): these are discarded in memory. No configuration text, key or password is in this output class (GRADE: from the one documented example only). |
| Sanitized projection kept | Per sample: the policy id (number), `matched_policy_type`, `policy_action` (accept/deny), and a presence-class for any other line. Everything else — the echoed addresses, interface name, web-filter lines — is dropped. Raw output is never persisted (AGENTS.md raw-evidence law); the transcript line holds the masked tuple only. |

### C2 — GUI / REST "policy lookup" monitor endpoint

| Field | Proposal |
|---|---|
| Literal shape | `GET /api/v2/monitor/firewall/policy-lookup/?srcintf=<IFACE>&sourceport=<SPORT>&sourceip=<SRC>&protocol=<PROTO>&dest=<DST>&destport=<DPORT>&policy_type=policy&ipv6=false&vdom=<vdom>` (parameter names exactly as printed in NF-741; the token goes in a header, never in the URL, see AG-API note below). NF-741 prints the response as JSON with `results.match`, `policy_id`, `matched_policy_type`, `srcaddr`, `dstaddr`, `user_group`, ... |
| VDOM context | A `vdom=<name>` query parameter (NF-741 example `vdom=vdom1`): no context switch is needed — the cleanest VDOM semantics of the three. |
| Read/write class | A `GET` on a `monitor` path; no state change is documented. Same MEDIUM grade as C1. |
| Privilege / credential | Token-based only: AG-API says an API administrator is created by a `super_admin`, receives a token shown once, is bound to an access profile and a mandatory trusted-host list (`Allow all (0.0.0.0/0) is not allowed`). That is a **new credential, a new transport (HTTPS to each FortiGate), and a trusted-host dependency on the neXus server address**. The Diagnostic-path law ("do not create a parallel diagnostic credential/network path when the existing controlled application path can already answer") applies, because C1 answers the same question over the existing SSH path. A genuinely separate path requires its own security/command review; this proposal does not request one. |
| Output size / timeout / frequency | Small JSON (NF-741 example). Same 20 / one run bound as C1 if ever chosen. Official size and timeout: UNKNOWN. |
| Unsupported behaviour | Same as C1; additionally the full parameter and response table of the REST API reference was not reachable, so required/optional parameters and the no-match response are `UNKNOWN`. |
| Secret-output risk | The request needs a bearer token; AG-API warns "there is no way for the FortiGate to provide this token again"; the 7.6/8.0 guides differ on whether the token may be sent in the URL — the 7.4.3 page was not checked for it, so a header-only rule would be a requirement of any later review. |
| Sanitized projection | Same as C1 (policy id, type, action only). |

### C3 — Debug flow (`diagnose debug flow ...`)

| Field | Proposal |
|---|---|
| Literal shape | A sequence, not one read: `diagnose debug enable`, `diagnose debug flow filter saddr <SRC>` / `sport` / `daddr` / `dport` / `proto`, `diagnose debug flow show function-name enable`, `diagnose debug flow trace start <N>`, then live traffic, then `diagnose debug flow trace stop` and a reset (AG-DBG lists `diagnose debug flow filter clear` "clear filter"; a `diagnose debug disable` is the counterpart of `enable`, not on the page fetched). |
| What it answers | It reports the policy that a **real packet** matched (AG-DBG's sample prints `Allowed by Policy-3: SNAT`). It cannot answer for a hypothetical flow: it needs a packet with that 5-tuple to actually arrive. A sampled flow that is not currently happening yields nothing. |
| Read/write class | **It changes device runtime state**: it enables the debug subsystem, sets a debug filter and starts a trace, all of which must be undone afterwards. Whether that state belongs to the session or to the device is `UNKNOWN` (not on the pages fetched). AG-DBG warns: "If FortiGate is connected to FortiAnalyzer or FortiCloud, the diagnose debug flow output will be recorded as event log messages and then sent to the devices. Do not run this command longer than necessary, as it generates a significant amount of data." So output would also leave the device in event logs. This is **not** established CLASS 0 and this proposal does not claim it is. |
| Other limits | "Flow monitoring does not work for traffic offloaded to NP6 or NP7 processors" (AG-DBG); the usual workaround, disabling NP6 fast path or NP7 offload per policy, is a configuration change and is out of the question. Output is an unbounded message stream (size UNKNOWN). |
| Privilege / timeout / frequency / reuse | Privilege UNKNOWN; timeout, frequency and reuse would all depend on live traffic and cannot be bounded to "20 calls, one run". |
| Secret-output risk | The stream carries real client addresses and ports of production traffic (CLASS 3 identity data) in bulk. |
| Sanitized projection | Would be the same policy id/action, but only after parsing an unbounded stream of real-traffic lines. |

## 3. Semantics the comparison depends on

Grades: HIGH / MEDIUM / LOW / UNKNOWN. A quote is an exact sentence from the page named; "UNKNOWN" means no official sentence
was found (the absence is stated, nothing is filled from general knowledge).

| Id | Question | Source, version | Quote | Grade | Used as |
|---|---|---|---|---|---|
| S1 | Is the lookup evaluated for the VDOM entered with `edit <vdom>`? | none found | - | UNKNOWN | the first run records the entered VDOM per sample; multi-VDOM results stay `NOT_EVALUABLE` until the PO reads a single-VDOM unit's MATCH rate first (recommended order, section 5) |
| S2 | Same first-match order as A0-FG-1/2 (sequence order, first match wins)? | AG-PL (7.0.12), AG-FP (7.4.1): "Interface Pair View displays the policies in the order that they are checked for matching traffic, grouped by the pairs of incoming and outgoing interfaces" / "By Sequence displays policies in the order that they are checked for matching traffic without any grouping." (list order); for the lookup tool: "Use this tool to find out which policy matches specific traffic from a number of policies." (AG-PL 7.0.12; AG-FP 7.4.1) | as quoted | MEDIUM for the list order (unchanged A0-FG-1); **UNKNOWN** that the lookup returns the *first* match in that order (the tool is described as finding "which policy matches", not as first-match) | the comparison itself is the evidence for A0-FG-2; a disagreement where neXus has two matching rules is reported as MISMATCH with the count of such samples, not as a neXus error |
| S3 | Implicit deny (A0-FG-3) — what does the lookup print when nothing matches? | AG-FP (7.4.1): "If the parameters do not match any configured policies, the traffic is denied." (the A0-FG-3 sentence, 7.4: "If no IPv4 policy matches the traffic, then it will match the implicit deny policy and will be dropped.") | as quoted | HIGH for the deny; **UNKNOWN** for the lookup's no-match output shape (NF-741 shows only a match example; its `firewall proxy-policy id: 0` line is not explained) | a sample neXus resolves to implicit deny is compared only if the output is classifiable as "no policy"; a numeric id `0` is **not** assumed to mean implicit deny. If unclassifiable -> `NOT_EVALUABLE (NO_MATCH_SHAPE_UNPROVEN)`; the first run records the line-name set (presence booleans) so the shape can be frozen afterwards |
| S4 | VIP policies (A0-FG-18, A0-FG-19) | AG-FP (7.4.1): "the FortiGate matches firewall policies with VIPs applied differently than typical firewall policies. Policies with VIPs applied have priority over typical firewall policies." Lookup pages: silent on VIPs | as quoted | MEDIUM (unchanged A0-FG-18); lookup behaviour for VIP destinations **UNKNOWN** | samples whose destination equals a VIP external address are a separate stratum: the PO is told their count; their MATCH/MISMATCH is the first real-environment evidence for A0-FG-18/19, never proof |
| S5 | `match-vip` (A0-FG-20) | AG-FP (7.4.1): "Enable/disable matching of VIPs when used in a policy with a deny action." and "If the policy action is set to accept, match-vip cannot be enabled." | as quoted | HIGH for the text; the lookup's treatment UNKNOWN | as S4 |
| S6 | Ingress interface `any`, multi-interface policies, zones, SD-WAN zones | AG-FP (7.4.1) lists policies with "any or multiple interfaces"; A0-FG-9 for zones. The lookup's `<device>` is "Source interface" (CLI-FW); whether a member interface matches a zone-bound policy and how `any` matches is not stated | `<device>` "Source interface." | UNKNOWN for zone/`any` handling in the lookup | the estate has no zones (A0-FG-10); `any` samples are a separate stratum counted by the PO |
| S7 | Destination interface: the lookup has no egress-interface argument | CLI-FW / NF-741: arguments end at `<device>` (source interface) and `<pol_type>`; "you need to confirm whether the relevant route required for the policy work already exists" (AG-PL, AG-FP) | as quoted | MEDIUM that the egress interface comes from the unit's routing table; **UNKNOWN** how | the neXus evaluation uses the egress interface from the stored route (as Trace does); a sample whose route is ambiguous in neXus is `NOT_EVALUABLE` before any command |
| S8 | Schedules | none found | - | UNKNOWN | a sample matching a rule with a schedule other than `always` is excluded from the PO's set or its mismatch is classed `SCHEDULE_UNPROVEN` |
| S9 | Users / groups / identity-based policies | NF-741: the tool "adds the ability to return a new policy match results page ... and the ability to use identity-based policy matching" with `[auth type] [user/group]`; "The web filter action tracing and user matching functionalities are not available, and diagnose firewall iprope lookup can only be used for basic policy lookups" (entry-level FortiGates). How a lookup *without* user arguments treats a user-bound policy: not stated | as quoted | MEDIUM for the identity arguments' existence; UNKNOWN for the no-user case | policies with `groups`/`users` are `UNKNOWN` in neXus already (A0-FG-12); samples hitting them are excluded from the comparison counts and reported as a count |
| S10 | Hardware offload and sessions | AG-DBG: "Flow monitoring does not work for traffic offloaded to NP6 or NP7 processors." | as quoted | HIGH for debug flow only | argues against C3; no effect on C1 (documents do not tie the lookup to sessions) |
| S11 | Is the lookup read-only? | CLI-FW: "Lookup firewall policy that matches provided criteria." | as quoted | MEDIUM (no state change stated; none denied) | gate class proposal CLASS 0, subject to the PO's approval and the first run's state check below |
| S12 | Required administrator privilege for the CLI form | none found | - | UNKNOWN | first run may be refused; handled as `NOT_EVALUABLE` |
| S13 | FortiOS versions | NF-741 ("7.4.1"), CLI-FW (7.6.4), AG-PL (7.0.12: GUI "Policy Lookup" only, no CLI literal) | - | HIGH that the 7-argument CLI form is documented from 7.4.1; UNKNOWN for older forms | version gate in section 2 (C1 unsupported behaviour) |

## 4. Comparison and what the record contains

For each PO-chosen sample (a masked 5-tuple plus ingress interface, taken from a stored policy snapshot of the same unit and
VDOM) neXus computes its own lookup (`PolicyLookup`, FortiGate single rulebase) **before** the device is asked, in memory.
After the device answers, both sides are reduced locally:

- `MATCH` — neXus rule id (its `policyid`) equals the device policy id and the actions are equal;
- `MISMATCH` — both sides gave a classifiable answer and the policy id or the action differ;
- `NOT_EVALUABLE` — no command sent (version gate, transparent mode, ambiguous route, excluded policy kind) or the
  answer was refused / unparsable / over cap / unclassifiable;
- `AMBIGUOUS` is used only when the neXus side itself reports two possible rules (VIP parallel evaluation).

The stored record (AGENTS.md sensitive-identity law: the relationship, not the values) is: run id, unit and VDOM as opaque
references, sample count (<= 20), the count of `MATCH` / `MISMATCH` / `NOT_EVALUABLE` / `AMBIGUOUS` with the reason-class
counts, per-sample only the sample's own opaque reference, the verdict, and the booleans "policy ids equal" / "actions equal";
plus presence booleans of the output line names seen. No address, port, interface name, policy name, serial, user name or raw
output enters the record, the transcript, Git metadata or chat. The PO reads aiview-masked pseudonyms (`FW-TANGO-04`)
only.

The first run also records one derived fact needed to close S3: "no-match output shape class" as a set of line-name
presence booleans (no values). Also recorded: whether the entered VDOM matches the sample's VDOM (S1), and whether any
configuration `change_state` appeared on the unit between the stored snapshot and the lookup (a changed snapshot makes the
sample `NOT_EVALUABLE (SNAPSHOT_STALE)`, not MISMATCH).

State check (S11): immediately before and after the run the existing read `get system status` result is unchanged in the
fields it already stores; any difference stops the run. This is a presence check on already-collected facts, not a new read.

## 5. Recommendation and the question for the PO

**Recommend C1 first.** It uses the existing SSH session and credential, needs no VDOM-changing flag beyond the already
approved context moves, needs no debug state, sends no traffic, produces about 150 bytes per sample, and its output maps
directly to the policy id and action that neXus stores. It is the exact FortiGate counterpart of ASA R2
(`packet-tracer`) and of PAN A6 row 4 (`security-policy-match`), both of which are also one command per sample.

- C2 (REST) has cleaner VDOM selection but adds an HTTPS path and a `super_admin`-created token with trusted-host binding:
  a new credential boundary for the same answer. Reconsider only if C1's VDOM semantics (S1) prove unusable.
- C3 (debug flow) is **not recommended**: it needs real traffic, changes runtime debug state that must be reset, can
  forward event logs to FortiAnalyzer/FortiCloud, is blind to NP-offloaded sessions, has an unbounded output and carries
  real client addresses.

Suggested order if approved: first a single-VDOM unit (S1 does not arise), up to 20 samples; a multi-VDOM unit only after
the PO has read that result. Samples should include a spread the PO considers representative; VIP-destination samples,
`any`-interface samples and implicit-deny samples are each counted as their own stratum so S3, S4 and S6 get evidence.

**Question for the PO (exact):** "Approve literal
`diagnose firewall iprope lookup <SRC> <SPORT> <DST> <DPORT> <PROTO> <IFACE> policy`
(TCP/UDP only; `<PROTO>` numeric `6` or `17`; `<SRC>`/`<DST>` IPv4 literals; ports numeric 1-65535; `<IFACE>` an interface
name stored for that unit and VDOM) for at most 20 PO-chosen masked samples on FortiGate units, read-only (CLASS 0), 30 s,
no retry, one run, inside the existing session with the approved VDOM context moves, only on units whose stored FortiOS
version is 7.4.1 or later, with the sanitized projection of section 2 (policy id, type, action) and the comparison record
of section 4?" The PO may approve, change the literal, restrict the units, or choose C2 instead (which would need its own
security review first).

## 6. If approved (not part of this proposal)

Gate row and `CAPABILITY` entry for the approved literal, a bounded parser (policy id / type / action only) and the
validation runner would be separate engineering steps after the approval, each under the network-device command gate
(`docs/AI_DEVELOPMENT_PROTOCOL.md`): why required, class, vendor/shell/context, timeout, retry, frequency, session reuse,
unsupported behaviour, secret-output risk, safe telemetry. Nothing here is such an entry.

## 7. Open items

- The REST API reference page for `policy-lookup` (parameter table, no-match response) and a 7.4 CLI reference page for
  `diagnose firewall iprope lookup` were not reachable in this environment: S1, S3 (output shape), S7 (how the egress
  interface is chosen), S8 and S12 remain `UNKNOWN`, and would be re-checked if a PO session can open them.
- The estate's FortiOS versions per unit are in the stored inventory; this proposal did not read them. If any unit is below
  7.4.1, C1 cannot be used there (C1 unsupported behaviour (a)).
