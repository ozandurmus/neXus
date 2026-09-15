# PO Decision Record — 2026-09-15 B — Session policy, lockout, and what an administrator can see

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-15.** Given in session. Successor
clauses to `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`, which is not edited in
place: this record supersedes the specific values and rules named below and
leaves the rest of `C3` — the state machine, the `end_reason` vocabulary, the
`E1`–`E6` gate chain, the heartbeat rule — exactly as it stands.

## 1. SP-1. The idle timeout is five minutes, and it is configurable

Five minutes from `last_seen_at`, recomputed into `idle_deadline_at` on every
request that passes `E1`, by the mechanism `C3` §3.4 already fixes. Supersedes
`C3`s thirty minutes.

It is **configurable by an administrator** from the administration surface, not
a source constant. Two limits, because a configurable security control that an
operator can disable is not a control: the value has a **maximum the
administrator cannot exceed**, and every change to it is an audited mutation
naming the actor, the old value and the new one. The maximum is not fixed here;
the implementing contract picks one and states why.

The absolute lifetime is unchanged and is not made configurable by this record.

## 2. SP-2. Concurrent sessions per identity are a policy, not a constant

`C3` fixes one active session per identity. That stays the **default**, and it
becomes an administrator-visible policy with a stated value rather than an
invisible rule: the administration surface shows how many concurrent sessions an
identity may hold, and the value is auditable when changed.

Raising it above one has a consequence the implementing contract must carry:
`C3`s `login_elsewhere` end reason exists because a second login ends the first.
A policy that permits two sessions must say what happens to the third, and must
not silently weaken the single-session guarantee for identities that still
depend on it.

## 3. SP-3. Five failed password attempts lock the account

An identity that accumulates **five consecutive failed authentication attempts**
is locked. A locked identity cannot authenticate, and the refusal is
distinguishable in the audit trail from a wrong password.

Three constraints:

- The counter resets on a successful authentication, never on elapsed time
  alone unless the implementing contract states an explicit window and why.
- The refusal shown to the browser does **not** reveal whether the account
  exists, is locked, or simply has the wrong password. Lockout is an
  availability control for the account holder and must not become an account
  enumeration oracle for everyone else.
- Lock and unlock are both audited mutations naming the actor.

## 4. SP-4. An administrator can see who is connected, and from where

The administration surface shows the currently active sessions: which identity,
since when, last seen when, and **the address the session is being used from**.
The same information is recorded in the audit trail at session creation.

The address is operational identity under `AGENTS.md`s sensitive identity
reporting law, so its handling is constrained: it is shown to an administrator
who already holds the role, it is never included in an artefact that leaves the
product, and the redaction rules of `UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md`
govern how it appears in audit presentation.

## 5. SP-5. An administrator can unlock an account

The administration surface carries an unlock action for a locked identity. It is
a mutation, it is audited, and it is available only to an identity holding the
administrative role.

## 6. What this record does not decide

The maximum configurable idle timeout, the concurrent-session ceiling, whether
the failed-attempt counter has a time window, and how an address is stored and
for how long. Each is a real design question and belongs in the implementing
contract, which is written before the code and names its own answer.

It does not decide anything about LDAP. Directory-backed authentication is a
separate line of work and these rules are written so that they hold for local
identities today and do not prejudge it.

## 7. Cross-references

- `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` — the session model left intact,
  and the two values this record replaces.
- `UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md` (FROZEN) — how an address and an
  actor appear in audit presentation.
- `AGENTS.md` — authority hierarchy; a FROZEN contract is superseded by a later
  record, never edited in place.
