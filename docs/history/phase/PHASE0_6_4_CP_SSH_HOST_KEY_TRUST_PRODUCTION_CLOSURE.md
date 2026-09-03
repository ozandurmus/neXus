# 0.6.4 — CP SSH Host-Key Trust Production Closure

## Status

**PLANNED — architecture contract frozen 2026-08-27**

Product baseline: `0.6.3 DONE`.

This is a bounded production-read-trust increment. It closes the operational
admission path for the existing opt-in strict Check Point SSH host-key mode.
It does not add collection coverage, device commands, write automation, or a
new credential mechanism.

## Objective

Make the existing strict host-key option usable as a production gate for the
supported Check Point read-only paths. When strict mode is explicitly enabled,
the client must use locally provisioned trusted host keys and fail closed before
device collection when that trust material cannot be used.

Compatibility mode remains explicitly opt-in for this build: existing default
behavior is preserved unless a local strict-trust policy enables the path.

## Scope

### In scope

- Audit the existing CP MDS, VSX and direct-SSH strict host-key hooks and their
  configuration consumers.
- Define one local-runtime, value-free strict-trust policy contract for trusted
  `known_hosts` material and strict-mode enablement.
- Make strict-mode preflight deterministic: absent, unreadable or malformed
  local trust policy/material fails before any SSH connection attempt.
- Preserve and test `RejectPolicy` plus system/local `known_hosts` loading for
  each supported CP SSH client path.
- Emit only safe operational state such as `strict_host_key=enabled` and a
  generic preflight reason; never emit key material, endpoint identity, user
  identity, paths or credentials.
- Add synthetic automated tests and a human production validation protocol.

### Explicitly out of scope

- New device commands, shell behavior, collection targets, retries, timeouts,
  polling frequency, concurrency, coordinator behavior or session reuse.
- SSH trust-on-first-use, automatic host-key enrollment, host-key rotation,
  endpoint discovery or any network access by the trust-policy parser.
- Storing host keys, credentials, endpoint identities or `known_hosts` content
  in the repository, HTML export, support bundle, logs, CAS or project metadata.
- PAN TLS/CA trust, OIDC, deployment/server work, CAS changes and native backup.
- Firewall write/change automation.

## Architecture decision

### Selected contract

Strict host-key verification is a **local admission prerequisite**, not
collection evidence. A local RuntimeRoot policy selects strict mode and the
trusted `known_hosts` source. The source is resolved before a client attempts
connection. Strict mode uses Paramiko `RejectPolicy`; no missing-key acceptance
or fallback to `AutoAddPolicy` is permitted.

The existing compatibility path stays unchanged when strict mode is disabled.
The build must not silently make existing estates strict by default.

### Supported paths

| Path | Strict-mode requirement |
| --- | --- |
| CP MDS / management-mediated SSH | Existing strict toggle uses trusted host-key loading and `RejectPolicy`. |
| VSX SSH | Existing strict toggle uses the same fail-closed preflight contract before physical endpoint connection. |
| Direct-SSH probe / configuration path | Existing strict toggle uses the same preflight contract before connection. |

An implementation may centralize common policy validation, but must preserve
the current public configuration compatibility surface until a separately
approved migration is accepted.

## Security and privacy contract

1. Strict enabled plus invalid/unavailable trust material means **no SSH
   connection attempt** and an explicit safe failure result.
2. Strict enabled never falls back to `AutoAddPolicy`, host-key acceptance or
   trust-on-first-use.
3. Strict disabled preserves current compatibility behavior exactly; it is not
   evidence of production trust.
4. Private host keys, public-key lines, fingerprints, endpoint names,
   addresses, usernames, credentials and local absolute paths never enter
   browser payloads, logs, tests, support artifacts or project metadata.
5. Trusted host-key material remains local runtime input and is excluded from
   version control.
6. The change is SSH-client admission only: command vocabulary, Expert/Clish
   semantics, VSX `vsenv` context identity, ClusterXL `MEMBER_SPECIFIC`
   semantics and CP secret-aware configuration handling remain unchanged.

## Failure semantics

- Policy absent or strict disabled: continue through the existing compatibility
  behavior with no new network call or altered collector result semantics.
- Strict policy malformed, trust source missing/unreadable, or no usable host
  keys: fail before connection with a value-free `strict_host_key_preflight`
  reason.
- Trusted key mismatch or unknown key in strict mode: connection is rejected;
  the result remains a bounded transport/collection failure and exposes no
  host-key value.
- A successful TCP/SSH session never proves device identity by itself; existing
  collector identity gates remain authoritative.

## Implementation plan

1. Audit strict host-key behavior in CP MDS, VSX and direct-SSH consumers plus
   their existing tests; record the exact compatibility surface.
2. Add/align a local policy parser and shared preflight helper only if the audit
   identifies divergent fail-open behavior.
3. Wire safe preflight outcome into each existing strict path without changing
   collector command lists, retry, timeout, frequency or concurrency.
4. Add synthetic tests using generated test keys/temporary `known_hosts` files;
   do not commit real host-key material or identities.
5. Run targeted CP transport tests, affected CP configuration/probe regression,
   the repository privacy gate, and regression sized to the actual shared-core
   change.
6. Perform the approved human production validation against a locally
   provisioned trusted-host entry before advancing beyond
   `AUTOMATED_VALIDATED`.

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | Strict mode is explicit and disabled by default; existing compatibility mode remains source-compatible. |
| AC-2 | Each CP MDS, VSX and direct-SSH strict path loads trusted host keys and sets `RejectPolicy` before connect. |
| AC-3 | Missing, unreadable or malformed strict trust input returns a value-free preflight failure and makes zero connection attempts. |
| AC-4 | Strict-mode unknown/mismatched host key is rejected with no fallback or sensitive telemetry. |
| AC-5 | Existing CP command, Expert/explicit-Clish, direct-Clish, VSX context, retry, timeout, polling and concurrency semantics are unchanged. |
| AC-6 | Tests and output contain no real host keys, endpoints, users, credentials, fingerprints or absolute runtime paths. |
| AC-7 | Targeted transport/collector regression and repository privacy gate pass; known xfails remain unchanged. |

## Human production validation gate

This build is not `DONE` from automated tests alone. A human must provision
trusted `known_hosts` data locally and validate, using an approved read-only
representative CP path:

1. strict disabled preserves the established compatibility baseline;
2. strict enabled with the trusted local entry completes the existing read-only
   operation;
3. strict enabled with a deliberately unavailable policy/source fails before
   connection; and
4. no new connection frequency or concurrency is introduced.

Only value-free summary evidence may be committed to project state.

## Merge gate and definition of done

Merge to `main` is blocked until AC-1 through AC-7 have automated evidence,
the diff shows no new device command or interaction-safety drift, and the
privacy gate is clean. The build may become `AUTOMATED_VALIDATED` after those
local gates. It becomes `REAL_ENV_VALIDATED` only after the human production
trust validation gate passes; then it may become `DONE`.

## Deferred follow-up

- PAN corporate-CA/TLS verification is a separate P0 trust build.
- Host-key rotation and managed enterprise credential/trust vault integration
  remain deployment-era work and require a dedicated security contract.
## Correction note — OP.0b S8-P0.1 strict trust-store preflight (2026-09-03)

Status of this section: **implementation-status correction only**. No
acceptance criterion, failure semantic, security boundary or deployment model
above changes; this records that the shipped 0.6.4 preflight did not implement
AC-2/AC-3 as written, and how that was corrected.

**Defect (found by the S8 preparation, reproduced against real Paramiko):**
`paramiko.SSHClient.load_system_host_keys()` populates the client's read-only
*system* host-key store; `SSHClient.get_host_keys()` returns the separate
writable *local* store (the one `load_host_keys()` / `AutoAddPolicy` fill).
The 0.6.4 helper loaded the former and gated on the latter, so
`strict=True` raised `CpSshStrictPreflightError` even with a correctly
provisioned system/user `known_hosts` (the DEV.2.2 `/root/.ssh/known_hosts`
mount, or the operator profile on Windows). Strict mode was therefore not
usable; the 0.6.4 R3 result ("strict + no trust fails before connect") was
correct, while R2 ("strict + provisioned entry completes the read-only
operation") could never have passed. The synthetic tests of the era encoded
the same wrong store assumption through `get_host_keys()` mocks.

**Correction (`utils/cp_ssh_trust.py`, all five CP SSH callers inherit it):**
the strict precondition now means *at least one configured trusted host-key
source was successfully loaded*. The helper names the system `known_hosts`
explicitly (the same OpenSSH user path Paramiko resolves with no filename),
loads it into the client's system store through the public
`load_system_host_keys(filename)` API, and derives the cardinality by parsing
the same file with the public `paramiko.HostKeys` parser — no Paramiko private
attribute is read by product code. Missing/unreadable and malformed sources
now fail closed with distinct value-free reason tokens
(`trust_source_unreadable`, `trust_source_malformed`,
`no_usable_host_keys_loaded`); Paramiko's parse exception, which carries the
offending line, is deliberately not chained. Order is unchanged: trusted keys
loaded → `RejectPolicy` installed → cardinality confirmed → only then may
`connect()` be called. Compatibility mode (`strict=False`) is byte-for-byte
the pre-existing behavior.

**Explicitly unchanged:** no `AutoAddPolicy`/`WarningPolicy`/TOFU/automatic
enrollment/`save_host_keys`/`ssh-keyscan` in the strict path; no new
environment variable, CLI flag or configuration surface; observed
compatibility-mode fingerprints stay `observe_and_record_not_production` and
are never promoted to trust; no new device command or network behavior.

**On the "RuntimeRoot policy selects the trusted `known_hosts` source"
wording above:** as implemented since 0.6.4, strict mode is selected by the
existing per-path environment toggles and the trusted source is the system/
user `known_hosts` only. No explicit RuntimeRoot trusted-source path exists in
the repository; this correction does not add one (separate PO approval
required). System `known_hosts` alone is sufficient and needs no duplication
into a second Paramiko store.

**Evidence:** `tests/test_op0b_s8_p01_cp_ssh_trust_preflight_correction.py`
(real `paramiko.SSHClient`, temporary synthetic `known_hosts`, in-process
generated keys, `connect()` replaced by a sentinel — no network); the
populated-store reproducer fails against the pre-correction helper and passes
after it. `tests/test_phase0_6_4_cp_ssh_host_key_trust_closure.py` and
`tests/test_phase0_6_1b_1_4_cp_ssh_trust.py` strict paths now decide trust
over a real isolated synthetic file rather than a `get_host_keys()` mock.
R2 (strict + provisioned entry, real MDS) remains owed on the production
server (`cp_ssh_trust_r2_prod_server`); it is now reachable.
