# Check Point policy collection: parallel MDS sessions

Status: **FROZEN — PO APPROVED** (2026-10-02)

Product Owner authorization, verbatim: "abi izin verdik ya 4 oturumla çekebilirsin bunu"

## Scope and measured baseline

The PO-provided serial measurements are: `show-domains` 1.8 seconds,
`show-packages` approximately 10 seconds, one access-rulebase page of 100
rules 25–36 seconds, and one domain with 15,000+ rules approximately 75
minutes. These are baseline observations, not a parallel-speedup guarantee.

This decision is the vendor interaction-safety gate for concurrent policy
collection against the same Check Point MDS. It authorizes at most four
independent trusted interactive SSH sessions, reusing the existing approved
`mgmt_cli` reads and their parameters, shell context and timeouts. It adds
no verbs, parameters, command-gate rows, credentials or device writes.

## Safety and result invariants

- Start with two concurrent work units, capped by the configured limit.
  After three consecutive successful pages taking less than 60 seconds,
  increase concurrency up to four. A slower page resets the success streak.
- On a page timeout, SSH/session error, or an `mgmt_cli` error mentioning
  sessions, locks or "too many", halve concurrency, with a minimum of one,
  and retry that page once. A failed retry is reported as incomplete;
  authentication, trust, lease and deadline failures remain fail-closed.
  Existing in-flight reads finish before the reduced admission limit is
  applied to new work. Idle sessions do not issue reads.
- Work units are domain/layer/page reads. Fetch the first page before
  scheduling the remaining offsets of a layer. Other layers and domains
  fill available slots. Assemble and validate pages by offset, preserving
  per-layer rule order, dictionaries and inline-layer discovery.
- Publish completed layers incrementally. Preserve changed-only reuse and
  domain admission. Cancellation stops new work and retries, drains bounded
  in-flight reads, publishes completed layers and closes every session.
- Record concurrency transitions as counts only in logs and the existing
  job transcript. Raw responses remain in memory; no vendor error text,
  identities or credentials enter diagnostics.

## Configuration and disablement

Worker setting: `ui2.policy.cp.max-sessions`, default `4`, valid range `1..4`.
Supply it as a JVM property (`-Dui2.policy.cp.max-sessions=1`) or the worker
environment variable `UI2_POLICY_CP_MAX_SESSIONS=1`. The JVM property takes
precedence. Set it to `1` to use the existing serial collector; restart the
worker to apply a change. No automatic increase above four is permitted.

## Validation boundary

Required automated cases: out-of-order page assembly, cross-layer/domain
slot filling, ramp/halve and single retry, cancellation with in-flight reads,
serial equivalence, changed-only reuse, and session cleanup on failure.
This local lane may not contact a host/device, deploy or push. Real-MDS
parallel performance and safety remain unverified until the engineering
session obtains authorized real-environment evidence.
