# PAN-OS 11 Vendor Vocabulary Evidence — D-V1 / D-V2 / D-V3a

Status: DRAFT (evidence-gathering only; not a contract, not FROZEN)

Movement type: READ_ONLY_AUDIT. This document does not change, freeze, or
propose a value for any predicate in `utils/failover/preflight_readiness.py`.
It only records what official PAN-OS 11.x documentation does and does not
establish, so the Product Owner can decide D-V1/D-V2/D-V3a in
`project/QUEUE.md`. Per the Authority hierarchy in `AGENTS.md`, a `DRAFT`
document must not be cited as approving a predicate — this one especially,
since it is the evidence a future freeze would cite, not the freeze itself.

## Method and honest limitations

- Fetched raw HTML from `docs.paloaltonetworks.com` via `curl` (not a
  summarizing fetch tool) and parsed it locally to text, so quotes below are
  the page's own text, not a paraphrase.
- PAN-OS 11.2-pinned pages under `pan-os/11-2/...` returned HTTP 404 for
  every path tried (see per-decision "URLs attempted" lists). The current
  Palo Alto docs platform serves the still-supported-version tree at
  `pan-os/11-1/...` and `pan-os/11-0/...` (11.0 marked "(EoL)" in the
  version picker) and a version-unpinned "current" mirror at
  `ngfw/administration/...`. Where a `pan-os/11-2/...` path 404s, that is
  reported as "PAN-OS 11.2-pinned page: 404", not silently swapped for a
  different version — per the instruction not to substitute a different
  release when a claimed URL 404s.
- The `pan-os/11-1/...` and `pan-os/11-0/...` pages fetched below returned
  byte-identical body text to each other and to the `ngfw/administration/...`
  mirror for the same topic (diffed locally). This is reported as an
  observation, not assumed: it means the fetched content is confirmed
  current for both 11.0 and 11.1 at fetch time (2026-09-12), not that PAN
  guarantees no 11.x-to-11.x drift ever existed.
- No CLI/API schema reference page enumerating `show high-availability
  state` XML/CLI leaf names (`conn-status`, `conn-ha1`, `conn-ha1-backup`,
  `conn-ha2`, `state-sync`, `app-compat`, `av-compat`, `threat-compat`,
  `url-compat`, `preemptive`, `last-error-reason`, `last-error-state`,
  `serial-num`) was found after targeted search of the PAN-OS 11.x admin
  guide, the CLI quick-start section, and the public knowledge base. The
  admin guide documents these as *concepts* in narrative prose (states,
  synchronization, preemption) and the knowledge base documents *some*
  narrative CLI output strings for troubleshooting — neither is a schema
  reference for the leaf-level tokens the parser consumes. This absence is
  itself evidence and is carried into each decision below as `UNKNOWN`,
  not filled from general model knowledge (`AGENTS.md`, Vendor semantics
  law).

## D-V1 — `conn-status` / `conn-ha1` / `conn-ha1-backup` / `conn-ha2`

**1. Exhaustive value list or only examples?** Only examples — not
exhaustive, and only for `conn-status` specifically (no `conn-ha1`,
`conn-ha1-backup`, or `conn-ha2` string vocabulary found at all in
narrative documentation).

Source: Palo Alto Networks Knowledge Base article "High Availability -
'HA Peer Connection Status'" (`id=kA14u000000oNlUCAU`), tagged for PAN-OS
9.1, 10.1, 10.2 and 11.0. Fetched
`https://knowledgebase.paloaltonetworks.com/KCSArticleDetail?id=kA14u000000oNlUCAU&lang=en_US`
(HTTP 200). Quoted CLI output block from the article:

> ```
> Peer Information:
>     Connection status: down
>     Connection down reason: HA1 link went down
>     Last non-functional state reason: Dataplane down: user triggered
> ```

and, immediately after that block:

> "Other possible Connection down reasons include:
> Heartbeat ping failure
> Never able to connect to peer
> Error in connection detected
> Peer HA agent exiting
> Hello protocol failure
> Capability exchange with peer failed
> HA1 encryption configuration mismatch
> SSH Tunnel reset"

The phrase "Other possible ... reasons include" is explicitly a
non-exhaustive example list for `Connection down reason` (a distinct field
from `Connection status` itself). The article shows `Connection status:
down` as the failure example; it never enumerates the full `Connection
status` value set (e.g. it does not, in this text, also show a `Connection
status: up` example block, though "up" as the healthy counterpart is
consistent with the code comment's citation of the same KB article title
"HA Peer Connection Status: up/down" — that summary line was not
independently re-confirmed verbatim in the fetched HTML; the fetched page's
literal displayed string is `Connection status: down`, not a table of both
values).

**2. Code vs. docs match?** The code (`_PAN_CONN_UP = ("up",)`,
`_PAN_CONN_DOWN = ("down",)`, comment at
`utils/failover/preflight_readiness.py:133-137`) freezes exactly "up" as
healthy and "down" as the one documented failure token, UNKNOWN otherwise.
This is consistent with what was found — no documented value exists that
the code would wrongly treat as UNKNOWN, and no undocumented value is
treated as healthy, because "up" is the only value the code accepts as
healthy and it is not contradicted by any fetched text. The code is not
more permissive than the evidence supports. Whether "up" is itself
*exhaustively* the only healthy token (as opposed to, say, some
transitional token) is UNKNOWN — no page enumerates it as exhaustive.

**3. Missing-field meaning.** Not documented. No fetched page states what
absence of a `conn-status` (or `conn-ha1`/`conn-ha1-backup`/`conn-ha2`)
leaf in `show high-availability state`/`show high-availability all` output
means (e.g., link not configured vs. collection truncation vs. platform
without that link). `UNKNOWN` — the repository's existing UNKNOWN/fail-closed
law already covers this; this evidence pass found nothing to narrow it.

URLs actually fetched for D-V1:
- `https://knowledgebase.paloaltonetworks.com/KCSArticleDetail?id=kA14u000000oNlUCAU&lang=en_US` — HTTP 200 (used above).
- `https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-web-interface-help/device/device-high-availability/ha-link-and-path-monitoring` — HTTP 200, but content is about link/path *monitoring configuration* (interfaces/thresholds), not the `conn-*` CLI leaf vocabulary; no relevant quotable text found.
- `https://docs.paloaltonetworks.com/pan-os/11-0/pan-os-web-interface-help/device/device-high-availability/ha-link-and-path-monitoring` — HTTP 404.
- `https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/high-availability/ha-links-and-backup-links` — HTTP 404 (this admin-guide path does not exist for 11.1; the topic lives only under the unversioned `ngfw/administration/high-availability/ha-links-and-backup-links`, which was not treated as a "PAN-OS 11.x" citation for this document since it carries no version pin).
- `https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/high-availability/ha-ports-on-palo-alto-networks-firewalls` — HTTP 404 (same unversioned-only situation).

## D-V2 — `state-sync` / `*-compat` / `preemptive` / `last-error-*`

**1. Exhaustive value list or only examples?** Not exhaustive for any of
the four. `state-sync`'s healthy value "Complete" is not directly
documented at all in the fetched pages (see below) — the closest
documentation is at the *feature* level ("state (session) synchronization"
enabled/disabled), not the CLI status token. `*-compat` (app/av/threat/url
compatibility) has no documented value vocabulary at all in the fetched
pages — the admin guide's HA-synchronization reference discusses
*content/version incompatibility as a cause of sync failure* in prose, not
a `Match`/`Mismatch` field. `preemptive` is documented only as a
configuration concept (enabled/disabled), not as an output-field token.
`last-error-*` binding (which `state-sync`/`*-compat` failure it correlates
to) is not documented anywhere found.

Source: "Reference: HA Synchronization", fetched at both
`https://docs.paloaltonetworks.com/pan-os/11-0/pan-os-admin/high-availability/reference-ha-synchronization`
and
`https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/high-availability/reference-ha-synchronization`
(both HTTP 200, byte-identical body text). Relevant quotes:

> "Use the no form of the following CLI configuration command to disable
> state (session) synchronization on the firewalls:
> `username@hostname#set deviceconfig high-availability group
> state-synchronization enabled`"

> "The HA configurations won't synchronize for the following reasons: ...
> If the PAN-OS versions are incompatible on HA peers. ... If the URL
> databases are incompatible on the HA peers. ... Additionally, a plugin
> mismatch might (not always) prevent configurations from synchronizing."

Neither quote gives a `state-sync` field value (e.g. "Complete") or an
`app-compat`/`av-compat`/`threat-compat`/`url-compat` field value (e.g.
"Match"/"Mismatch"); both describe the underlying feature/behavior in
prose, not the monitoring-command output schema.

Source: "Device Priority and Preemption", fetched at
`https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/high-availability/ha-concepts/device-priority-and-preemption`
(HTTP 200). Quote:

> "By default, preemption is disabled on the firewalls and must be enabled
> on both firewalls. When enabled, the preemptive behavior allows the
> firewall with the higher priority (lower numerical value) to resume as
> active or active-primary after it recovers from a failure."

This documents preemption as a two-state *configuration* concept
(enabled/disabled) at the feature level, not the literal token(s) the
`local-info`/`peer-info` `preemptive` leaf emits in `show
high-availability state` (e.g. whether it prints `yes`/`no`, `True`/`False`,
or something else). `UNKNOWN` for the literal CLI token vocabulary.

**2. Code vs. docs match?** `_PAN_STATE_SYNC_COMPLETE = ("complete",)` and
`_PAN_COMPAT_MATCH` / `_PAN_COMPAT_MISMATCH` = `("match",)` / `("mismatch",)`
(comment at `utils/failover/preflight_readiness.py:129-142`) are **not**
independently corroborated by any fetched PAN-OS 11.x page — the doc
comment already says "No KNOWN_BAD vocabulary is frozen for `state-sync`"
and this evidence pass agrees there is no documented `state-sync` or
`*-compat` failure token either. The code's existing behavior (accept only
the named healthy token; UNKNOWN for anything else, no failure token
outside `*-compat`'s "Mismatch") is at least as conservative as the
documentation supports, since the documentation supports *none* of these
literal tokens directly — the code is not claiming more than the docs
give it, but the docs also do not affirmatively back "complete"/"match"/
"mismatch" as the literal wire tokens; that traces to prior operational/
real-environment evidence outside this document's scope (this document is
docs-only, per the task). No documented value was found that the code
would wrongly bucket as UNKNOWN, and no undocumented value was found
frozen as healthy in the code beyond what the pre-existing comment already
disclosed as unconfirmed.

**3. Missing-field meaning.** Not documented for any of `state-sync`,
`*-compat`, `preemptive`, or `last-error-*`. `UNKNOWN`.

`last-error-*` binding specifically: no fetched page names a `last-error-
reason` or `last-error-state` leaf under `local-info`/`peer-info`, nor
states which of `state-sync`/`*-compat` (or any other field) it is bound
to when populated. `UNKNOWN` — full stop, not narrowed by this pass.

URLs actually fetched for D-V2:
- `https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/high-availability/reference-ha-synchronization` — HTTP 200.
- `https://docs.paloaltonetworks.com/pan-os/11-0/pan-os-admin/high-availability/reference-ha-synchronization` — HTTP 200 (identical body to 11-1).
- `https://docs.paloaltonetworks.com/pan-os/11-2/pan-os-admin/high-availability/reference-ha-synchronization` — HTTP 404.
- `https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/high-availability/ha-concepts/device-priority-and-preemption` — HTTP 200.
- `https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/high-availability/ha-concepts/failover` — HTTP 200 (see D-V3a/general section for the functional/non-functional quote; no `*-compat`/`state-sync`/`preemptive` token vocabulary found here either).
- `https://docs.paloaltonetworks.com/pan-os/11-0/pan-os-admin/high-availability/ha-concepts/failover` — HTTP 200, identical body to 11-1.

## D-V3a — `local-info` / `peer-info` `serial-num` semantics (docs-only half)

**1. Exhaustive value list or only examples?** Not applicable in the usual
sense — `serial-num` is an identifier field, not an enumerated-vocabulary
field, so "exhaustive value list" does not apply the way it does to
`conn-status`/`state-sync`. The real question this decision needs answered
is *semantic*: does `local-info/serial-num` in `show high-availability
state` output the acting device's own chassis serial, and does
`peer-info/serial-num` output the peer's own reported chassis serial (as
opposed to, e.g., a VSID, a cluster/group identifier, or a
locally-computed value)? No fetched PAN-OS 11.x page states this
explicitly. Every page found that discusses `local-info`/`peer-info`
(the KB connection-status article, the admin-guide HA topics) shows
`State`, `Connection status`, `Connection down reason`, `Last
non-functional state reason` — none shows a `serial-num` (or `Serial
Number`) leaf under `Local Information:` / `Peer Information:` in a
quoted CLI output block.

**2. Code vs. docs match?** Not evaluable from documentation alone — there
is no documented field to compare the code's handling of `serial-num`
against. This is a **docs-only half** of D-V3a per its `project/QUEUE.md`
description; per the identifier law in `AGENTS.md`, no equivalence should
be assumed here regardless (opaque-identifier rule applies independent of
what this document finds).

**3. Missing-field meaning.** Not documented. `UNKNOWN`.

This decision's real-environment half (D-V3b: "PAN peer-serial real
correspondence (B2) on the approved real pair") is out of scope for this
document — `project/QUEUE.md` already tracks it separately as "B2 NOT
ESTABLISHED", and nothing fetched here changes that.

URLs actually fetched for D-V3a: the same KB and admin-guide URLs listed
under D-V1/D-V2 above were checked for any `serial-num`/`Serial Number`
CLI leaf in a quoted output block; none was found. No additional URL
search under vendor documentation surfaced a dedicated `show
high-availability state` field-by-field schema reference page.

## Supporting context found (not one of D-V1/D-V2/D-V3a, but corroborates
the code's existing frozen `state` vocabulary)

Source: "HA Firewall States", fetched at
`https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/high-availability/ha-firewall-states`
and `https://docs.paloaltonetworks.com/pan-os/11-0/pan-os-admin/high-availability/ha-firewall-states`
(both HTTP 200, identical body). Table lists exactly: Initial, Active,
Passive, Active-Primary, Active-Secondary, Tentative, Non-functional,
Suspended.

Source: "Failover", same URLs as in D-V2's URL list. Quote:

> "States that are functional are active, passive, active-primary, and
> active-secondary. States that are not functional are initial,
> non-functional, tentative, and suspended."

This corroborates `PAN_NON_FUNCTIONAL_STATES = frozenset({"initial",
"non-functional", "tentative", "suspended"})` at
`utils/failover/preflight_readiness.py:119-122` word-for-word against
PAN-OS 11.0/11.1 documentation (11.2-pinned page 404s, see next section) —
this vocabulary is exhaustive and matches the code exactly. This is not one
of the three assigned decisions (it is the contract's already-`ESTABLISHED`
claim per the existing code comment) and is included here only as
corroborating evidence gathered incidentally.

## PAN-OS 11.2 page-availability note

Every `pan-os/11-2/...` path attempted in this session 404s:
`.../pan-os-admin/high-availability/ha-firewall-states`,
`.../pan-os-admin/high-availability/reference-ha-synchronization`. The
11.2 version selector is present and listed in every fetched page's
navigation ("PAN-OS 12.2 / PAN-OS 12.1 / PAN-OS 11.2 / PAN-OS 11.1 / PAN-OS
11.0 (EoL) / ..."), so 11.2 is a real, current release in PAN's own
version picker; its content for these topics could not be located at the
`pan-os/11-2/...` path pattern in this session. This is reported as "page
not found at the attempted path", not as "PAN-OS 11.2 lacks this
documentation" — per the task's caution about a path moving between
releases, the correct 11.2 path segment was not independently discovered
here and should not be assumed absent.

## Summary table

| Decision | Exhaustive? | Code stricter/looser than docs? | Missing-field meaning |
|---|---|---|---|
| D-V1 (`conn-*`) | No — `Connection status` example only shows "down"; `Connection down reason` explicitly "include"s more | Matches; code is not looser than evidence supports | UNKNOWN |
| D-V2 (`state-sync`/`*-compat`/`preemptive`/`last-error-*`) | No — none of the four literal CLI tokens documented; only feature-level enable/disable prose | Code's healthy tokens are unconfirmed by docs (pre-existing, disclosed) but not contradicted; no doc value would be wrongly UNKNOWNed | UNKNOWN for all four |
| D-V3a (`serial-num`) | N/A (identifier, not enum) — no field even shown in a quoted output block | Not evaluable from docs | UNKNOWN |
