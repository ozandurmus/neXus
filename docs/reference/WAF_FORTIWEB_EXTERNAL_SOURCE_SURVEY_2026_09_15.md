# WAF / FortiWeb — external source survey, 2026-09-15

## Status

**DRAFT — SURVEY, NOT AUTHORITY.** This document records what was observed in a
third-party public repository that the Product Owner asked be surveyed for a
future WAF capability. It decides nothing, freezes nothing, and authorizes no
implementation.

It is **not** a measurement record. `roles/PO.md` §2 requires that a contract
naming a vendor command, route or field may only be implemented once a
committed measurement record exists beside it, stating which tool was used,
which commands were run and which field names came back. Nothing below meets
that bar: every vendor detail here was read out of someone else's source code,
never observed against a FortiWeb appliance. Under `AGENTS.md`'s vendor
semantics law — a field name is not its contract, a command name is not its
semantics — these are **leads for where to point a future measurement**, not
evidence of anything.

## Why this record exists

WAF is not in any current neXus contract. The Product Owner expects a WAF
capability to become relevant (config collection and backup), and asked that
what was found be written down now rather than rediscovered later. This record
is that note, and its main job is to carry two things forward: the small amount
that is genuinely useful, and the reason none of it may be copied.

## The source

A public GitHub repository surveyed on 2026-09-15 at its then-current HEAD.
Python (FastAPI) backend, React frontend, PostgreSQL, Redis/Celery, Nginx.
Self-described as a "Phase 1 foundation for a containerized security assessment
platform" carrying FortiWeb snapshot, parsing and maturity-scoring APIs.

Observed shape, as counts: 13 Python modules, of which two carry almost all of
the code (one ~214 KB, one ~58 KB); the remaining eleven are under 4 KB each.
No test coverage of the authentication path was found.

## Licensing — the blocking finding

**The repository carries no licence file, and GitHub reports no licence for
it.** A public repository without a licence is *all rights reserved*: publication
grants the right to view and to fork within GitHub, and no right to copy, modify,
redistribute, or incorporate into another product.

Consequently **no code, no configuration and no file from this source may enter
this repository or the product**, regardless of how small or how useful the
fragment appears. This is a legal constraint, not a style preference, and it is
not waived by the source being easy to find.

Two routes would change this, and both are the Product Owner's to pursue:

1. The author adds an OSI licence (Apache-2.0 or MIT) to the repository.
2. The author grants written permission for the specific reuse intended.

Until one of those exists, this record is the only artefact that may derive from
the source, and it derives *facts about* it rather than *content from* it.

A separate consideration even after a licence: taking third-party code into a
product in this domain is a supply-chain decision with its own review, not an
automatic consequence of a permissive licence.

## What is potentially useful

Two things, both at the level of where to look rather than what to write.

**FortiWeb management API surface.** The source configures two REST paths under
a `/api/v2.0/cmdb/` prefix — one for WAF configuration and one for server-policy
objects — reached over HTTPS with a token credential. If a WAF capability is
scoped, these are a reasonable first place to point a measurement session.
Marked `UNVERIFIED`: the paths were read from a configuration default, not
observed against an appliance, and neither their response shapes nor their field
semantics are known to this repository.

**A snapshot → parse → score pipeline shape.** The source collects a
configuration snapshot, parses it, and derives a maturity score. The first two
stages correspond to what neXus already calls Configuration collection; the third
corresponds to the Alignment plane, where expected intent is compared against
actual state. The correspondence is at the level of the idea. It is worth noting
that neXus's own Configuration/Alignment separation
(`AGENTS.md`, engineering laws) is stricter than what the source implements,
which collapses them.

## What must precede any WAF implementation

In order, none skippable:

1. A Product Owner decision that WAF is in scope at all, with its own record.
2. A measurement record against a real FortiWeb, per `roles/PO.md` §2 — which
   tool, which commands, which field names came back, as counts and shapes.
3. The network-device command gate for every command or route the capability
   will issue (`docs/AI_DEVELOPMENT_PROTOCOL.md`): vendor, read/write class,
   transport, timeout, retry, frequency, session reuse, unsupported behaviour,
   secret-output risk, safe telemetry.
4. A FROZEN contract before implementation, since a new vendor introduces new
   vendor semantics (`AGENTS.md`, mandatory build lifecycle).
5. Placement of its authenticated surface, which is blocked on `AUTH-PLACEMENT`
   (`PO_DECISION_RECORD_2026_09_13D` §3) exactly as backup and failover are.

## Anti-patterns observed, and the neXus clause that already forbids each

Recorded so the survey cannot later be mistaken for an endorsement, and so that
a future reader who does obtain a licence knows which parts must not be carried
across. Each was read directly from the source.

| Observed in the source | Already forbidden here by |
|---|---|
| Authorization role taken from a client-supplied request header, trusted without verification, across eight endpoints | `AGENTS.md` architectural invariants — no security decision originates in the browser |
| Login returns a fixed literal token string that no code path ever verifies; no signing, session or expiry machinery exists | `UI2_0_B1_03` identity and sessions; the session/CSRF work already merged here |
| A hardcoded default administrator credential pair that configuration cannot disable, because it is OR-ed with the configured one | `AGENTS.md` privacy and DLP — no real or default secrets in source |
| LDAP authentication helper defined but never called from the login path; dead code presented as a feature | `AGENTS.md` — contract status and honest capability reporting; `UNKNOWN` over claimed certainty |
| LDAP search filter built by string interpolation of the supplied username | Injection; identity law — identifiers are opaque and are never pasted into a query language |
| LDAP bind with no empty-password guard, so an unauthenticated bind can read as success | `AGENTS.md` UNKNOWN / fail-closed law |
| Plaintext `ldap://` default with no StartTLS and no certificate validation | Backlog `ldap_tls_trust_store_pin_and_format`; production TLS requires trusted CA verification |
| Vendor TLS verification disabled by default | `AGENTS.md` Palo Alto section — POC TLS exceptions are debt, never production design |
| CORS configured to allow every origin, including plain HTTP, alongside header-trust authorization | `AGENTS.md` architectural invariants |
| Device API keys persisted as a plain string column | The encrypted credential-reference design already shipped here |
| Database backup invoked with the connection password in the process argument vector, readable by any local user | `AGENTS.md` raw-evidence and privacy laws |
| Backup artefact neither encrypted nor digest-verified, and its restore path begins by dropping every table | `UI2_0_C7` backup artefact contract — encrypted artefacts, digest verification, restore disabled |
| A hardcoded public IPv4 address as a vendor endpoint default | `AGENTS.md` sensitive identity reporting law |
| Scheduler enabled by default | The scheduler is deliberately deferred here and has no enabled product path |

No credential value, address, hostname or token from the source is reproduced in
this table or anywhere in this record; each row states the relationship, not the
value (`AGENTS.md` sensitive identity reporting law).

The author may wish to know that the header-trust authorization and the
non-disableable default credential are publicly readable today. Telling them is
the Product Owner's to do, not an agent's.

## What this record does not decide

Whether WAF enters scope. Whether FortiWeb is the vendor if it does. Whether any
licence is obtained. Whether any of the observed API surface is correct. All are
open, and none is advanced by this document existing.
